"""A stand-in engine for tests: no models, deterministic output, optional delay or failure."""

from __future__ import annotations

import threading
import time
from pathlib import Path

from .. import audio as audio_io
from .base import Cancelled, Engine, IsCancelled, Progress, Result


class FakeEngine(Engine):
    def __init__(self, model_id: str = "fake", name: str = "Fake model", device: str = "cpu",
                 delay_s: float = 0.0, fail: str | None = None, steps: int = 4):
        super().__init__(model_id, name, device)
        self.delay_s = delay_s
        self.fail = fail
        self.steps = steps
        self.calls: list[str] = []
        self.gate: threading.Event | None = None  # when set by a test, each run waits for it

    def transcribe_file(self, path: str | Path, progress: Progress = lambda f, phase=None: None, cancelled: IsCancelled = lambda: False) -> Result:
        self.calls.append(str(path))
        duration_ms = audio_io.probe_duration_ms(path)
        if self.gate is not None:
            self.gate.wait(10)
        for i in range(self.steps):
            if cancelled():
                raise Cancelled()
            time.sleep(self.delay_s / max(self.steps, 1))
            progress((i + 1) / self.steps)
        if self.fail:
            raise RuntimeError(self.fail)
        half = max(duration_ms // 2, 1)
        segments = [
            {"s": 0, "e": half, "t": f"First half by {self.id}."},
            {"s": half, "e": max(duration_ms, half + 1), "t": f"Second half by {self.id}."},
        ]
        return Result(segments=segments, duration_ms=duration_ms, device=self.device)
