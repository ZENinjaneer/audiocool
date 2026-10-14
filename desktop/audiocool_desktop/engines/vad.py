"""Speech detection with Silero VAD, preserving recurrent state across the recording.

Independent parallel streams are not equivalent to a chronological pass: a short audio warmup
does not reconstruct the LSTM's hidden/cell state. The resulting probability changes can discard
clear speech far beyond the split boundary. Process frames in order and reset only between files.
"""

from __future__ import annotations

import logging
import threading
from importlib.metadata import PackageNotFoundError, version

import numpy as np

log = logging.getLogger(__name__)

SR = 16_000
WINDOW = 512  # samples per Silero window at 16 kHz
DEFAULT_THRESHOLD = 0.4
MIN_SILENCE_MS = 300
MIN_SPEECH_MS = 250
PAD_MS = 150
_model = None
_lock = threading.Lock()


def settings(threshold: float, max_speech_s: float) -> dict:
    """Record the detector and actual settings with the resulting transcript."""
    try:
        package_version = version("silero-vad")
    except PackageNotFoundError:
        package_version = "unknown"
    return {"name": "silero-vad", "packageVersion": package_version, "backend": "torchscript",
        "state": "chronological", "sampleRate": SR, "windowSamples": WINDOW,
        "threshold": threshold, "maxSpeechSeconds": max_speech_s,
        "minSilenceMs": MIN_SILENCE_MS, "minSpeechMs": MIN_SPEECH_MS, "padMs": PAD_MS}


def _silero():
    """Imports silero_vad without its side effect: it calls torch.set_num_threads(1) for the whole
    process on import, which would make every CPU model here run ~8x slower."""
    import torch

    threads = torch.get_num_threads()
    import silero_vad
    import silero_vad.utils_vad

    if torch.get_num_threads() != threads:
        torch.set_num_threads(threads)
    return silero_vad


def _load():
    global _model
    with _lock:
        if _model is None:
            _model = _silero().load_silero_vad(onnx=False)
        return _model


def speech_probs(audio: np.ndarray, streams: int | None = None) -> np.ndarray:
    """Speech probability for each 512-sample window of 16 kHz audio.

    ``streams`` is retained for callers of the old API, but no longer partitions recurrent
    history. All values produce the same chronological inference. Working memory, other than
    the returned probabilities, is bounded to one frame; even the last partial frame is scored.
    """
    import torch

    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim != 1:
        raise ValueError("VAD audio must be one-dimensional mono samples")
    n = len(audio)
    frames = -(-n // WINDOW)
    if frames == 0:
        return np.zeros(0, np.float32)
    model = _load()
    out = np.empty(frames, dtype=np.float32)
    with _lock, torch.inference_mode():
        threads = torch.get_num_threads()
        torch.set_num_threads(min(threads, 4))
        try:
            model.reset_states()
            for i in range(frames):
                chunk = audio[i * WINDOW:(i + 1) * WINDOW]
                if len(chunk) < WINDOW:
                    chunk = np.pad(chunk, (0, WINDOW - len(chunk)))
                frame = torch.from_numpy(np.ascontiguousarray(chunk)).unsqueeze(0)
                out[i] = float(model(frame, SR).reshape(-1)[0])
        finally:
            try:
                model.reset_states()  # also clear state after an interrupted or failed pass
            finally:
                torch.set_num_threads(threads)
    return out


def speech_segments(
    audio: np.ndarray,
    threshold: float = DEFAULT_THRESHOLD,
    max_speech_s: float = 30.0,
    min_silence_ms: int = MIN_SILENCE_MS,
    min_speech_ms: int = MIN_SPEECH_MS,
    pad_ms: int = PAD_MS,
) -> list[tuple[int, int]]:
    """Stretches of speech as (start, end) sample positions in 16 kHz audio."""
    get_speech_timestamps_from_probs = _silero().utils_vad.get_speech_timestamps_from_probs
    probs = speech_probs(audio)
    if len(probs) == 0:
        return []
    ts = get_speech_timestamps_from_probs(
        probs.tolist(),
        sampling_rate=SR,
        threshold=threshold,
        min_speech_duration_ms=min_speech_ms,
        max_speech_duration_s=max_speech_s,
        min_silence_duration_ms=min_silence_ms,
        speech_pad_ms=pad_ms,
        audio_length_samples=len(audio),
    )
    return [(int(t["start"]), int(t["end"])) for t in ts if t["end"] > t["start"]]


def make_chunks(
    speech: list[tuple[int, int]],
    max_s: float = 30.0,
    max_gap_s: float = 1.5,
    sr: int = SR,
) -> list[tuple[int, int]]:
    """Groups speech stretches into chunks of at most ``max_s`` seconds for the recognizer.

    A chunk is one contiguous span of audio (short pauses inside it are kept, which helps the
    model); a pause longer than ``max_gap_s`` always starts a new chunk.
    """
    max_len, max_gap = int(max_s * sr), int(max_gap_s * sr)
    chunks: list[list[int]] = []
    for a, b in speech:
        if chunks and a - chunks[-1][1] <= max_gap and b - chunks[-1][0] <= max_len:
            chunks[-1][1] = b
        else:
            chunks.append([a, b])
    out: list[tuple[int, int]] = []
    for a, b in chunks:
        while b - a > max_len:
            out.append((a, a + max_len))
            a += max_len
        if b > a:
            out.append((a, b))
    return out
