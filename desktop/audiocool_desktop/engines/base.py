"""What every speech engine shares: decode, find speech, cut into chunks, recognize, build lines."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from .. import audio as audio_io
from .segmenter import Word, build_segments

log = logging.getLogger(__name__)

SR = audio_io.SAMPLE_RATE
Progress = Callable[[float], None]
IsCancelled = Callable[[], bool]


class Cancelled(Exception):
    """The job was cancelled while running."""


@dataclass
class Result:
    segments: list[dict]
    duration_ms: int
    device: str
    timings: dict = field(default_factory=dict)  # seconds spent per stage
    speech_s: float = 0.0
    chunks: int = 0


def _noop(_: float) -> None:
    pass


def _never() -> bool:
    return False


def quiet_transformers() -> None:
    """No 'Loading weights' progress bars in the server's console."""
    from transformers.utils import logging as hf_logging

    hf_logging.disable_progress_bar()
    hf_logging.set_verbosity_error()


def is_gpu_failure(exc: BaseException) -> bool:
    """CUDA out-of-memory or driver errors, after which running on the CPU is worth a try."""
    name = type(exc).__name__
    text = str(exc)
    return name in ("OutOfMemoryError", "AcceleratorError") or any(
        s in text for s in ("CUDA", "cuDNN", "CUBLAS", "cublas", "out of memory", "NVML", "device-side")
    )


class Engine:
    """A speech recognizer. Subclasses implement load/unload/recognize."""

    #: Longest piece of audio handed to the recognizer at once.
    max_chunk_s = 30.0

    def __init__(self, model_id: str, name: str, device: str):
        self.id = model_id
        self.name = name
        self.device = device

    # -- to implement -----------------------------------------------------------------------

    @property
    def loaded(self) -> bool:
        return True

    def load(self) -> None:
        pass

    def unload(self) -> None:
        pass

    def recognize(self, chunks: list[np.ndarray], offsets_s: list[float], progress: Progress, cancelled: IsCancelled) -> list[list[Word]]:
        """Words (with absolute times) for each chunk of 16 kHz audio starting at ``offsets_s``."""
        raise NotImplementedError

    # -- pipeline ---------------------------------------------------------------------------

    def transcribe_file(self, path: str | Path, progress: Progress = _noop, cancelled: IsCancelled = _never) -> Result:
        t0 = time.monotonic()
        samples = audio_io.decode(path)
        t_decode = time.monotonic() - t0
        progress(0.03)
        result = self.transcribe_array(samples, lambda f: progress(0.03 + 0.97 * f), cancelled)
        result.timings = {"decode": round(t_decode, 3), **result.timings}
        return result

    def transcribe_array(self, samples: np.ndarray, progress: Progress = _noop, cancelled: IsCancelled = _never) -> Result:
        from . import vad

        if cancelled():
            raise Cancelled()
        self.load()
        t0 = time.monotonic()
        speech = vad.speech_segments(samples, max_speech_s=self.max_chunk_s)
        chunks = vad.make_chunks(speech, max_s=self.max_chunk_s)
        t_vad = time.monotonic() - t0
        progress(0.04)
        pieces = [samples[a:b] for a, b in chunks]
        t1 = time.monotonic()
        words_per_chunk = self.recognize(pieces, [a / SR for a, _ in chunks], lambda f: progress(0.04 + 0.95 * f), cancelled) if pieces else []
        t_asr = time.monotonic() - t1
        words = [w for ws in words_per_chunk for w in ws]
        segments = build_segments(words)
        progress(1.0)
        return Result(
            segments=segments,
            duration_ms=int(len(samples) * 1000 // SR),
            device=self.device,
            timings={"vad": round(t_vad, 3), "asr": round(t_asr, 3)},
            speech_s=round(sum(b - a for a, b in chunks) / SR, 2),
            chunks=len(chunks),
        )


def batches_by_length(lengths: list[int], max_items: int, max_total: int) -> list[list[int]]:
    """Groups chunk indices, longest first, so each batch pads little and fits in memory."""
    order = sorted(range(len(lengths)), key=lambda i: -lengths[i])
    out: list[list[int]] = []
    cur: list[int] = []
    for i in order:
        # Every item in a batch is padded to the batch's first (longest) item.
        if cur and (len(cur) >= max_items or lengths[cur[0]] * (len(cur) + 1) > max_total):
            out.append(cur)
            cur = []
        cur.append(i)
    if cur:
        out.append(cur)
    return out
