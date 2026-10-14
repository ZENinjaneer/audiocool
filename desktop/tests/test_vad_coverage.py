"""Regression: changing batching must never reset a recording's recurrent VAD history."""
from __future__ import annotations

import numpy as np
import pytest

from audiocool_desktop.engines import vad


class StatefulDetector:
    def __init__(self, fail_at=None):
        self.state = None
        self.calls = 0
        self.fail_at = fail_at

    def reset_states(self):
        self.state = None

    def __call__(self, frame, sample_rate):
        import torch
        self.calls += 1
        if self.calls == self.fail_at:
            raise RuntimeError("detector failure")
        if self.state is None:
            self.state = torch.zeros(frame.shape[0])
        self.state = self.state * 0.995 + frame.mean(dim=1) * 0.005
        return self.state.unsqueeze(1)


def test_partition_hint_does_not_change_recurrent_history(monkeypatch):
    pytest.importorskip("torch")
    model = StatefulDetector()
    monkeypatch.setattr(vad, "_load", lambda: model)
    samples = np.repeat(np.linspace(0.1, 0.9, 2100, dtype=np.float32), vad.WINDOW)
    chronological = vad.speech_probs(samples, streams=1)
    parallel_hint = vad.speech_probs(samples, streams=4)
    np.testing.assert_array_equal(parallel_hint, chronological)
    assert chronological[-1] > 0.7
    assert model.state is None  # no state leaks into the next recording


def test_partial_final_frame_and_empty_input(monkeypatch):
    pytest.importorskip("torch")
    model = StatefulDetector()
    monkeypatch.setattr(vad, "_load", lambda: model)
    samples = np.ones(vad.WINDOW + 1, dtype=np.float32)
    probabilities = vad.speech_probs(samples)
    assert probabilities.shape == (2,)
    assert probabilities[0] == pytest.approx(0.005)
    assert probabilities[1] == pytest.approx(0.005 * 0.995 + 0.005 / vad.WINDOW)
    assert vad.speech_probs(np.zeros(0, np.float32)).size == 0


def test_detector_state_and_thread_count_are_restored_after_failure(monkeypatch):
    torch = pytest.importorskip("torch")
    model = StatefulDetector(fail_at=2)
    monkeypatch.setattr(vad, "_load", lambda: model)
    before = torch.get_num_threads()
    with pytest.raises(RuntimeError, match="detector failure"):
        vad.speech_probs(np.ones(vad.WINDOW * 4, np.float32))
    assert model.state is None
    assert torch.get_num_threads() == before


def test_silero_matches_its_chronological_reference(monkeypatch):
    """Uses the bundled public-domain speech fixture; no model download or GPU required."""
    torch = pytest.importorskip("torch")
    silero = pytest.importorskip("silero_vad")
    from audiocool_desktop import audio
    from conftest import DATA

    clip = audio.decode(DATA / "jfk.m4a")
    samples = np.concatenate([clip, np.zeros(16000, np.float32), clip, clip])
    model = silero.load_silero_vad(onnx=False)
    monkeypatch.setattr(vad, "_load", lambda: model)
    with torch.inference_mode():
        expected = model.audio_forward(torch.from_numpy(samples).unsqueeze(0), vad.SR).reshape(-1).numpy()
    actual = vad.speech_probs(samples, streams=4)
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    assert np.count_nonzero(actual > 0.4) > 500
