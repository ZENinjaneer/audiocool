from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.environ["AUDIOCOOL_LAN_IPS"] = "192.168.1.20"

from fastapi.testclient import TestClient  # noqa: E402

from audiocool_desktop import audio  # noqa: E402
from audiocool_desktop.app import App  # noqa: E402
from audiocool_desktop.config import Config  # noqa: E402
from audiocool_desktop.engines import ModelSpec, Registry  # noqa: E402
from audiocool_desktop.engines.fake import FakeEngine  # noqa: E402
from audiocool_desktop.server import create_app  # noqa: E402
from audiocool_desktop.summaries import Summaries  # noqa: E402

DATA = Path(__file__).parent / "data"
T0 = 1790000000000


def tone(seconds: float, freq: float = 440.0, sr: int = 44_100) -> np.ndarray:
    t = np.arange(int(sr * seconds)) / sr
    return (0.3 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


_AUDIO_CACHE: dict[tuple, bytes] = {}


def aac_bytes(tmp_path: Path, seconds: float = 2.0, ext: str = "m4a", freq: float = 440.0) -> bytes:
    """A small phone-like AAC file (.aac = ADTS, .m4a = MP4)."""
    key = (seconds, ext, freq)
    if key not in _AUDIO_CACHE:
        path = tmp_path / f"tone-{seconds}-{freq}.{ext}"
        audio.encode_aac(tone(seconds, freq), path)
        _AUDIO_CACHE[key] = path.read_bytes()
    return _AUDIO_CACHE[key]


def phone_session(sid: str = "a1b2c3d4e5f6", title: str = "Bio 101", recordings=None, notes=None, updated: int = T0) -> dict:
    if recordings is None:
        recordings = [{"id": "r1", "file": "recording-1.m4a", "createdAt": T0, "durationMs": 2000}]
    if notes is None:
        notes = [
            {"id": "n1", "text": "Key point about mitochondria", "createdAt": T0 + 1500, "recId": "r1", "offsetMs": 1200},
            {"id": "n2", "text": "unlinked note", "createdAt": T0 + 5000},
        ]
    return {"version": 1, "id": sid, "title": title, "createdAt": T0, "updatedAt": updated, "recordings": recordings, "notes": notes}


class Env:
    """An app with fake engines, a client, and helpers to act like the phone."""

    def __init__(self, tmp_path: Path, engines: list[FakeEngine] | None = None, start_worker: bool = True, cuda: bool = False):
        self.tmp = tmp_path
        self.home = tmp_path / "home"
        self.library = tmp_path / "library"
        self.engines = engines or [FakeEngine("fake-best", "Fake best"), FakeEngine("fake-fast", "Fake fast")]
        self.start_worker = start_worker
        self.cuda = cuda
        self.open()

    def open(self) -> None:
        self.config = Config(self.home, library=self.library)
        specs = [ModelSpec(e.id, e.name, f"{e.name} for tests", "cuda", (lambda dev, e=e: e)) for e in self.engines]
        self.registry = Registry(specs, cuda=self.cuda)
        # Summaries with a stand-in for llama.cpp's server (see test_summaries.py), not the real model.
        from test_summaries import fake_launcher

        summaries = Summaries(self.tmp / "summaries", launcher=fake_launcher(self.tmp))
        self.app = App(self.config, self.registry, start_worker=self.start_worker, summaries=summaries)
        self.client = TestClient(create_app(self.app))
        self.remote = TestClient(create_app(self.app), client=("192.168.1.77", 40000))

    def close(self) -> None:
        self.client.close()
        self.remote.close()
        self.app.close()

    @property
    def auth(self) -> dict:
        return {"Authorization": f"Bearer {self.config.token}"}

    def put_session(self, session: dict, files: dict | None = None):
        if files is None:
            files = {}
        return self.client.put(f"/api/v1/sessions/{session['id']}", headers=self.auth, json={"session": session, "files": files})

    def upload(self, sid: str, name: str, data: bytes):
        return self.client.put(f"/api/v1/sessions/{sid}/files/{name}", headers=self.auth, content=data)

    def ui(self, method: str, path: str, **kw):
        headers = kw.pop("headers", {})
        if method != "GET":
            headers["X-AudioCool"] = "1"
        return self.client.request(method, f"/ui/api{path}", headers=headers, **kw)

    def wait_jobs(self, timeout: float = 20.0) -> None:
        assert self.app.jobs.wait_idle(timeout), "jobs didn't finish"

    def sync_with_audio(self, sid: str = "a1b2c3d4e5f6", seconds: float = 2.0, **kw) -> dict:
        """PUT a session and upload its audio, like the phone does."""
        data = aac_bytes(self.tmp, seconds)
        s = phone_session(sid, **kw)
        r = self.put_session(s, {"recording-1.m4a": len(data)})
        assert r.status_code == 200, r.text
        assert r.json() == {"needed": ["recording-1.m4a"]}
        r = self.upload(sid, "recording-1.m4a", data)
        assert r.status_code == 204, r.text
        return s


@pytest.fixture
def env(tmp_path):
    e = Env(tmp_path)
    yield e
    e.close()


def wait_for(cond, timeout: float = 10.0, step: float = 0.02):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if cond():
            return True
        time.sleep(step)
    return False
