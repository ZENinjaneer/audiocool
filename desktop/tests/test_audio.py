"""Decoding the phone's AAC files (ADTS and MP4), plus the formatting and segmentation helpers."""

from __future__ import annotations

import numpy as np
import pytest

from audiocool_desktop import audio
from audiocool_desktop import sessionfmt as fmt
from audiocool_desktop.engines.segmenter import Word, build_segments
from audiocool_desktop.engines.vad import make_chunks
from conftest import DATA, T0, phone_session, tone


def dominant_hz(x: np.ndarray, sr: int = 16_000) -> float:
    seg = x[sr // 4: sr // 4 + sr]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    return float(np.argmax(spec) * sr / len(seg))


@pytest.mark.parametrize("ext,container", [("aac", "aac"), ("m4a", "mov,mp4,m4a,3gp,3g2,mj2")])
def test_decode_phone_aac(tmp_path, ext, container):
    import av

    path = tmp_path / f"recording-1.{ext}"
    audio.encode_aac(tone(3.0, 523.25), path)  # mono, 44.1 kHz, 96 kbps like the phone
    with av.open(str(path)) as c:
        assert c.format.name == container
        st = c.streams.audio[0]
        assert st.codec_context.name == "aac" and st.sample_rate == 44_100
    x = audio.decode(path)
    assert x.dtype == np.float32 and x.ndim == 1
    assert abs(len(x) / 16_000 - 3.0) < 0.06  # ADTS keeps the encoder's ~23 ms of priming
    assert abs(dominant_hz(x) - 523.25) < 2
    assert 0.18 < float(np.sqrt(np.mean(x[4000:-4000] ** 2))) < 0.24  # 0.3 amplitude sine -> 0.21 RMS
    assert abs(audio.probe_duration_ms(path) - 3000) <= 60


def test_decode_cut_off_adts_recording(tmp_path):
    """A recording interrupted mid-frame (app killed) still decodes up to the damage."""
    path = tmp_path / "recording-1.aac"
    audio.encode_aac(tone(4.0), path)
    data = path.read_bytes()
    cut = tmp_path / "cut.aac"
    cut.write_bytes(data[: len(data) // 2 + 37])
    x = audio.decode(cut)
    assert 1.6 < len(x) / 16_000 < 2.4
    assert abs(dominant_hz(x) - 440) < 2


def test_decode_real_speech_clip():
    x = audio.decode(DATA / "jfk.m4a")
    assert abs(len(x) / 16_000 - 11.0) < 0.05
    assert audio.probe_duration_ms(DATA / "jfk.m4a") == 11000


def test_resample_roundtrip():
    x = tone(1.0, 300, sr=44_100)
    y = audio.resample(x, 44_100, 16_000)
    assert abs(len(y) - 16_000) < 50 and abs(dominant_hz(y) - 300) < 2


# --- session format ------------------------------------------------------------------------


def test_format_helpers_match_the_app():
    assert fmt.format_time(0) == "00:00"
    assert fmt.format_time(307_000) == "05:07"
    assert fmt.format_time(3_725_000) == "1:02:05"
    s = phone_session(recordings=[
        {"id": "r1", "file": "recording-1.m4a", "createdAt": T0, "durationMs": 60_000},
        {"id": "r2", "file": "recording-2.m4a", "createdAt": T0 + 120_000, "durationMs": 30_000},
    ], notes=[
        {"id": "a", "text": "second recording note", "createdAt": T0 + 1, "recId": "r2", "offsetMs": 5000},
        {"id": "b", "text": "unlinked, written first", "createdAt": T0 + 10},
        {"id": "c", "text": "first recording note", "createdAt": T0 + 200_000, "recId": "r1", "offsetMs": 20_000},
    ])
    assert [n["id"] for n in fmt.ordered_notes(s)] == ["b", "c", "a"]
    assert fmt.note_label(s, s["notes"][0]) == "#2 00:05"
    assert fmt.folder_name(s).endswith("_a1b2c3d4e5f6") and len(fmt.folder_name(s)) == len("2026-09-21_14-13_") + 12
    md = fmt.session_markdown(s)
    lines = md.splitlines()
    assert lines[0] == "# Bio 101"
    assert lines[1].endswith(" · 2 recordings · 01:30")
    assert lines[3:6] == ["- unlinked, written first", "- [#1 00:20] first recording note", "- [#2 00:05] second recording note"]
    assert "Audio: recording-1.m4a (01:00), recording-2.m4a (00:30)" in md


def test_validate_session_keeps_unknown_fields_and_defaults():
    s = fmt.validate_session({"id": "abc", "recordings": [{"id": "r1", "file": "recording-1.aac", "future": 1}], "x": {"y": 2}})
    assert s["x"] == {"y": 2} and s["recordings"][0]["future"] == 1
    assert s["title"] == "Untitled" and s["createdAt"] == 0 and s["notes"] == []
    with pytest.raises(fmt.BadInput):
        fmt.validate_session({"id": "abc", "recordings": [{"id": "r1", "file": "recording-1.aac", "durationMs": True}]})


def test_srt():
    rec = {"transcript": [{"s": 0, "e": 1500, "t": "Hello."}, {"s": 3_723_004, "e": 3_725_000, "t": "Late."}]}
    assert fmt.recording_srt(rec) == "1\n00:00:00,000 --> 00:00:01,500\nHello.\n\n2\n01:02:03,004 --> 01:02:05,000\nLate.\n"


# --- segmentation --------------------------------------------------------------------------


def words_from(text: str, start: float = 0.0, rate: float = 0.35, pauses: dict | None = None) -> list[Word]:
    out, t = [], start
    for i, w in enumerate(text.split()):
        t += (pauses or {}).get(i, 0.0)
        out.append(Word(w, t, t + rate * 0.8))
        t += rate
    return out


def test_segments_follow_sentences():
    ws = words_from("This is the first sentence of the lecture today. And here comes a second one that is a little longer.")
    segs = build_segments(ws)
    assert [s["t"] for s in segs] == [
        "This is the first sentence of the lecture today.",
        "And here comes a second one that is a little longer.",
    ]
    assert segs[0]["s"] == 0 and segs[0]["e"] == round(ws[8].end * 1000)
    assert segs[1]["s"] == round(ws[9].start * 1000)


def test_short_sentences_merge_and_long_ones_split():
    segs = build_segments(words_from("Okay. Yes. So let us begin with the cell membrane and its structure."))
    assert segs[0]["t"].startswith("Okay. Yes.")  # under 2 s on their own
    long_text = " ".join(f"word{i}," if i % 9 == 8 else f"word{i}" for i in range(90)) + "."
    segs = build_segments(words_from(long_text))  # one 31 s sentence
    assert len(segs) >= 2
    assert all((s["e"] - s["s"]) <= 20_000 for s in segs)
    assert all((s["e"] - s["s"]) >= 2_000 for s in segs)
    assert " ".join(s["t"] for s in segs) == long_text


def test_long_pause_breaks_a_line():
    segs = build_segments(words_from("we talked about this and then after a long break we went on", pauses={6: 3.0}))
    assert [s["t"] for s in segs] == ["we talked about this and then", "after a long break we went on"]


def test_abbreviations_dont_end_sentences():
    segs = build_segments(words_from("Then Dr. Smith and Mr. Jones arrived in the U.S. late at night for the meeting."))
    assert len(segs) == 1


def test_make_chunks():
    sr = 16_000
    speech = [(0, 5 * sr), (5 * sr + 8000, 12 * sr), (12 * sr + 4000, 31 * sr), (40 * sr, 45 * sr)]
    chunks = make_chunks(speech, max_s=30, max_gap_s=1.5)
    assert chunks == [(0, 12 * sr), (12 * sr + 4000, 31 * sr), (40 * sr, 45 * sr)]
    assert make_chunks([(0, 70 * sr)], max_s=30) == [(0, 30 * sr), (30 * sr, 60 * sr), (60 * sr, 70 * sr)]
