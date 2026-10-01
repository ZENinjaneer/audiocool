"""The session library on disk.

Same layout as the phone's backup folder, so a backup can be imported or used directly:

    <library>/2026-09-22_15-05_a1b2c3d4e5f6/
        session.json            the session (phone format), with the best transcripts merged in
        recording-1.m4a         audio, exactly as uploaded
        notes.md                human-readable notes and transcript
        audiocool-desktop.json  desktop-only data (see below)

``audiocool-desktop.json`` (the "sidecar") holds what the desktop owns: transcripts made here
(per recording, with the model that made them), title edits made in the web UI, and cached audio
durations. The phone never writes that file, so even if the phone (or a sync tool) overwrites
session.json, the desktop's transcripts survive and are merged back in.
"""

from __future__ import annotations

import copy
import json
import logging
import os
import secrets
import shutil
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from . import sessionfmt as fmt
from .config import atomic_write_text
from .sessionfmt import AUDIO_NAME, BadInput

log = logging.getLogger(__name__)

SESSION_JSON = "session.json"
NOTES_MD = "notes.md"
SIDECAR = "audiocool-desktop.json"
PHONE_MODEL = "phone"
# A replacement audio file whose length differs by more than this is a different recording
# (e.g. the first upload was cut short), so a transcript of the old file no longer applies.
STALE_TRANSCRIPT_MS = 2000


class NotFound(LookupError):
    """Unknown session, recording or file; the API turns it into a 404 response."""


def new_sidecar() -> dict:
    return {"version": 1, "transcripts": {}, "audio": {}}


def _read_json(path: Path) -> Any:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _stat_key(folder: Path) -> tuple:
    """Changes whenever session.json or the sidecar changes on disk."""
    try:
        st = (folder / SESSION_JSON).stat()
    except FileNotFoundError:
        return ()
    try:
        sc = (folder / SIDECAR).stat()
        sc_key = (sc.st_mtime_ns, sc.st_size)
    except FileNotFoundError:
        sc_key = (0, 0)
    return (st.st_mtime_ns, st.st_size) + sc_key


@dataclass
class Entry:
    """One session as loaded from its folder."""

    id: str
    folder: Path
    session: dict  # effective view: phone data with desktop transcripts and title merged in
    sidecar: dict
    key: tuple = ()
    _search: list | None = field(default=None, repr=False)

    def recording(self, rec_id: str) -> dict | None:
        return fmt.recording(self.session, rec_id)

    def desktop_transcript(self, rec_id: str) -> dict | None:
        return self.sidecar.get("transcripts", {}).get(rec_id)

    def transcript_models(self) -> dict[str, str]:
        """recording id -> id of the model that made the transcript the desktop holds for it."""
        return {rid: t["model"] for rid, t in self.sidecar.get("transcripts", {}).items() if self.recording(rid)}

    def model_names(self) -> dict[str, str]:
        return {rid: t.get("modelName") or t["model"] for rid, t in self.sidecar.get("transcripts", {}).items()}

    def audio_file(self, rec: dict) -> Path | None:
        """The recording's audio on disk. While a renamed file (.aac -> .m4a) is still on its way,
        the old one with the same number stands in."""
        path = self.folder / rec["file"]
        if path.is_file():
            return path
        stem = Path(rec["file"]).stem
        for ext in (".m4a", ".aac"):
            alt = self.folder / f"{stem}{ext}"
            if alt.is_file():
                return alt
        return None

    def has_audio(self, rec: dict) -> bool:
        return (self.folder / rec["file"]).is_file()

    def audio_duration_ms(self, rec: dict) -> int:
        """Length of the recording: the phone's figure, else what the desktop measured."""
        d = int(rec.get("durationMs") or 0)
        if d > 0:
            return d
        cached = self.sidecar.get("audio", {}).get(rec["file"]) or {}
        return int(cached.get("durationMs") or 0)


def merge(base: dict, sidecar: dict) -> dict:
    """The session as the desktop presents it: desktop transcripts and title edits win."""
    s = copy.deepcopy(base)
    title = sidecar.get("title")
    if isinstance(title, dict) and isinstance(title.get("value"), str):
        s["title"] = title["value"]
    transcripts = sidecar.get("transcripts", {})
    for rec in s.get("recordings", []):
        rec.pop("transcriptModel", None)
        d = transcripts.get(rec["id"])
        if d is not None:
            rec["transcript"] = copy.deepcopy(d["segments"])
            rec["transcriptModel"] = d["model"]
    return s


def _apply_title_rule(base_title: str, sidecar: dict) -> bool:
    """Keeps a title edited on the desktop until the phone renames the session itself.

    The sidecar remembers which phone title the edit replaced; if the phone now sends a different
    title, the phone's rename is newer and wins. Returns True if the sidecar changed.
    """
    t = sidecar.get("title")
    if not isinstance(t, dict):
        return False
    if base_title in (t.get("phoneTitle"), t.get("value")):
        return False
    sidecar.pop("title", None)
    return True


class Library:
    def __init__(self, root: Path, probe_duration: Callable[[Path], int] | None = None):
        self._lock = threading.RLock()
        self._entries: dict[str, Entry] = {}
        self._by_folder: dict[Path, Entry] = {}
        self._last_scan = 0.0
        self._probe = probe_duration
        self.root = Path(root)
        self.set_root(self.root)

    # -- scanning ---------------------------------------------------------------------------

    def set_root(self, root: Path) -> None:
        with self._lock:
            self.root = Path(root).expanduser()
            self.root.mkdir(parents=True, exist_ok=True)
            self._entries.clear()
            self._by_folder.clear()
            self.refresh(force=True)

    def refresh(self, force: bool = False, max_age: float = 1.0) -> None:
        """Picks up sessions added, changed or removed on disk (e.g. by a backup sync)."""
        with self._lock:
            now = time.monotonic()
            if not force and now - self._last_scan < max_age:
                return
            self._last_scan = now
            found: dict[str, Entry] = {}
            by_folder: dict[Path, Entry] = {}
            try:
                dirs = [Path(d.path) for d in os.scandir(self.root) if d.is_dir() and not d.name.startswith(".")]
            except FileNotFoundError:
                self.root.mkdir(parents=True, exist_ok=True)
                dirs = []
            for folder in sorted(dirs):
                key = _stat_key(folder)
                if not key:
                    continue
                entry = self._by_folder.get(folder)
                if entry is None or entry.key != key:
                    entry = self._load(folder)
                    if entry is None:
                        continue
                by_folder[folder] = entry
                other = found.get(entry.id)
                if other is not None:
                    keep = entry if entry.session.get("updatedAt", 0) > other.session.get("updatedAt", 0) else other
                    log.warning("Session %s is in two folders (%s, %s); using %s", entry.id, other.folder.name, folder.name, keep.folder.name)
                    entry = keep
                found[entry.id] = entry
            self._entries = found
            self._by_folder = by_folder

    def _load(self, folder: Path) -> Entry | None:
        try:
            raw = _read_json(folder / SESSION_JSON)
            base = fmt.validate_session(raw)
        except (OSError, ValueError) as e:
            log.warning("Skipping %s: unreadable session.json (%s)", folder, e)
            return None
        sidecar = new_sidecar()
        try:
            if (folder / SIDECAR).exists():
                loaded = _read_json(folder / SIDECAR)
                if isinstance(loaded, dict):
                    sidecar.update(loaded)
                    sidecar.setdefault("transcripts", {})
                    sidecar.setdefault("audio", {})
        except (OSError, ValueError) as e:
            log.warning("Ignoring unreadable %s in %s (%s)", SIDECAR, folder.name, e)
        if _apply_title_rule(base.get("title", ""), sidecar):
            self._write_sidecar(folder, sidecar)
        return Entry(id=base["id"], folder=folder, session=merge(base, sidecar), sidecar=sidecar, key=_stat_key(folder))

    def entries(self) -> list[Entry]:
        self.refresh()
        with self._lock:
            return list(self._entries.values())

    def get(self, session_id: str) -> Entry | None:
        with self._lock:
            entry = self._entries.get(session_id)
            if entry is not None:
                key = _stat_key(entry.folder)
                if key == entry.key:
                    return entry
                if key:
                    fresh = self._load(entry.folder)
                    if fresh is not None and fresh.id == session_id:
                        self._remember(fresh)
                        return fresh
            self.refresh(force=entry is not None)
            return self._entries.get(session_id)

    def require(self, session_id: str) -> Entry:
        entry = self.get(session_id)
        if entry is None:
            raise NotFound(f"no session {session_id}")
        return entry

    def _remember(self, entry: Entry) -> None:
        entry.key = _stat_key(entry.folder)
        entry._search = None
        self._entries[entry.id] = entry
        self._by_folder[entry.folder] = entry

    # -- writing ----------------------------------------------------------------------------

    @staticmethod
    def _write_sidecar(folder: Path, sidecar: dict) -> None:
        atomic_write_text(folder / SIDECAR, json.dumps(sidecar, indent=2, ensure_ascii=False) + "\n")

    def _write(self, folder: Path, base: dict, sidecar: dict, sidecar_changed: bool = True) -> Entry:
        """Saves a session (phone data + sidecar) and regenerates notes.md."""
        effective = merge(base, sidecar)
        folder.mkdir(parents=True, exist_ok=True)
        if sidecar_changed or not (folder / SIDECAR).exists():
            self._write_sidecar(folder, sidecar)
        atomic_write_text(folder / SESSION_JSON, json.dumps(effective, indent=2, ensure_ascii=False) + "\n")
        entry = Entry(id=base["id"], folder=folder, session=effective, sidecar=sidecar)
        md = fmt.session_markdown(effective, include_transcript=True, model_names=entry.model_names())
        md_path = folder / NOTES_MD
        try:
            unchanged = md_path.read_text(encoding="utf-8") == md
        except OSError:
            unchanged = False
        if not unchanged:
            atomic_write_text(md_path, md)
        self._remember(entry)
        return entry

    def _new_folder(self, session: dict) -> Path:
        folder = self.root / fmt.folder_name(session)
        n = 2
        while folder.exists() and (folder / SESSION_JSON).exists():
            try:
                if _read_json(folder / SESSION_JSON).get("id") == session["id"]:
                    return folder
            except (OSError, ValueError, AttributeError):
                pass
            folder = self.root / f"{fmt.folder_name(session)}-{n}"
            n += 1
        return folder

    def put_session(self, session: dict, files: dict[str, int]) -> list[str]:
        """Stores a session sent by the phone; returns the audio files it should upload.

        A file is needed when the phone offers it (it is in ``files``), the session references
        it, and the desktop doesn't have it at that size. Transcripts made on the desktop are kept.
        """
        with self._lock:
            entry = self.get(session["id"])
            if entry is not None:
                folder, sidecar = entry.folder, copy.deepcopy(entry.sidecar)
            else:
                folder, sidecar = self._new_folder(session), new_sidecar()
            _apply_title_rule(session.get("title", ""), sidecar)
            self._write(folder, session, sidecar)
            needed = []
            for rec in session.get("recordings", []):
                name = rec["file"]
                if name not in files:
                    continue
                path = folder / name
                if not path.is_file() or path.stat().st_size != files[name]:
                    needed.append(name)
            self._remove_replaced_audio(folder, session)
            return needed

    def _remove_replaced_audio(self, folder: Path, session: dict) -> None:
        """Deletes audio the session no longer uses once its replacement is here (.aac -> .m4a)."""
        used = {r["file"] for r in session.get("recordings", [])}
        present_stems = {Path(n).stem for n in used if (folder / n).is_file()}
        for path in folder.iterdir():
            if AUDIO_NAME.fullmatch(path.name) and path.name not in used and path.stem in present_stems:
                log.info("Removing %s/%s (replaced)", folder.name, path.name)
                path.unlink(missing_ok=True)

    def upload_temp_path(self, session_id: str, name: str) -> Path:
        entry = self.require(session_id)
        return entry.folder / f".{name}.{secrets.token_hex(6)}.part"

    def commit_audio(self, session_id: str, name: str, tmp: Path) -> None:
        """Moves a fully received upload into place (atomically) and tidies up after it."""
        duration = self._probe_safely(tmp)
        with self._lock:
            entry = self.get(session_id)
            rec = None if entry is None else next((r for r in entry.session["recordings"] if r["file"] == name), None)
            if entry is None or rec is None:
                tmp.unlink(missing_ok=True)
                raise NotFound(f"{name} is not part of session {session_id}")
            os.replace(tmp, entry.folder / name)
            sidecar = copy.deepcopy(entry.sidecar)
            st = (entry.folder / name).stat()
            sidecar.setdefault("audio", {})[name] = {"size": st.st_size, "durationMs": duration}
            old = sidecar.get("transcripts", {}).get(rec["id"])
            if old and duration and old.get("audioDurationMs") and abs(duration - old["audioDurationMs"]) > STALE_TRANSCRIPT_MS:
                log.info("New audio for %s/%s is %d ms (transcript was of %d ms); dropping the old transcript",
                         session_id, rec["id"], duration, old["audioDurationMs"])
                sidecar["transcripts"].pop(rec["id"], None)
            base = self._base(entry)
            self._write(entry.folder, base, sidecar)
            self._remove_replaced_audio(entry.folder, base)

    def _probe_safely(self, path: Path) -> int:
        if self._probe is None:
            return 0
        try:
            return int(self._probe(path))
        except Exception as e:  # a bad file still gets stored; transcription will report the problem
            log.warning("Couldn't read the length of %s: %s", path.name, e)
            return 0

    @staticmethod
    def _base(entry: Entry) -> dict:
        """The phone's part of a session: the effective view minus what the sidecar contributes.

        Where a desktop transcript replaced the phone's, the phone's version isn't kept, so the
        recording has no transcript of its own until the phone sends one again.
        """
        base = copy.deepcopy(entry.session)
        owned = entry.sidecar.get("transcripts", {})
        for rec in base.get("recordings", []):
            rec.pop("transcriptModel", None)
            if rec["id"] in owned:
                rec.pop("transcript", None)
        return base

    def set_transcript(self, session_id: str, rec_id: str, segments: list[dict], model: str, model_name: str, **meta: Any) -> None:
        """Saves a transcript made on the desktop; it replaces the phone's from now on."""
        with self._lock:
            entry = self.require(session_id)
            if entry.recording(rec_id) is None:
                raise NotFound(f"no recording {rec_id} in session {session_id}")
            sidecar = copy.deepcopy(entry.sidecar)
            sidecar.setdefault("transcripts", {})[rec_id] = {
                "model": model,
                "modelName": model_name,
                "createdAt": fmt.now_ms(),
                "edited": False,
                **meta,
                "segments": [{"s": int(s["s"]), "e": int(s["e"]), "t": str(s["t"])} for s in segments],
            }
            self._write(entry.folder, self._base(entry), sidecar)

    def edit_transcript_line(self, session_id: str, rec_id: str, index: int, text: str, start_ms: int | None = None) -> dict:
        """Corrects one transcript line from the web UI. Editing the phone's transcript makes it a
        desktop-owned copy (model "phone"), so the correction survives the next sync."""
        with self._lock:
            entry = self.require(session_id)
            rec = entry.recording(rec_id)
            if rec is None:
                raise NotFound(f"no recording {rec_id} in session {session_id}")
            sidecar = copy.deepcopy(entry.sidecar)
            record = sidecar.setdefault("transcripts", {}).get(rec_id)
            if record is None:
                if not rec.get("transcript"):
                    raise NotFound("this recording has no transcript")
                record = {"model": PHONE_MODEL, "modelName": "Phone", "createdAt": fmt.now_ms(),
                          "segments": copy.deepcopy(rec["transcript"])}
                sidecar["transcripts"][rec_id] = record
            segs = record["segments"]
            if not 0 <= index < len(segs):
                raise BadInput("no such transcript line")
            if start_ms is not None and segs[index]["s"] != start_ms:
                raise BadInput("the transcript changed; reload and try again")
            segs[index]["t"] = text.strip()
            record["edited"] = True
            record["editedAt"] = fmt.now_ms()
            self._write(entry.folder, self._base(entry), sidecar)
            return segs[index]

    def rename(self, session_id: str, title: str) -> None:
        title = title.strip()
        if not title:
            raise BadInput("the title can't be empty")
        with self._lock:
            entry = self.require(session_id)
            sidecar = copy.deepcopy(entry.sidecar)
            previous = sidecar.get("title")
            phone_title = previous.get("phoneTitle") if isinstance(previous, dict) else entry.session.get("title", "")
            sidecar["title"] = {"value": title, "phoneTitle": phone_title}
            base = self._base(entry)
            self._write(entry.folder, base, sidecar)

    def delete(self, session_id: str) -> None:
        with self._lock:
            entry = self.require(session_id)
            shutil.rmtree(entry.folder)
            self._entries.pop(session_id, None)
            self._by_folder.pop(entry.folder, None)

    # -- importing --------------------------------------------------------------------------

    def import_folder(self, src: Path) -> str:
        """Imports one session folder in the backup layout. Returns 'added', 'updated' or 'unchanged'.

        The newer copy of the session data wins (by updatedAt); audio the library lacks (or has
        at a different size) is copied; transcripts made on the desktop are kept.
        """
        src = Path(src)
        try:
            session = fmt.validate_session(_read_json(src / SESSION_JSON))
        except (OSError, ValueError) as e:
            raise BadInput(f"{src.name}: unreadable session.json ({e})") from e
        with self._lock:
            entry = self.get(session["id"])
            if entry is not None and entry.folder.resolve() == src.resolve():
                return "unchanged"
            status = "unchanged"
            if entry is None:
                folder = self._new_folder(session)
                sidecar = new_sidecar()
                if (src / SIDECAR).is_file():
                    try:
                        loaded = _read_json(src / SIDECAR)
                        if isinstance(loaded, dict):
                            sidecar.update(loaded)
                    except (OSError, ValueError):
                        pass
                _apply_title_rule(session.get("title", ""), sidecar)
                base = session
                status = "added"
            else:
                folder, sidecar = entry.folder, copy.deepcopy(entry.sidecar)
                if session.get("updatedAt", 0) > entry.session.get("updatedAt", 0):
                    _apply_title_rule(session.get("title", ""), sidecar)
                    base = session
                    status = "updated"
                else:
                    base = self._base(entry)
            folder.mkdir(parents=True, exist_ok=True)
            copied = False
            for rec in base.get("recordings", []):
                s_path, d_path = src / rec["file"], folder / rec["file"]
                if s_path.is_file() and (not d_path.is_file() or d_path.stat().st_size != s_path.stat().st_size):
                    tmp = folder / f".{rec['file']}.{secrets.token_hex(6)}.part"
                    try:
                        shutil.copyfile(s_path, tmp)
                        os.replace(tmp, d_path)
                    finally:
                        tmp.unlink(missing_ok=True)
                    copied = True
            if status != "unchanged" or copied:
                self._write(folder, base, sidecar)
                self._remove_replaced_audio(folder, base)
                if status == "unchanged":
                    status = "updated"
            return status

    def import_tree(self, src: Path, max_depth: int = 3) -> dict:
        """Imports every session folder found under ``src`` (a backup folder, or one session)."""
        src = Path(src).expanduser()
        if not src.is_dir():
            raise BadInput(f"{src} is not a folder")
        result: dict[str, Any] = {"added": 0, "updated": 0, "unchanged": 0, "errors": [], "sessions": []}
        for folder in _session_folders(src, max_depth):
            try:
                status = self.import_folder(folder)
                result[status] += 1
                result["sessions"].append({"folder": folder.name, "status": status})
            except (BadInput, OSError) as e:
                result["errors"].append(f"{folder.name}: {e}")
        if not result["sessions"] and not result["errors"]:
            raise BadInput("no sessions found (looking for folders that contain session.json)")
        return result


def _session_folders(root: Path, max_depth: int) -> list[Path]:
    found = []

    def walk(folder: Path, depth: int) -> None:
        if (folder / SESSION_JSON).is_file():
            found.append(folder)
            return
        if depth >= max_depth:
            return
        try:
            children = sorted(p for p in folder.iterdir() if p.is_dir() and not p.name.startswith("."))
        except OSError:
            return
        for child in children:
            walk(child, depth + 1)

    walk(root, 0)
    return found
