"""Speech detection with Silero VAD, used to cut long recordings into chunks at pauses.

Silero scores 32 ms windows one after another, carrying state between them. Scoring an hour of
audio that way is ~110k model calls, so the audio is split into a few dozen contiguous streams
that are scored side by side as one batch (each stream keeps its own state; only its first few
windows lack context). The scores then go through Silero's own post-processing.
"""

from __future__ import annotations

import logging
import threading

import numpy as np

log = logging.getLogger(__name__)

SR = 16_000
WINDOW = 512  # samples per Silero window at 16 kHz
_model = None
_lock = threading.Lock()


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


WARMUP = 48  # windows (~1.5 s) each stream runs over the previous stream's audio first


def speech_probs(audio: np.ndarray, streams: int | None = None) -> np.ndarray:
    """Speech probability of each 512-sample window of 16 kHz audio."""
    import torch

    model = _load()
    n = len(audio)
    frames = -(-n // WINDOW)
    if frames == 0:
        return np.zeros(0, np.float32)
    if streams is None:
        # At least ~20 s per stream, at most 64 streams.
        streams = int(max(1, min(64, frames // 625)))
    per = -(-frames // streams)
    warm = WARMUP if streams > 1 else 0
    # Stream k scores windows [k*per, (k+1)*per) after warming its state up on the `warm`
    # windows before them, so its first real windows have context like a sequential pass.
    padded = np.zeros((warm + streams * per) * WINDOW, np.float32)
    padded[warm * WINDOW: warm * WINDOW + n] = audio
    span = (per + warm) * WINDOW
    x = torch.from_numpy(np.stack([padded[k * per * WINDOW: k * per * WINDOW + span] for k in range(streams)]))
    out = torch.empty(streams, per + warm)
    threads = torch.get_num_threads()
    with _lock, torch.inference_mode():
        torch.set_num_threads(min(threads, 4))  # tiny ops: more threads only add overhead and contention
        try:
            model.reset_states()  # the model sizes its state to the batch on the first call
            for i in range(per + warm):
                out[:, i] = model(x[:, i * WINDOW:(i + 1) * WINDOW], SR).reshape(-1)
            model.reset_states()
        finally:
            torch.set_num_threads(threads)
    return out[:, warm:].reshape(-1)[:frames].numpy()


def speech_segments(
    audio: np.ndarray,
    threshold: float = 0.4,
    max_speech_s: float = 30.0,
    min_silence_ms: int = 300,
    min_speech_ms: int = 250,
    pad_ms: int = 150,
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
