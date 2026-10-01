"""Search across notes and transcripts, matching the app's rules (search/Search.kt).

Every word of the query must appear (case- and accent-insensitive, as substrings). Hits carry the
match positions in the original text for highlighting, and where in the audio they are.
"""

from __future__ import annotations

import unicodedata
from functools import lru_cache
from typing import Iterable

from . import sessionfmt as fmt


@lru_cache(maxsize=4096)
def _fold_char(ch: str) -> str:
    base = "".join(c for c in unicodedata.normalize("NFD", ch) if unicodedata.category(c) != "Mn").lower()
    return base if len(base) == 1 else ch.lower()[:1] or ch


def fold(s: str) -> str:
    """Lowercase without accents, one character per character so positions map back onto ``s``."""
    if s.isascii():
        return s.lower()
    return "".join(_fold_char(ch) if ord(ch) >= 128 else ch.lower() for ch in s)


def search_terms(query: str) -> list[str]:
    seen: list[str] = []
    for term in fold(query).split():
        if term and term not in seen:
            seen.append(term)
    return seen


def find_terms(folded: str, terms: list[str]) -> list[tuple[int, int]] | None:
    """Every occurrence of every term as merged [start, end) ranges, or None if a term is missing."""
    ranges: list[tuple[int, int]] = []
    for term in terms:
        start = folded.find(term)
        if start < 0:
            return None
        while start >= 0:
            ranges.append((start, start + len(term)))
            start = folded.find(term, start + len(term))
    ranges.sort()
    merged: list[tuple[int, int]] = []
    for a, b in ranges:
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def _index(entry) -> list[tuple]:
    """(kind, folded text, text, recId, atMs, noteId, timeline key) for every searchable line, cached per entry."""
    if entry._search is not None:
        return entry._search
    s = entry.session
    items = []
    for note in s.get("notes", []):
        text = note.get("text", "")
        rec = fmt.recording(s, note.get("recId"))
        linked = rec is not None and note.get("offsetMs") is not None
        items.append(("note", fold(text), text, rec["id"] if rec else None, note.get("offsetMs") if linked else None,
                      note.get("id"), fmt.timeline_key(s, note)))
    for rec in s.get("recordings", []):
        for i, seg in enumerate(rec.get("transcript") or []):
            text = seg.get("t", "")
            items.append(("speech", fold(text), text, rec["id"], seg["s"], None, int(rec.get("createdAt", 0)) + seg["s"], i))
    entry._search = items
    return items


def search_entries(entries: Iterable, query: str, limit: int = 500) -> dict:
    terms = search_terms(query)
    if not terms:
        return {"query": query, "hits": [], "total": 0, "truncated": False}
    hits = []
    total = 0
    for entry in sorted(entries, key=lambda e: e.session.get("createdAt", 0), reverse=True):
        found = []
        for item in _index(entry):
            matches = find_terms(item[1], terms)
            if matches is None:
                continue
            found.append(item + (matches,))
        found.sort(key=lambda it: it[6])
        for it in found:
            total += 1
            if len(hits) >= limit:
                continue
            kind, _, text, rec_id, at_ms, note_id, _key = it[:7]
            hit = {
                "sessionId": entry.id,
                "sessionTitle": entry.session.get("title", ""),
                "sessionCreatedAt": entry.session.get("createdAt", 0),
                "kind": kind,
                "text": text,
                "matches": [list(m) for m in it[-1]],
                "recId": rec_id,
                "atMs": at_ms,
                "noteId": note_id,
                "label": fmt.time_label(entry.session, rec_id, at_ms) if at_ms is not None else None,
            }
            if kind == "speech":
                hit["line"] = it[7]
            hits.append(hit)
    return {"query": query, "hits": hits, "total": total, "truncated": total > len(hits)}
