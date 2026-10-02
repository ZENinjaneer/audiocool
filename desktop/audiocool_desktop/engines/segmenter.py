"""Turns timestamped words into transcript lines: sentences or phrases of about 2-20 seconds.

Lines break at sentence ends and long pauses. Sentences longer than ``max_s`` (and long ones
with a clear clause break) are split at the best pause/punctuation; fragments shorter than
``min_s`` are joined to a neighbour when that keeps the line under ``max_s``.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass


@dataclass
class Word:
    text: str  # as displayed, with any attached punctuation
    start: float  # seconds from the start of the recording
    end: float


_SENT_END = re.compile(r"[.!?…。！？]+[\"'”’)\]]*$")
_CLAUSE_END = re.compile(r"[,;:—–]+[\"'”’)\]]*$|\s[-–—]$")
_ABBREV = {"mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "vs.", "e.g.", "i.e.", "jr.", "sr.", "no.", "fig.", "approx.", "cf."}
_CONJUNCTIONS = {"and", "but", "so", "because", "which", "that", "or", "when", "where", "while", "if", "then", "although"}


def _ends_sentence(text: str) -> bool:
    if not _SENT_END.search(text):
        return False
    low = text.lower().strip("\"'”’()[]")
    if low in _ABBREV:
        return False
    # Initials such as "J." or "U.S." rarely end a sentence.
    if re.fullmatch(r"(?:[A-Za-z]\.){1,3}", text.strip("\"'”’()[]")) and len(low) <= 4:
        return False
    return True


_FILLERS = {"uh", "um", "uhm", "umm", "uhh", "er", "erm", "hmm", "mm", "mhm"}
_TRAILING_STOP = re.compile(r"[.!?…]+$")


def drop_fillers(words: list[Word]) -> list[Word]:
    """Removes hesitation sounds ("uh", "um") that some models write out, keeping sentence
    punctuation and capitalisation intact: "Uh, so we start." -> "So we start."."""
    out: list[Word] = []
    capitalize_next = False
    prefix = ""  # an opening quote or bracket that was attached to a removed filler
    for w in words:
        core = w.text.strip(".,!?;:…\"'“”‘’()[]-—–").lower()
        if core in _FILLERS:
            stop = _TRAILING_STOP.search(w.text)
            if stop and out and not re.search(r"[.!?…,;:]$", out[-1].text):
                out[-1].text += stop.group(0)
            first = next((k for k, c in enumerate(w.text) if c.isalpha()), 0)
            prefix += w.text[:first]
            if w.text[first:first + 1].isupper() and (not out or _ends_sentence(out[-1].text)):
                capitalize_next = True
            continue
        if capitalize_next or prefix:
            text = w.text
            i = next((k for k, c in enumerate(text) if c.isalpha()), None)
            if capitalize_next and i is not None:
                text = text[:i] + text[i].upper() + text[i + 1:]
            w = Word(prefix + text, w.start, w.end)
            capitalize_next, prefix = False, ""
        out.append(w)
    return out


def _is_cjk(ch: str) -> bool:
    return unicodedata.east_asian_width(ch) in ("W", "F") and unicodedata.category(ch).startswith("L")


def join_words(words: list[Word]) -> str:
    out = ""
    for w in words:
        t = w.text.strip()
        if not t:
            continue
        if out and not (_is_cjk(out[-1]) and _is_cjk(t[0])):
            out += " "
        out += t
    return out


def _duration(ws: list[Word]) -> float:
    return ws[-1].end - ws[0].start if ws else 0.0


def _split_score(words: list[Word], k: int, min_s: float) -> float:
    """How good a line break before words[k] is."""
    left, right = words[:k], words[k:]
    dl, dr = _duration(left), _duration(right)
    pause = max(0.0, words[k].start - words[k - 1].end)
    score = min(pause, 1.5)
    prev = words[k - 1].text
    if _ends_sentence(prev):
        score += 1.0
    elif _CLAUSE_END.search(prev):
        score += 0.5
    if words[k].text.lower().strip(",.;:!?\"'") in _CONJUNCTIONS:
        score += 0.15
    total = dl + dr
    score -= 0.4 * abs(dl - dr) / max(total, 1e-6)  # prefer balanced halves
    if dl < min_s or dr < min_s:
        score -= 2.0
    return score


def _split_long(words: list[Word], min_s: float, max_s: float, soft_max_s: float) -> list[list[Word]]:
    d = _duration(words)
    if len(words) < 2 or d <= soft_max_s:
        return [words]
    best_k, best = 0, float("-inf")
    for k in range(1, len(words)):
        s = _split_score(words, k, min_s)
        if s > best:
            best_k, best = k, s
    if d <= max_s:
        # Under the hard limit: only split at a clear clause break into reasonable halves.
        left, right = words[:best_k], words[best_k:]
        if best < 0.6 or _duration(left) < 3.0 or _duration(right) < 3.0:
            return [words]
    return _split_long(words[:best_k], min_s, max_s, soft_max_s) + _split_long(words[best_k:], min_s, max_s, soft_max_s)


def _merge_short(pieces: list[list[Word]], min_s: float, max_s: float, max_gap_s: float) -> list[list[Word]]:
    pieces = [p for p in pieces if p]
    changed = True
    while changed:
        changed = False
        for i, p in enumerate(pieces):
            if _duration(p) >= min_s:
                continue
            candidates = []
            if i + 1 < len(pieces):
                gap = pieces[i + 1][0].start - p[-1].end
                if gap < max_gap_s and _duration(p + pieces[i + 1]) <= max_s:
                    candidates.append((gap, i + 1))
            if i > 0:
                gap = p[0].start - pieces[i - 1][-1].end
                if gap < max_gap_s and _duration(pieces[i - 1] + p) <= max_s:
                    candidates.append((gap, i - 1))
            if not candidates:
                continue
            _, j = min(candidates)
            a, b = min(i, j), max(i, j)
            pieces[a:b + 1] = [pieces[a] + pieces[b]]
            changed = True
            break
    return pieces


def build_segments(
    words: list[Word],
    min_s: float = 2.0,
    max_s: float = 20.0,
    soft_max_s: float = 12.0,
    pause_s: float = 1.5,
) -> list[dict]:
    """Groups words into transcript lines ``{"s": ms, "e": ms, "t": text, "w": [ms, ...]}``, where ``w``
    is where each of the line's words (``t`` split at its spaces) starts, from ``s``: the phone lights
    up each word as it's played. It's left out when the words and the text don't line up one to one."""
    words = [w for w in words if w.text.strip()]
    if not words:
        return []
    words.sort(key=lambda w: w.start)
    sentences: list[list[Word]] = []
    cur: list[Word] = []
    for w in words:
        if cur and w.start - cur[-1].end >= pause_s:
            sentences.append(cur)
            cur = []
        cur.append(w)
        if _ends_sentence(w.text):
            sentences.append(cur)
            cur = []
    if cur:
        sentences.append(cur)

    pieces: list[list[Word]] = []
    for sent in sentences:
        pieces.extend(_split_long(sent, min_s, max_s, soft_max_s))
    pieces = _merge_short(pieces, min_s, max_s, pause_s)

    out = []
    last_end = 0
    for p in pieces:
        s = max(int(round(p[0].start * 1000)), 0)
        e = max(int(round(p[-1].end * 1000)), s)
        if out and s < last_end:
            s = min(last_end, e)
        text = join_words(p)
        if text:
            seg = {"s": s, "e": e, "t": text}
            shown = [w for w in p if w.text.strip()]
            if len(shown) == len(text.split(" ")):
                seg["w"] = [min(max(int(round(w.start * 1000)) - s, 0), e - s) for w in shown]
            out.append(seg)
            last_end = e
    return out
