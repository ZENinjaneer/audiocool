"""The speech models on offer, and the one currently loaded.

Engines import torch/transformers only when first used, so the server starts quickly and the
tests can run with a fake engine.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

from .base import Cancelled, Engine, Result, is_gpu_failure  # noqa: F401  (re-exported)

log = logging.getLogger(__name__)


@dataclass
class ModelSpec:
    id: str
    name: str
    description: str
    #: "cuda": runs on the GPU when there is one (else on the CPU); "cpu": always on the CPU.
    device: str
    factory: Callable[[str], Engine]
    repos: tuple[str, ...] = field(default_factory=tuple)


def default_specs(language: str | None = "English") -> list[ModelSpec]:
    from .parakeet import ParakeetEngine
    from .qwen3 import Qwen3Engine

    parakeet = "nvidia/parakeet-tdt-0.6b-v3"
    return [
        ModelSpec(
            id="qwen3-asr-1.7b",
            name="Qwen3-ASR 1.7B",
            description="Most accurate. Qwen3-ASR 1.7B with the Qwen3 forced aligner for word timing.",
            device="cuda",
            factory=lambda dev: Qwen3Engine("qwen3-asr-1.7b", "Qwen3-ASR 1.7B", dev, language=language),
            repos=("Qwen/Qwen3-ASR-1.7B-hf", "Qwen/Qwen3-ForcedAligner-0.6B-hf"),
        ),
        ModelSpec(
            id="parakeet-tdt-0.6b-v3",
            name="Parakeet TDT 0.6B v3",
            description="Fast. NVIDIA Parakeet TDT 0.6B v3, nearly as accurate at several times the speed.",
            device="cuda",
            factory=lambda dev: ParakeetEngine("parakeet-tdt-0.6b-v3", "Parakeet TDT 0.6B v3", dev, repo=parakeet),
            repos=(parakeet,),
        ),
        ModelSpec(
            id="parakeet-tdt-0.6b-v3-cpu",
            name="Parakeet TDT 0.6B v3 (CPU)",
            description="Runs on the processor and leaves the GPU alone. Slower, same accuracy as Parakeet.",
            device="cpu",
            factory=lambda dev: ParakeetEngine("parakeet-tdt-0.6b-v3-cpu", "Parakeet TDT 0.6B v3 (CPU)", "cpu", repo=parakeet),
            repos=(parakeet,),
        ),
    ]


class Registry:
    def __init__(self, specs: list[ModelSpec], default_id: str | None = None, cuda: bool | None = None):
        self.specs = {s.id: s for s in specs}
        self.order = [s.id for s in specs]
        self._cuda = cuda
        self._gpu_name: str | None = None
        self.preferred_default = default_id
        self._engine: Engine | None = None
        self._lock = threading.RLock()
        self._last_used = time.monotonic()

    # -- hardware ---------------------------------------------------------------------------

    @property
    def cuda_available(self) -> bool:
        if self._cuda is None:
            try:
                import torch

                self._cuda = bool(torch.cuda.is_available())
                if self._cuda:
                    self._gpu_name = torch.cuda.get_device_name(0)
            except Exception as e:  # no torch, broken driver...
                log.warning("CUDA not available: %s", e)
                self._cuda = False
        return self._cuda

    @property
    def gpu_name(self) -> str | None:
        return self._gpu_name if self.cuda_available else None

    def device_for(self, spec: ModelSpec) -> str:
        return "cuda" if spec.device == "cuda" and self.cuda_available else "cpu"

    # -- catalogue --------------------------------------------------------------------------

    def get(self, model_id: str | None) -> ModelSpec | None:
        return self.specs.get(model_id) if model_id else None

    @property
    def default_id(self) -> str:
        if self.preferred_default in self.specs:
            return self.preferred_default
        want = "cuda" if self.cuda_available else "cpu"
        for mid in self.order:
            if self.specs[mid].device == want:
                return mid
        return self.order[0]

    def models(self) -> list[dict]:
        """The list the phone sees in /api/v1/ping."""
        default = self.default_id
        return [{"id": mid, "name": self.specs[mid].name, "default": mid == default} for mid in self.order]

    def describe(self) -> list[dict]:
        default = self.default_id
        loaded = self._engine
        out = []
        for mid in self.order:
            spec = self.specs[mid]
            out.append({
                "id": mid,
                "name": spec.name,
                "description": spec.description,
                "default": mid == default,
                "device": self.device_for(spec),
                "loaded": loaded is not None and loaded.id == mid and loaded.loaded,
            })
        return out

    def name_of(self, model_id: str) -> str:
        spec = self.specs.get(model_id)
        return spec.name if spec else model_id

    # -- engines ----------------------------------------------------------------------------

    def engine(self, model_id: str, device: str | None = None) -> Engine:
        """A loaded engine for the model. Only one stays loaded, to keep GPU memory free."""
        with self._lock:
            spec = self.specs[model_id]
            dev = device or self.device_for(spec)
            current = self._engine
            if current is not None and (current.id != model_id or current.device != dev):
                log.info("Unloading %s (%s)", current.name, current.device)
                current.unload()
                self._engine = None
            if self._engine is None:
                self._engine = spec.factory(dev)
            if not self._engine.loaded:
                log.info("Loading %s on %s", spec.name, dev)
                t = time.monotonic()
                self._engine.load()
                log.info("Loaded %s in %.1f s", spec.name, time.monotonic() - t)
            self._last_used = time.monotonic()
            return self._engine

    def touch(self) -> None:
        self._last_used = time.monotonic()

    def unload(self) -> None:
        with self._lock:
            if self._engine is not None:
                log.info("Unloading %s", self._engine.name)
                self._engine.unload()
                self._engine = None

    def unload_if_idle(self, idle_s: float) -> None:
        with self._lock:
            if self._engine is not None and self._engine.loaded and time.monotonic() - self._last_used > idle_s:
                self.unload()
