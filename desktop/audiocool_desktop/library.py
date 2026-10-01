"""The session library on disk.

Same layout as the phone's backup folder, so a backup can be imported or used directly:

    <library>/2026-09-22_15-05_a1b2c3d4e5f6/
        session.json            the session (phone format), with the best transcripts merged in
        recording-1.m4a         audio, exactly as uploaded
        photo-<id>.jpg          photos attached to notes (they arrive with backups, not the API)
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
import re
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
#: Photos the phone attaches to notes (BackupManager.PHOTO in the app is photo-[\w-]+\.jpg).
PHOTO_NAME = re.compile(r"photo-[\w-]+\.(jpg|jpeg|png|webp)", re.IGNORECASE)
# A replacement audio file whose length differs by more than this is a different recording
# (e.g. the first upload was cut short), so a transcript of the old file no longer applies.
STALE_TRANSCRIPT_MS = 2000


class NotFound(LookupError):
    """Unknown session, recording or file; the API turns it into a 404 response."""


def new_sidecar() -> dict:
    return {"version": 1, "transcripts": {}, "audio": {}}


def clean_sidecar(raw: Any) -> dict:
    """A sidecar read from disk, keeping only well-formed parts (it may be hand-edited or old)."""
    out = new_sidecar()
    if not isinstance(raw, dict):
        return out
    for key, value in raw.items():
        if key not in ("transcripts", "audio", "title"):
            out[key] = value
    transcripts = raw.get("transcripts")
    if isinstance(transcripts, dict):
        for rid, t in transcripts.items():
            if not (isinstance(t, dict) and isinstance(t.get("model"), str)):
                continue
            try:
                segments = fmt.validate_transcript(t.get("segments"), "segments")
            except BadInput:
                continue
            out["transcripts"][str(rid)] = {**t, "segments": segments}
    if isinstance(raw.get("audio"), dict):
        out["audio"] = {k: v for k, v in raw["audio"].items() if isinstance(v, dict)}
    title = raw.get("title")
    if isinstance(title, dict) and isinstance(title.get("value"), str):
        out["title"] = title
    return out


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


def _copy_into(src: Path, folder: Path, name: str) -> None:
    """Copies a file into a session folder atomically."""
    tmp = folder / f".{name}.{secrets.token_hex(6)}.part"
    try:
        shutil.copyfile(src, tmp)
        os.replace(tmp, folder / name)
    finally:
        tmp.unlink(missing_ok=True)


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

    def photo_names(self) -> list[str]:
        """Photo files the session's notes refer to (safe names only)."""
        names = []
        for note in self.session.get("notes", []):
            name = note.get("photo")
            if isinstance(name, str) and PHOTO_NAME.fullmatch(name) and name not in names:
                names.append(name)
        return names

    def photo_file(self, name: str) -> Path | None:
        if name not in self.photo_names():
            return None
        path = self.folder / name
        return path if path.is_file() else None

    def thumbnail(self) -> str | None:
        """The photo that stands for the session in lists, chosen as the app does: the note named
        by ``thumbnail``, else the first photo in timeline order (if its file is here)."""
        notes = [n for n in fmt.ordered_notes(self.session) if isinstance(n.get("photo"), str)]
        chosen = next((n for n in notes if n.get("id") == self.session.get("thumbnail")), None) or (notes[0] if notes else None)
        if chosen is None:
            return None
        return chosen["photo"] if self.photo_file(chosen["photo"]) else None


def merge(base: dict, sidecar: dict) -> dict:
    """The session as the desktop presents it: desktop transcripts and title edits win.

    ``transcriptModel`` (which the app also writes, for its own transcripts) is set to the model
    of the desktop's transcript where there is one, and left as the phone sent it elsewhere.
    """
    s = copy.deepcopy(base)
    title = sidecar.get("title")
    if isinstance(title, dict) and isinstance(title.get("value"), str):
        s["title"] = title["value"]
    transcripts = sidecar.get("transcripts", {})
    for rec in s.get("recordings", []):
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
        """Reads one session folder; a damaged one is skipped (and logged), never fatal."""
        try:
            base = fmt.validate_session(_read_json(folder / SESSION_JSON))
        except (OSError, ValueError) as e:
            log.warning("Skipping %s: unreadable session.json (%s)", folder, e)
            return None
        sidecar = new_sidecar()
        if (folder / SIDECAR).exists():
            try:
                sidecar = clean_sidecar(_read_json(folder / SIDECAR))
            except (OSError, ValueError) as e:
                log.warning("Ignoring unreadable %s in %s (%s)", SIDECAR, folder.name, e)
        try:
            if _apply_title_rule(base.get("title", ""), sidecar):
                self._write_sidecar(folder, sidecar)
            return Entry(id=base["id"], folder=folder, session=merge(base, sidecar), sidecar=sidecar, key=_stat_key(folder))
        except Exception as e:  # noqa: BLE001 - one bad folder mustn't take the library down
            log.warning("Skipping %s: %s", folder, e)
            return None

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
        entry = Entry(id=base["id"], folder=folder, session=effective, sidecar=sidecar)
        # Render first, so a problem shows up before anything is half-written.
        md = fmt.session_markdown(effective, include_transcript=True, model_names=entry.model_names())
        folder.mkdir(parents=True, exist_ok=True)
        if sidecar_changed or not (folder / SIDECAR).exists():
            self._write_sidecar(folder, sidecar)
        atomic_write_text(folder / SESSION_JSON, json.dumps(effective, indent=2, ensure_ascii=False) + "\n")
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
            entry = self._write(folder, session, sidecar)
            needed = []
            # Recordings, then (an extension of the API) photos attached to notes.
            for name in [r["file"] for r in session.get("recordings", [])] + entry.photo_names():
                if name not in files:
                    continue
                path = folder / name
                if not path.is_file() or path.stat().st_size != files[name]:
                    needed.append(name)
            self._remove_replaced_audio(folder, session)
            self._remove_unused_photos(entry)
            return needed

    @staticmethod
    def _remove_unused_photos(entry: Entry) -> None:
        """Deletes photos no note uses any more (e.g. deleted on the phone), as the app's backup does."""
        used = set(entry.photo_names())
        for path in entry.folder.iterdir():
            if PHOTO_NAME.fullmatch(path.name) and path.name not in used:
                log.info("Removing %s/%s (no note uses it)", entry.folder.name, path.name)
                path.unlink(missing_ok=True)

    def commit_photo(self, session_id: str, name: str, tmp: Path) -> None:
        """Moves an uploaded photo into place (atomically)."""
        with self._lock:
            entry = self.get(session_id)
            if entry is None or name not in entry.photo_names():
                tmp.unlink(missing_ok=True)
                raise NotFound(f"{name} is not a photo of session {session_id}")
            os.replace(tmp, entry.folder / name)
            self._remember(entry)  # the summary's thumbnail may change

    def _remove_replaced_audio(self, folder: Path, session: dict) -> None:
        """Deletes audio the session no longer uses once its replacement is here (.aac -> .m4a)."""
        used = {r["file"] for r in session.get("recordings", [])}
        present_stems = {Path(n).stem for n in used if (folder / n).is_file()}
        for path in folder.iterdir():
            if AUDIO_NAME.fullmatch(path.name) and path.name not in used and path.stem in present_stems:
                log.info("Removing %s/%s (replaced)", folder.name, path.name)
                path.unlink(missing_ok=True)

    def _note_new_audio(self, sidecar: dict, folder: Path, rec: dict, duration: int) -> None:
        """Records a new audio file's length and drops a desktop transcript made of different audio."""
        name = rec["file"]
        st = (folder / name).stat()
        sidecar.setdefault("audio", {})[name] = {"size": st.st_size, "durationMs": duration}
        old = sidecar.get("transcripts", {}).get(rec["id"])
        if old and duration and old.get("audioDurationMs") and abs(duration - old["audioDurationMs"]) > STALE_TRANSCRIPT_MS:
            log.info("New audio for %s/%s is %d ms (transcript was of %d ms); dropping the old transcript",
                     folder.name, rec["id"], duration, old["audioDurationMs"])
            sidecar["transcripts"].pop(rec["id"], None)

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
            self._note_new_audio(sidecar, entry.folder, rec, duration)
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
            if rec["id"] in owned:
                rec.pop("transcript", None)
                rec.pop("transcriptModel", None)
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
                "origin": "desktop",
                "createdAt": fmt.now_ms(),
                "edited": False,
                **meta,
                "segments": [{"s": int(s["s"]), "e": int(s["e"]), "t": str(s["t"])} for s in segments],
            }
            self._write(entry.folder, self._base(entry), sidecar)

    def edit_transcript_line(self, session_id: str, rec_id: str, index: int, text: str, start_ms: int | None = None) -> dict:
        """Corrects one transcript line from the web UI. Editing the phone's transcript makes it a
        desktop-owned copy (still labelled with the phone's model), so the correction survives the
        next sync and goes back to the phone."""
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
                phone_model = rec.get("transcriptModel") if isinstance(rec.get("transcriptModel"), str) else None
                record = {"model": phone_model or PHONE_MODEL, "modelName": "Phone", "origin": "phone",
                          "createdAt": fmt.now_ms(), "segments": copy.deepcopy(rec["transcript"])}
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

        The newer copy of the session (by updatedAt) wins, files included: audio and photos the
        library lacks are always copied, and ones it has at a different size are replaced only
        when the imported copy is the newer one. Transcripts made on the desktop are kept (unless
        the audio they were made from is replaced by a recording of a different length).
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
                        sidecar = clean_sidecar(_read_json(src / SIDECAR))
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
            newer = status != "unchanged"
            folder.mkdir(parents=True, exist_ok=True)
            copied = False
            for rec in base.get("recordings", []):
                s_path, d_path = src / rec["file"], folder / rec["file"]
                if not s_path.is_file():
                    continue
                if d_path.is_file() and (not newer or d_path.stat().st_size == s_path.stat().st_size):
                    continue
                _copy_into(s_path, folder, rec["file"])
                self._note_new_audio(sidecar, folder, rec, self._probe_safely(d_path))
                copied = True
            photos = Entry(id=base["id"], folder=folder, session=base, sidecar=sidecar).photo_names()
            for name in photos:
                s_path, d_path = src / name, folder / name
                if s_path.is_file() and (not d_path.is_file() or (newer and d_path.stat().st_size != s_path.stat().st_size)):
                    _copy_into(s_path, folder, name)
                    copied = True
            if newer or copied:
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
            except Exception as e:  # noqa: BLE001 - report it and go on with the other sessions
                log.warning("Couldn't import %s: %s", folder, e)
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
