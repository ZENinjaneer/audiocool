"""Coverage and timing uncertainty must survive successful transcription as explicit metadata."""
from __future__ import annotations

import math
import numpy as np
import pytest

from audiocool_desktop.engines import vad
from audiocool_desktop.engines.base import Engine, SR, coverage_quality
from audiocool_desktop.engines.qwen3 import Qwen3Engine, alignment_problem, attach_times
from audiocool_desktop.engines.segmenter import Word, build_segments


class StubEngine(Engine):
    def __init__(self):
        super().__init__("stub", "Stub", "cpu")
        self.calls = 0
        self.fallback = False

    def recognize(self, chunks, offsets_s, progress, cancelled):
        self.calls += 1
        if self.fallback:
            self.record_alignment_fallback(offsets_s[0], len(chunks[0]) / SR, "alignment_missing")
            return [[Word("Hello.", offsets_s[0], offsets_s[0] + 1)]]
        return [[] for _ in chunks]


def test_silence_does_not_warn_or_force_recognition(monkeypatch):
    monkeypatch.setattr(vad, "speech_segments", lambda samples, **kwargs: [])
    engine = StubEngine()
    result = engine.transcribe_array(np.zeros(32 * SR, np.float32))
    assert result.segments == [] and engine.calls == 0
    assert result.quality["coverage"]["gapCount"] == 1
    assert result.quality["coverage"]["audibleGapCount"] == 0
    assert result.quality["warnings"] == []


def test_actual_detector_settings_are_reported(monkeypatch):
    seen = {}
    def detect(samples, **kwargs):
        seen.update(kwargs)
        return []
    monkeypatch.setattr(vad, "speech_segments", detect)
    engine = StubEngine()
    engine.vad_threshold = 0.17
    result = engine.transcribe_array(np.zeros(SR, np.float32))
    assert seen == {"threshold": 0.17, "max_speech_s": 30.0}
    assert result.quality["detector"]["threshold"] == 0.17
    assert result.quality["detector"]["state"] == "chronological"


def test_alignment_metadata_is_bounded():
    engine = StubEngine()
    for i in range(150):
        engine.record_alignment_fallback(i * 30, 30, "alignment_missing")
    assert engine._alignment_fallback_count == 150
    assert len(engine._alignment_fallbacks) == 100


def test_audible_rejected_audio_is_flagged_without_forcing_asr(monkeypatch):
    monkeypatch.setattr(vad, "speech_segments", lambda samples, **kwargs: [])
    engine = StubEngine()
    result = engine.transcribe_array(np.full(32 * SR, 0.05, np.float32))
    assert engine.calls == 0
    quality = result.quality
    assert quality["gaps"][0]["kind"] == "vad_skipped"
    assert quality["gaps"][0]["audible"] is True
    assert quality["coverage"]["recognitionAudioMs"] == 0
    assert quality["warnings"][0]["code"] == "audible_untranscribed_audio"


def test_admitted_audio_without_text_is_distinguished(monkeypatch):
    monkeypatch.setattr(vad, "speech_segments", lambda samples, **kwargs: [(0, len(samples))])
    result = StubEngine().transcribe_array(np.full(40 * SR, 0.05, np.float32))
    assert result.quality["gaps"][0]["kind"] == "speech_without_text"
    assert result.quality["coverage"]["vadSpeechMs"] == 40_000
    assert result.quality["coverage"]["recognitionAudioMs"] == 40_000


def test_coverage_metadata_is_bounded_and_does_not_mutate_words():
    samples = np.full(SR * 6, 0.05, np.float32)
    words = [Word("a", i * .05, i * .05 + .01) for i in range(120)]
    original = [(w.start, w.end) for w in words]
    quality = coverage_quality(samples, [], [], words, gap_threshold_ms=20)
    assert quality["coverage"]["gapCount"] == 120
    assert quality["coverage"]["gapsTruncated"] is True
    assert len(quality["gaps"]) == 100
    assert [(w.start, w.end) for w in words] == original
    assert coverage_quality(np.zeros(0, np.float32), [], [], [])["warnings"] == []


def test_alignment_failure_is_visible_and_reset_between_recordings(monkeypatch):
    monkeypatch.setattr(vad, "speech_segments", lambda samples, **kwargs: [(0, len(samples))])
    engine = StubEngine()
    engine.fallback = True
    first = engine.transcribe_array(np.ones(SR * 2, np.float32) * .05)
    assert first.quality["alignment"]["fallbackCount"] == 1
    assert first.quality["alignment"]["estimatedTiming"] == [{"s": 0, "e": 2000, "reason": "alignment_missing"}]
    assert first.segments[0]["timingEstimated"] is True
    assert any(w["code"] == "estimated_word_timing" for w in first.quality["warnings"])
    engine.fallback = False
    second = engine.transcribe_array(np.ones(SR * 2, np.float32) * .05)
    assert second.quality["alignment"]["fallbackCount"] == 0
    assert second.quality["alignment"]["estimatedTiming"] == []


@pytest.mark.parametrize("aligned,reason", [
    ([], "alignment_missing"),
    ([{"start_time": 0, "end_time": 0.5}], "token_count_mismatch"),
    ([{"start_time": 0, "end_time": .5}, {"start_time": float("nan"), "end_time": 1}], "invalid_alignment_time"),
    ([{"start_time": .6, "end_time": .8}, {"start_time": .2, "end_time": .4}], "out_of_order_or_out_of_bounds"),
    ([{"start_time": 0, "end_time": .5}, {"start_time": .5, "end_time": 5}], "out_of_order_or_out_of_bounds"),
    ([{"text": "there", "start_time": 0, "end_time": .5}, {"text": "Hello", "start_time": .5, "end_time": 1}], "token_text_mismatch"),
])
def test_invalid_alignment_cannot_masquerade_as_measured_timing(aligned, reason):
    assert alignment_problem("Hello there.", aligned, 2) == reason
    words = attach_times("Hello there.", aligned, 10, 2)
    assert all(math.isfinite(w.start) and 10 <= w.start <= w.end <= 12 for w in words)


def test_qwen_records_real_alignment_exception(monkeypatch):
    """Exercise recognize(), not just the formatter: a failed aligner preserves text and reports it."""
    engine = Qwen3Engine("test", "Test", "cpu")
    monkeypatch.setattr(engine, "load", lambda: None)
    monkeypatch.setattr(engine, "_transcribe_batch", lambda audio: ["Hello there."] * len(audio))
    def fail(*args):
        raise ValueError("aligner failed")
    monkeypatch.setattr(engine, "_align_batch", fail)
    result = engine.recognize([np.zeros(SR * 2, np.float32)], [5.0], lambda *args: None, lambda: False)
    assert [w.text for w in result[0]] == ["Hello", "there."]
    assert engine._alignment_fallback_count == 1
    assert engine._alignment_fallbacks == [{"s": 5000, "e": 7000, "reason": "alignment_missing"}]


def test_missing_alignment_batch_keeps_recognized_text(monkeypatch):
    engine = Qwen3Engine("test", "Test", "cpu")
    monkeypatch.setattr(engine, "load", lambda: None)
    monkeypatch.setattr(engine, "_transcribe_batch", lambda audio: ["Hello there."] * len(audio))
    monkeypatch.setattr(engine, "_align_batch", lambda *args: [])
    result = engine.recognize([np.zeros(SR * 2, np.float32)], [5.0], lambda *args: None, lambda: False)
    assert [w.text for w in result[0]] == ["Hello", "there."]
    assert engine._alignment_fallback_count == 1


def test_word_end_offsets_preserve_silence_between_words():
    segments = build_segments([Word("Hello", 0, .2), Word("there.", .8, 1)])
    assert segments[0]["w"] == [0, 800]
    assert segments[0]["we"] == [200, 1000]
