"""What every speech engine shares: decode, find speech, cut into chunks, recognize, build lines."""

from __future__ import annotations

import logging
import math
import heapq
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from .. import audio as audio_io
from .segmenter import Word, build_segments, drop_fillers

log = logging.getLogger(__name__)

SR = audio_io.SAMPLE_RATE
#: progress(fraction 0..1, phase=None): phase is a short label such as "Transcribing".
Progress = Callable[..., None]
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
    quality: dict = field(default_factory=dict)


QUALITY_INTERVAL_LIMIT = 100
GAP_THRESHOLD_MS = 15_000


def _merged_intervals(intervals: list[tuple[int, int]], duration_ms: int) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for start, end in sorted(intervals):
        start, end = max(0, min(start, duration_ms)), max(0, min(end, duration_ms))
        if end < start:
            continue
        if out and start <= out[-1][1]:
            out[-1] = (out[-1][0], max(end, out[-1][1]))
        else:
            out.append((start, end))
    return out


def _signal_levels(samples: np.ndarray) -> tuple[float, float]:
    """RMS/peak dBFS with bounded scratch memory, including for hour-long gaps."""
    energy, peak = 0.0, 0.0
    for start in range(0, len(samples), SR * 2):
        block = samples[start:start + SR * 2].astype(np.float64)
        energy += float(np.dot(block, block))
        if len(block):
            peak = max(peak, float(np.max(np.abs(block))))
    rms = math.sqrt(energy / max(len(samples), 1))
    return round(20 * math.log10(max(rms, 1e-8)), 2), round(20 * math.log10(max(peak, 1e-8)), 2)


def coverage_quality(samples: np.ndarray, speech: list[tuple[int, int]], chunks: list[tuple[int, int]],
                     words: list[Word], gap_threshold_ms: int = GAP_THRESHOLD_MS) -> dict:
    """Report untranscribed intervals; signal energy is not proof that they contain speech.

    Only diagnostics are produced. Never force recognition of a rejected interval here: speech
    recognizers can invent text over noise or silence. Keep the largest 100 intervals and retain
    total counts so long recordings cannot create unbounded metadata.
    """
    duration = len(samples) * 1000 // SR
    spoken = _merged_intervals([(round(w.start * 1000), round(w.end * 1000)) for w in words
                               if math.isfinite(w.start) and math.isfinite(w.end)], duration)
    admitted = _merged_intervals([(a * 1000 // SR, b * 1000 // SR) for a, b in speech], duration)
    heap: list[tuple[int, int, dict]] = []
    previous, total, audible_count, longest = 0, 0, 0, 0
    for start, end in spoken + [(duration, duration)]:
        gap = start - previous
        longest = max(longest, gap)
        if gap >= gap_threshold_ms:
            rms, peak = _signal_levels(samples[previous * SR // 1000:start * SR // 1000])
            audible = rms >= -60.0 and peak >= -45.0
            overlap = sum(max(0, min(start, b) - max(previous, a)) for a, b in admitted)
            kind = "speech_without_text" if overlap >= min(1000, gap // 10) else "vad_skipped"
            record = {"s": previous, "e": start, "kind": kind, "rmsDbfs": rms,
                      "peakDbfs": peak, "audible": audible, "vadSpeechMs": overlap}
            total += 1
            audible_count += int(audible)
            candidate = (gap, previous, record)
            if len(heap) < QUALITY_INTERVAL_LIMIT:
                heapq.heappush(heap, candidate)
            elif candidate[:2] > heap[0][:2]:
                heapq.heapreplace(heap, candidate)
        previous = max(previous, end)
    warnings = []
    if audible_count:
        warnings.append({"code": "audible_untranscribed_audio",
            "message": f"{audible_count} interval(s) contain audible signal without transcript text. They may contain quiet speech or noise; review the audio."})
    return {"version": 1,
        "coverage": {"audioMs": duration, "vadSpeechMs": sum(b - a for a, b in admitted),
            "recognitionAudioMs": sum(b - a for a, b in chunks) * 1000 // SR,
            "longestGapMs": longest, "gapCount": total, "audibleGapCount": audible_count,
            "gapThresholdMs": gap_threshold_ms, "gapsTruncated": total > QUALITY_INTERVAL_LIMIT},
        "gaps": [entry[2] for entry in sorted(heap, key=lambda entry: entry[1])],
        "alignment": {"fallbackCount": 0, "estimatedTiming": [], "truncated": False},
        "warnings": warnings}


def _noop(fraction: float, phase: str | None = None) -> None:
    pass


def _scaled(progress: Progress, start: float, span: float) -> Progress:
    """Maps a sub-step's 0..1 progress onto [start, start + span] of the whole."""
    return lambda f, phase=None: progress(start + span * f, phase)


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
    #: Prefer speech recall; remains configurable for recordings with persistent background noise.
    #: Distant/muffled speech can still be rejected, hence independent coverage diagnostics below.
    vad_threshold = 0.1

    def __init__(self, model_id: str, name: str, device: str):
        self.id = model_id
        self.name = name
        self.device = device
        self._alignment_fallback_count = 0
        self._alignment_fallbacks: list[dict] = []

    def record_alignment_fallback(self, start_s: float, duration_s: float, reason: str) -> None:
        self._alignment_fallback_count += 1
        if len(self._alignment_fallbacks) < QUALITY_INTERVAL_LIMIT:
            self._alignment_fallbacks.append({"s": round(start_s * 1000),
                "e": round((start_s + duration_s) * 1000), "reason": reason})

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
        progress(0.0, "Reading the audio")
        samples = audio_io.decode(path)
        t_decode = time.monotonic() - t0
        progress(0.03)
        result = self.transcribe_array(samples, _scaled(progress, 0.03, 0.97), cancelled)
        result.timings = {"decode": round(t_decode, 3), **result.timings}
        return result

    def transcribe_array(self, samples: np.ndarray, progress: Progress = _noop, cancelled: IsCancelled = _never) -> Result:
        from . import vad

        if cancelled():
            raise Cancelled()
        self.load()
        t0 = time.monotonic()
        progress(0.0, "Finding speech")
        speech = vad.speech_segments(samples, threshold=self.vad_threshold, max_speech_s=self.max_chunk_s)
        chunks = vad.make_chunks(speech, max_s=self.max_chunk_s)
        t_vad = time.monotonic() - t0
        progress(0.04, "Transcribing")
        pieces = [samples[a:b] for a, b in chunks]
        t1 = time.monotonic()
        self._alignment_fallback_count = 0
        self._alignment_fallbacks = []
        words_per_chunk = self.recognize(pieces, [a / SR for a, _ in chunks], _scaled(progress, 0.04, 0.95), cancelled) if pieces else []
        t_asr = time.monotonic() - t1
        raw_words = [w for ws in words_per_chunk for w in ws]
        words = drop_fillers(raw_words)
        segments = build_segments(words)
        quality = coverage_quality(samples, speech, chunks, raw_words)
        quality["detector"] = vad.settings(self.vad_threshold, self.max_chunk_s)
        quality["alignment"] = {"fallbackCount": self._alignment_fallback_count,
            "estimatedTiming": sorted(self._alignment_fallbacks, key=lambda row: row["s"]),
            "truncated": self._alignment_fallback_count > len(self._alignment_fallbacks)}
        if self._alignment_fallback_count:
            quality["warnings"].append({"code": "estimated_word_timing",
                "message": f"Word timing was estimated in {self._alignment_fallback_count} audio chunk(s) because forced alignment was unavailable or invalid."})
            for segment in segments:
                if any(segment["s"] < a["e"] and segment["e"] > a["s"] for a in self._alignment_fallbacks):
                    segment["timingEstimated"] = True
        progress(1.0)
        return Result(
            segments=segments,
            duration_ms=int(len(samples) * 1000 // SR),
            device=self.device,
            timings={"vad": round(t_vad, 3), "asr": round(t_asr, 3)},
            speech_s=round(sum(b - a for a, b in chunks) / SR, 2),
            chunks=len(chunks),
            quality=quality,
        )


def is_oom(exc: BaseException) -> bool:
    return type(exc).__name__ == "OutOfMemoryError" or "out of memory" in str(exc).lower()


class OomSplitter:
    """Runs batches, halving the batch size whenever the GPU runs out of memory (e.g. because
    another program is using it), and keeps the smaller size for the rest of the job."""

    def __init__(self) -> None:
        self.cap: int | None = None

    def run(self, batch: list, fn: Callable[[list], list]) -> list:
        out: list = []
        i = 0
        while i < len(batch):
            size = len(batch) - i if self.cap is None else min(self.cap, len(batch) - i)
            sub = batch[i:i + size]
            try:
                out.extend(fn(sub))
                i += size
            except Exception as e:
                if not is_oom(e) or size == 1:
                    raise
                try:
                    import torch

                    torch.cuda.empty_cache()
                except Exception:
                    pass
                self.cap = max(1, size // 2)
                log.warning("Out of GPU memory with %d chunks at once; retrying with %d", size, self.cap)
        return out


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
