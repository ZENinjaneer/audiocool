"""End to end with the real default model: the phone's calls, a real speech clip, a real transcript.

The clip is the opening of JFK's inaugural address (public domain), encoded like the phone
records (AAC in MP4, mono, 44.1 kHz, 96 kbps). Uses the GPU when there is one. The first run
downloads the model (several GB) from Hugging Face.
"""

from __future__ import annotations

import re
import time

import pytest

from audiocool_desktop.app import App
from audiocool_desktop.config import Config
from audiocool_desktop.engines import Registry, default_specs
from audiocool_desktop.server import create_app
from conftest import DATA, T0
from fastapi.testclient import TestClient

REFERENCE = "and so my fellow americans ask not what your country can do for you ask what you can do for your country"


def words(text: str) -> list[str]:
    return re.sub(r"[^a-z' ]", " ", text.lower()).split()


def wer(ref: list[str], hyp: list[str]) -> float:
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
    return d[-1] / len(ref)


@pytest.mark.slow
def test_phone_flow_with_real_default_model(tmp_path):
    registry = Registry(default_specs())
    app = App(Config(tmp_path / "home", library=tmp_path / "library"), registry)
    client = TestClient(create_app(app))
    try:
        auth = {"Authorization": f"Bearer {app.config.token}"}
        default = client.get("/api/v1/ping", headers=auth).json()["models"]
        default_id = next(m["id"] for m in default if m["default"])
        assert default_id == ("qwen3-asr-1.7b" if registry.cuda_available else "parakeet-tdt-0.6b-v3-cpu")

        data = (DATA / "jfk.m4a").read_bytes()
        session = {
            "version": 1, "id": "jfk1961intro", "title": "Inaugural address", "createdAt": T0, "updatedAt": T0,
            "recordings": [{"id": "r1", "file": "recording-1.m4a", "createdAt": T0, "durationMs": 11000}],
            "notes": [{"id": "n1", "text": "ask not", "createdAt": T0 + 4000, "recId": "r1", "offsetMs": 4000}],
        }
        r = client.put("/api/v1/sessions/jfk1961intro", headers=auth, json={"session": session, "files": {"recording-1.m4a": len(data)}})
        assert r.json() == {"needed": ["recording-1.m4a"]}
        assert client.put("/api/v1/sessions/jfk1961intro/files/recording-1.m4a", headers=auth, content=data).status_code == 204
        r = client.post("/api/v1/sessions/jfk1961intro/transcribe", headers=auth, json={"model": None, "recordingIds": None})
        assert r.status_code == 202
        (job,) = r.json()["jobs"]
        assert job["model"] == default_id

        deadline = time.monotonic() + 900  # includes loading (and on first run downloading) the model
        while True:
            body = client.get("/api/v1/sessions/jfk1961intro", headers=auth).json()
            status = body["jobs"][0]["status"]
            if status in ("done", "error") or time.monotonic() > deadline:
                break
            time.sleep(0.5)
        assert status == "done", body["jobs"][0]
        assert body["transcriptModels"] == {"r1": default_id}
        segments = body["session"]["recordings"][0]["transcript"]
        text = " ".join(s["t"] for s in segments)
        print(f"\n{default_id}: {segments}")
        assert wer(words(REFERENCE), words(text)) < 0.1, text
        assert text[0].isupper() and text.rstrip()[-1] in ".!?"  # cased and punctuated
        assert 0 <= segments[0]["s"] < 1500 and 9000 < segments[-1]["e"] <= 11000
        assert all(s["s"] <= s["e"] for s in segments)
        listed = client.get("/api/v1/sessions", headers=auth).json()["sessions"][0]["recordings"][0]
        assert listed["transcribed"] is True and listed["model"] == default_id
    finally:
        client.close()
        app.close()
