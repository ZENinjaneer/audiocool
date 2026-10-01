"""The session.json format shared with the Android app, plus the Markdown / SRT / TXT exports.

A session is plain JSON (see the app's SessionJson.kt). Times are epoch milliseconds; a note's
``offsetMs`` and a transcript segment's ``s``/``e`` are milliseconds from the start of that
recording's audio file. Unknown fields are kept, since the phone ignores them too.
"""

from __future__ import annotations

import copy
import re
import time
from datetime import datetime
from typing import Any, Iterable

AUDIO_NAME = re.compile(r"recording-\d+\.(aac|m4a)")
#: Photos the app attaches to notes (BackupManager.PHOTO in the app is photo-[\w-]+\.jpg).
PHOTO_NAME = re.compile(r"photo-[\w-]+\.(jpg|jpeg|png|webp)", re.IGNORECASE)
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
ITEM_ID_MAX = 128


class BadInput(ValueError):
    """Raised for malformed input; the API turns it into a 400 response."""


# ---------------------------------------------------------------------------------------------
# Validation


#: Largest time accepted (ms): the end of year 9999, which dates can still be formatted.
MAX_MS = 253_402_300_799_999


def _int(value: Any, what: str, default: int | None = None, minimum: int | None = 0, maximum: int | None = MAX_MS) -> int:
    if value is None:
        if default is None:
            raise BadInput(f"{what} is required")
        return default
    # bool is a subclass of int; a JSON true/false is not a number here.
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BadInput(f"{what} must be a number")
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            raise BadInput(f"{what} must be a finite number")
        value = int(value)
    if minimum is not None and value < minimum:
        raise BadInput(f"{what} must be >= {minimum}")
    if maximum is not None and value > maximum:
        raise BadInput(f"{what} is too large")
    return value


def _str(value: Any, what: str, default: str | None = None, max_len: int = 1_000_000) -> str:
    if value is None:
        if default is None:
            raise BadInput(f"{what} is required")
        return default
    if not isinstance(value, str):
        raise BadInput(f"{what} must be a string")
    if len(value) > max_len:
        raise BadInput(f"{what} is too long")
    return value


def validate_transcript(value: Any, what: str = "transcript") -> list[dict]:
    if not isinstance(value, list):
        raise BadInput(f"{what} must be a list")
    out = []
    for i, seg in enumerate(value):
        if not isinstance(seg, dict):
            raise BadInput(f"{what}[{i}] must be an object")
        s = _int(seg.get("s"), f"{what}[{i}].s")
        e = _int(seg.get("e"), f"{what}[{i}].e")
        t = _str(seg.get("t"), f"{what}[{i}].t", default="")
        clean = dict(seg)
        clean.update(s=s, e=max(e, s), t=t)
        out.append(clean)
    return out


def validate_session(obj: Any, expected_id: str | None = None) -> dict:
    """Checks a session object from the phone and returns a cleaned copy (unknown fields kept).

    Lenient where the phone's own decoder is lenient (missing numbers default to 0), strict where
    the server depends on a value: ids, and audio file names, which become paths on disk.
    """
    if not isinstance(obj, dict):
        raise BadInput("session must be an object")
    s = copy.deepcopy(obj)
    sid = _str(s.get("id"), "session.id", max_len=64)
    if not SESSION_ID.fullmatch(sid):
        raise BadInput("session.id may only contain letters, digits, '-' and '_' (max 64)")
    if expected_id is not None and sid != expected_id:
        raise BadInput("session.id does not match the URL")
    s["version"] = _int(s.get("version"), "session.version", default=1)
    s["title"] = _str(s.get("title"), "session.title", default="Untitled", max_len=10_000)
    s["createdAt"] = _int(s.get("createdAt"), "session.createdAt", default=0)
    s["updatedAt"] = _int(s.get("updatedAt"), "session.updatedAt", default=0)

    recs = s.get("recordings")
    if recs is None:
        recs = []
    if not isinstance(recs, list):
        raise BadInput("session.recordings must be a list")
    rec_ids: set[str] = set()
    files: set[str] = set()
    clean_recs = []
    for i, r in enumerate(recs):
        what = f"recordings[{i}]"
        if not isinstance(r, dict):
            raise BadInput(f"{what} must be an object")
        r = dict(r)
        rid = _str(r.get("id"), f"{what}.id", max_len=ITEM_ID_MAX)
        if not rid:
            raise BadInput(f"{what}.id must not be empty")
        if rid in rec_ids:
            raise BadInput(f"duplicate recording id {rid!r}")
        rec_ids.add(rid)
        f = _str(r.get("file"), f"{what}.file", max_len=64)
        if not AUDIO_NAME.fullmatch(f):
            raise BadInput(f"{what}.file must look like recording-N.aac or recording-N.m4a")
        if f in files:
            raise BadInput(f"two recordings use the file {f}")
        files.add(f)
        r["id"], r["file"] = rid, f
        r["createdAt"] = _int(r.get("createdAt"), f"{what}.createdAt", default=0)
        r["durationMs"] = _int(r.get("durationMs"), f"{what}.durationMs", default=0)
        if r.get("transcript") is not None:
            r["transcript"] = validate_transcript(r["transcript"], f"{what}.transcript")
        else:
            r.pop("transcript", None)
        clean_recs.append(r)
    s["recordings"] = clean_recs

    notes = s.get("notes")
    if notes is None:
        notes = []
    if not isinstance(notes, list):
        raise BadInput("session.notes must be a list")
    clean_notes = []
    for i, n in enumerate(notes):
        what = f"notes[{i}]"
        if not isinstance(n, dict):
            raise BadInput(f"{what} must be an object")
        n = dict(n)
        n["id"] = _str(n.get("id"), f"{what}.id", max_len=ITEM_ID_MAX)
        n["text"] = _str(n.get("text"), f"{what}.text", default="")
        n["createdAt"] = _int(n.get("createdAt"), f"{what}.createdAt", default=0)
        if n.get("recId") is not None:
            n["recId"] = _str(n["recId"], f"{what}.recId", max_len=ITEM_ID_MAX)
        else:
            n.pop("recId", None)
        if n.get("offsetMs") is not None:
            n["offsetMs"] = _int(n["offsetMs"], f"{what}.offsetMs")
        else:
            n.pop("offsetMs", None)
        clean_notes.append(n)
    s["notes"] = clean_notes
    return s


def validate_files(obj: Any) -> dict[str, int]:
    """The ``files`` map of a session upload: file name -> size in bytes."""
    if not isinstance(obj, dict):
        raise BadInput("files must be an object mapping file names to sizes")
    out = {}
    for name, size in obj.items():
        if not isinstance(name, str) or len(name) > 64:
            raise BadInput("files keys must be file names")
        out[name] = _int(size, f"files[{name!r}]")
    return out


# ---------------------------------------------------------------------------------------------
# Helpers mirroring the app (Models.kt / Format.kt)


def recording(session: dict, rec_id: str | None) -> dict | None:
    if rec_id is None:
        return None
    return next((r for r in session.get("recordings", []) if r.get("id") == rec_id), None)


def recording_number(session: dict, rec_id: str | None) -> int:
    """1-based position of a recording in the session, or 0 if it isn't there."""
    for i, r in enumerate(session.get("recordings", [])):
        if r.get("id") == rec_id:
            return i + 1
    return 0


def note_is_linked(session: dict, note: dict) -> bool:
    return note.get("offsetMs") is not None and recording(session, note.get("recId")) is not None


def timeline_key(session: dict, note: dict) -> int:
    rec = recording(session, note.get("recId"))
    off = note.get("offsetMs")
    if rec is not None and off is not None:
        return int(rec.get("createdAt", 0)) + int(off)
    return int(note.get("createdAt", 0))


def ordered_notes(session: dict) -> list[dict]:
    """Notes in timeline order: linked notes by the audio moment they point at, others by time written."""
    notes = list(session.get("notes", []))
    return sorted(notes, key=lambda n: (timeline_key(session, n), int(n.get("createdAt", 0))))


def total_duration_ms(session: dict) -> int:
    return sum(int(r.get("durationMs", 0)) for r in session.get("recordings", []))


def format_time(ms: int) -> str:
    """'05:07' under an hour, '1:02:05' from an hour on (like the app)."""
    total = max(int(ms), 0) // 1000
    h, m, s = total // 3600, total % 3600 // 60, total % 60
    return f"{h}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"


def format_date(ms: int) -> str:
    """'Sep 22, 2026 · 3:05 PM' in local time (like the app's formatDate)."""
    try:
        dt = datetime.fromtimestamp(int(ms) / 1000)
    except (OverflowError, OSError, ValueError):
        return "(unknown date)"
    hour = dt.hour % 12 or 12
    return f"{dt.strftime('%b')} {dt.day}, {dt.year} · {hour}:{dt.minute:02d} {'AM' if dt.hour < 12 else 'PM'}"


def time_label(session: dict, rec_id: str | None, offset_ms: int) -> str | None:
    """'03:12', or '#2 03:12' when the session has several recordings; None if the recording is gone."""
    number = recording_number(session, rec_id)
    if number == 0:
        return None
    t = format_time(offset_ms)
    return f"#{number} {t}" if len(session.get("recordings", [])) > 1 else t


def note_label(session: dict, note: dict) -> str | None:
    off = note.get("offsetMs")
    return time_label(session, note.get("recId"), off) if off is not None else None


def folder_name(session: dict) -> str:
    """Backup folder name used by the app: start time (local) and id, e.g. 2026-09-22_15-05_a1b2c3d4e5f6."""
    created = int(session.get("createdAt", 0)) / 1000
    try:
        stamp = datetime.fromtimestamp(created).strftime("%Y-%m-%d_%H-%M")
    except (OverflowError, OSError, ValueError):
        stamp = "1970-01-01_00-00"
    return f"{stamp}_{session['id']}"


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------------------------
# Exports


def _transcript_lines(session: dict) -> Iterable[tuple[dict, dict]]:
    for rec in session.get("recordings", []):
        for seg in rec.get("transcript") or []:
            yield rec, seg


def _model_names_used(session: dict, model_names: dict[str, str] | None) -> list[str]:
    """Names of the models behind the transcripts that are present, for a 'Transcribed with' line."""
    names = model_names or {}
    return sorted({names[r["id"]] for r in session.get("recordings", []) if r.get("transcript") and names.get(r["id"])})


def session_markdown(session: dict, include_transcript: bool = True, model_names: dict[str, str] | None = None) -> str:
    """notes.md, in the same layout the app writes (sessionMarkdown in Format.kt).

    ``model_names`` maps recording id -> name of the model that made its transcript; when given,
    a line under the Transcript heading says which model it was.
    """
    out: list[str] = [f"# {session.get('title', '')}"]
    header = format_date(session.get("createdAt", 0))
    recs = session.get("recordings", [])
    if recs:
        n = len(recs)
        header += f" · {n} recording{'s' if n > 1 else ''} · {format_time(total_duration_ms(session))}"
    out += [header, ""]
    for note in ordered_notes(session):
        label = note_label(session, note)
        text = note.get("text", "")
        photo = note.get("photo")
        if isinstance(photo, str) and PHOTO_NAME.fullmatch(photo):
            # A photo shows as an image (its file is in the same folder), as in the app's notes.md;
            # the caption is kept on one line and its brackets escaped so the link stays intact.
            caption = " ".join(text.split()).replace("[", "\\[").replace("]", "\\]") or "Photo"
            text = f"![{caption}]({photo})"
        out.append(f"- [{label}] {text}" if label else f"- {text}")
    if recs:
        out.append("")
        out.append("Audio: " + ", ".join(f"{r['file']} ({format_time(r.get('durationMs', 0))})" for r in recs))
    if include_transcript and any(r.get("transcript") for r in recs):
        out += ["", "## Transcript", ""]
        names = _model_names_used(session, model_names)
        if names:
            out += [f"_Transcribed with {', '.join(names)}._", ""]
        for rec, seg in _transcript_lines(session):
            out.append(f"- [{time_label(session, rec['id'], seg['s'])}] {seg.get('t', '')}")
    return "\n".join(out) + "\n"


def session_text(session: dict, model_names: dict[str, str] | None = None) -> str:
    """Plain-text export: header, notes and transcript with [time] labels."""
    out: list[str] = [session.get("title", ""), format_date(session.get("createdAt", 0))]
    recs = session.get("recordings", [])
    if recs:
        n = len(recs)
        out[-1] += f" · {n} recording{'s' if n > 1 else ''} · {format_time(total_duration_ms(session))}"
    notes = ordered_notes(session)
    if notes:
        out += ["", "NOTES", ""]
        for note in notes:
            label = note_label(session, note)
            text = note.get("text", "")
            if isinstance(note.get("photo"), str) and note["photo"]:
                text = f"[photo {note['photo']}] {text}".rstrip()
            out.append(f"[{label}] {text}" if label else text)
    if any(r.get("transcript") for r in recs):
        out += ["", "TRANSCRIPT", ""]
        names = _model_names_used(session, model_names)
        if names:
            out += [f"(Transcribed with {', '.join(names)})", ""]
        for rec, seg in _transcript_lines(session):
            out.append(f"[{time_label(session, rec['id'], seg['s'])}] {seg.get('t', '')}")
    return "\n".join(out) + "\n"


def _srt_time(ms: int) -> str:
    ms = max(int(ms), 0)
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def recording_srt(rec: dict) -> str:
    """SubRip subtitles for one recording's transcript (times relative to its audio file)."""
    blocks = []
    for i, seg in enumerate(rec.get("transcript") or [], start=1):
        text = (seg.get("t") or "").strip() or "…"
        blocks.append(f"{i}\n{_srt_time(seg['s'])} --> {_srt_time(max(seg['e'], seg['s'] + 1))}\n{text}\n")
    return "\n".join(blocks)


def safe_filename(title: str, fallback: str = "session") -> str:
    """A file name made from a title, as the app does when sharing."""
    name = re.sub(r"[^\w .-]", "_", title).strip()[:60].strip()
    return name or fallback
