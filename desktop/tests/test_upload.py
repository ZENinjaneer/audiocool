"""Uploads: which files are needed, validation, renames (.aac -> .m4a), atomic writes, big files."""

from __future__ import annotations

import hashlib
import resource
import socket
import threading
import time

import httpx
import pytest
import uvicorn

from audiocool_desktop.server import create_app
from conftest import T0, aac_bytes, phone_session, wait_for


def test_needed_files(env):
    data = aac_bytes(env.tmp, 2.0)
    s = phone_session(recordings=[
        {"id": "r1", "file": "recording-1.m4a", "createdAt": T0, "durationMs": 2000},
        {"id": "r2", "file": "recording-2.aac", "createdAt": T0 + 5000, "durationMs": 0},  # still recording
    ])
    files = {"recording-1.m4a": len(data), "recording-9.m4a": 10, "notes.txt": 3}  # unreferenced names are ignored
    assert env.put_session(s, files).json() == {"needed": ["recording-1.m4a"]}
    assert env.upload(s["id"], "recording-1.m4a", data).status_code == 204
    assert env.put_session(s, files).json() == {"needed": []}
    # Same name, different size: needed again (e.g. the first upload was of a shorter file).
    assert env.put_session(s, {"recording-1.m4a": len(data) + 1}).json() == {"needed": ["recording-1.m4a"]}
    # Now the phone offers the finished second recording too.
    assert env.put_session(s, {"recording-1.m4a": len(data), "recording-2.aac": 1234}).json() == {"needed": ["recording-2.aac"]}


def test_upload_validation(env):
    env.put_session(phone_session())
    data = aac_bytes(env.tmp, 1.0)
    assert env.upload("a1b2c3d4e5f6", "session.json", b"{}").status_code == 400
    assert env.upload("a1b2c3d4e5f6", "recording-1.mp3", data).status_code == 400
    assert env.upload("a1b2c3d4e5f6", "..%2Fx.m4a", data).status_code in (400, 404)
    r = env.upload("a1b2c3d4e5f6", "recording-2.m4a", data)  # valid name, not in the session
    assert r.status_code == 404 and "not a file of this session" in r.json()["error"]
    assert env.upload("nosuchsession", "recording-1.m4a", data).status_code == 404
    assert not list(env.library.glob("*/recording-2.m4a"))
    assert not list(env.library.glob("*/.*.part"))


def test_upload_replaces_atomically(env):
    env.sync_with_audio()
    folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    new = aac_bytes(env.tmp, 3.0)
    assert env.upload("a1b2c3d4e5f6", "recording-1.m4a", new).status_code == 204
    assert (folder / "recording-1.m4a").read_bytes() == new
    assert not list(folder.glob(".*.part"))


def test_rename_aac_to_m4a_keeps_desktop_transcript(env):
    adts = aac_bytes(env.tmp, 2.0, "aac")
    s = phone_session(recordings=[{"id": "r1", "file": "recording-1.aac", "createdAt": T0, "durationMs": 2000}])
    assert env.put_session(s, {"recording-1.aac": len(adts)}).json() == {"needed": ["recording-1.aac"]}
    env.upload(s["id"], "recording-1.aac", adts)
    env.client.post(f"/api/v1/sessions/{s['id']}/transcribe", headers=env.auth, json={})
    env.wait_jobs()
    folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    # The phone converted it to .m4a without re-encoding.
    m4a = aac_bytes(env.tmp, 2.0, "m4a")
    s["recordings"][0]["file"] = "recording-1.m4a"
    s["updatedAt"] += 1
    assert env.put_session(s, {"recording-1.m4a": len(m4a)}).json() == {"needed": ["recording-1.m4a"]}
    # Until the new file arrives, the old one still plays.
    assert (folder / "recording-1.aac").exists()
    r = env.ui("GET", "/sessions/a1b2c3d4e5f6/audio/r1")
    assert r.status_code == 200 and r.content == adts
    assert env.upload(s["id"], "recording-1.m4a", m4a).status_code == 204
    assert not (folder / "recording-1.aac").exists()
    assert (folder / "recording-1.m4a").read_bytes() == m4a
    body = env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()
    assert body["transcriptModels"] == {"r1": "fake-best"}
    assert body["session"]["recordings"][0]["file"] == "recording-1.m4a"


def test_new_audio_of_different_length_drops_stale_transcript(env):
    env.sync_with_audio(seconds=2.0)
    env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth, json={})
    env.wait_jobs()
    longer = aac_bytes(env.tmp, 6.0)
    env.upload("a1b2c3d4e5f6", "recording-1.m4a", longer)
    body = env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()
    assert body["transcriptModels"] == {}


# --- through a real HTTP server ------------------------------------------------------------


@pytest.fixture
def live(env):
    """Runs the app under uvicorn on a free port (TestClient buffers request bodies)."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(env.app, port=port), host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    assert wait_for(lambda: server.started, 10)
    yield f"http://127.0.0.1:{port}", port
    server.should_exit = True
    thread.join(10)


def test_large_upload_streams_to_disk(env, live):
    """A few hundred MB go straight to disk: the server's memory doesn't grow with the file."""
    base, _ = live
    size = 320 * 1024 * 1024
    s = phone_session()
    env.put_session(s, {"recording-1.m4a": size})
    block = hashlib.sha256(b"seed").digest() * 2048  # 64 KiB
    digest = hashlib.sha256()

    def body():
        sent = 0
        while sent < size:
            chunk = block[: min(len(block), size - sent)]
            digest.update(chunk)
            sent += len(chunk)
            yield chunk

    page = resource.getpagesize()

    def rss() -> int:
        with open("/proc/self/statm") as f:
            return int(f.read().split()[1]) * page

    peak = [rss()]
    baseline = peak[0]
    done = threading.Event()

    def sample():
        while not done.is_set():
            peak[0] = max(peak[0], rss())
            time.sleep(0.02)

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    t = time.monotonic()
    r = httpx.put(f"{base}/api/v1/sessions/{s['id']}/files/recording-1.m4a", headers={**env.auth, "Content-Length": str(size)},
                  content=body(), timeout=120)
    elapsed = time.monotonic() - t
    done.set()
    sampler.join()
    assert r.status_code == 204, r.text
    stored = next(env.library.glob("*_a1b2c3d4e5f6")) / "recording-1.m4a"
    assert stored.stat().st_size == size
    h = hashlib.sha256()
    with open(stored, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    assert h.hexdigest() == digest.hexdigest()
    grew_mb = (peak[0] - baseline) / 2**20
    assert grew_mb < 64, f"memory grew by {grew_mb:.0f} MB for a {size >> 20} MB upload"
    print(f"\n320 MB upload: {elapsed:.1f} s ({size / elapsed / 1e6:.0f} MB/s), peak RSS grew {grew_mb:.1f} MB")
    assert env.put_session(s, {"recording-1.m4a": size}).json() == {"needed": []}


def test_interrupted_upload_leaves_old_file(env, live):
    _, port = live
    env.sync_with_audio()
    folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    before = (folder / "recording-1.m4a").read_bytes()
    with socket.create_connection(("127.0.0.1", port)) as sock:
        head = (f"PUT /api/v1/sessions/a1b2c3d4e5f6/files/recording-1.m4a HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                f"Authorization: Bearer {env.config.token}\r\nContent-Length: 1000000\r\n\r\n")
        sock.sendall(head.encode() + b"x" * 300_000)
        time.sleep(0.3)
    # The connection dropped mid-upload: the old file is untouched and no partial file remains.
    assert wait_for(lambda: not list(folder.glob(".*.part")), 5)
    assert (folder / "recording-1.m4a").read_bytes() == before
