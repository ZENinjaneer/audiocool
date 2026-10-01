"""Wires the parts together: settings, library, models and the job queue."""

from __future__ import annotations

import logging
from pathlib import Path

from . import audio
from .config import Config
from .engines import Registry, is_gpu_failure
from .jobs import JobQueue
from .library import Library

log = logging.getLogger(__name__)

#: Free GPU memory after this long without a transcription.
UNLOAD_AFTER_S = 600


class App:
    def __init__(self, config: Config, registry: Registry, start_worker: bool = True):
        self.config = config
        self.registry = registry
        if config.default_model and registry.get(config.default_model):
            registry.preferred_default = config.default_model
        self.library = Library(config.library, probe_duration=audio.probe_duration_ms)
        self.jobs = JobQueue(
            config.home / "jobs.db",
            runner=self.run_job,
            idle_callback=lambda: registry.unload_if_idle(UNLOAD_AFTER_S),
        )
        if start_worker:
            self.jobs.start()

    def close(self) -> None:
        self.jobs.close()
        self.registry.unload()

    def set_library(self, path: Path) -> None:
        self.config.update(library=str(path))
        self.library.set_root(self.config.library)

    def run_job(self, job: dict, progress, cancelled) -> dict:
        """Transcribes one recording and stores the result (called on the worker thread)."""
        entry = self.library.get(job["sessionId"])
        if entry is None:
            raise RuntimeError("The session no longer exists")
        rec = entry.recording(job["recordingId"])
        if rec is None:
            raise RuntimeError("The recording no longer exists")
        path = entry.audio_file(rec)
        if path is None:
            raise RuntimeError(f"{rec['file']} hasn't been uploaded yet")
        spec = self.registry.get(job["model"])
        if spec is None:
            raise RuntimeError(f"Unknown model {job['model']}")

        info = None
        engine = None
        try:
            engine = self.registry.engine(spec.id)
            result = engine.transcribe_file(path, progress, cancelled)
        except Exception as e:
            on_gpu = self.registry.device_for(spec) == "cuda"
            if not (on_gpu and is_gpu_failure(e)) or cancelled():
                raise
            log.warning("GPU failed for job %s (%s); retrying on the CPU", job["id"], e)
            self.registry.unload()
            try:
                import torch

                torch.cuda.empty_cache()
            except Exception:
                pass
            info = f"Ran on the CPU because the GPU failed: {str(e).splitlines()[0][:160]}"
            engine = self.registry.engine(spec.id, device="cpu")
            result = engine.transcribe_file(path, progress, cancelled)
        self.registry.touch()
        self.library.set_transcript(
            entry.id,
            rec["id"],
            result.segments,
            model=spec.id,
            model_name=spec.name,
            audioFile=path.name,
            audioDurationMs=result.duration_ms,
            device=result.device,
            timings=result.timings,
        )
        return {"device": result.device, "info": info}
