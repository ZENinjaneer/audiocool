"""Summaries for the phone: the model's download, its server's life, and the phone's API."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from audiocool_desktop import summaries as sm
from audiocool_desktop.summaries import Download, Summaries, fetch

FAKE = Path(__file__).parent / "fake_llama.py"


def fake_launcher(tmp: Path, mode: str = "ok"):
    def launch(model: Path, port: int, logfile: Path) -> subprocess.Popen:
        out = open(logfile, "ab")
        try:
            # The model's path on its command line, as llama-server's has it.
            return subprocess.Popen([sys.executable, str(FAKE), str(port), mode, str(tmp / "requests.jsonl"), str(model)], stdout=out, stderr=subprocess.STDOUT)
        finally:
            out.close()

    return launch


def make_ready(s: Summaries) -> None:
    """The files in place, as after a download (the model as an empty file of the right size)."""
    s.server_path.parent.mkdir(parents=True, exist_ok=True)
    s.server_path.write_text("")
    with open(s.model_path, "wb") as f:
        f.truncate(sm.MODEL.size)


def requests(tmp: Path) -> list[dict]:
    p = tmp / "requests.jsonl"
    return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []


def test_a_reply_starts_the_server_once_and_it_stops_when_idle(tmp_path):
    s = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path), idle_s=1.0)
    make_ready(s)
    try:
        assert s.status()["state"] == "ready"
        assert not s.status()["running"]
        assert s.reply("Summarize this part of the talk: sleep comes in cycles.", 140) == "Reply to: Summarize this part of the tal"
        first = s._proc
        assert s.status()["running"]
        assert s.reply("Another prompt", 50) == "Reply to: Another prompt"
        assert s._proc is first  # still the same server
        sent = requests(tmp_path)
        assert [r["max_tokens"] for r in sent] == [140, 50]
        assert sent[0]["messages"] == [{"role": "user", "content": "Summarize this part of the talk: sleep comes in cycles."}]
        assert sent[0]["temperature"] == 0.3
        # Unused for a while, it's stopped (giving the GPU memory back), and started again when needed.
        deadline = time.monotonic() + 10
        while s.status()["running"] and time.monotonic() < deadline:
            time.sleep(0.1)
        assert not s.status()["running"]
        assert first.poll() is not None
        assert s.reply("After a rest", 10) == "Reply to: After a rest"
    finally:
        s.close()
    assert s._proc is None


def test_a_server_that_wont_start_says_why(tmp_path):
    s = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path, mode="die"))
    make_ready(s)
    with pytest.raises(RuntimeError, match="out of memory"):
        s.reply("Hello", 10)
    s.close()


def test_without_the_model_there_is_no_reply(tmp_path):
    s = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path))
    assert s.status()["state"] == "missing"
    with pytest.raises(RuntimeError, match="isn't downloaded"):
        s.reply("Hello", 10)


def test_release_stops_an_idle_server(tmp_path):
    s = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path))
    make_ready(s)
    s.reply("Hello", 10)
    assert s.status()["running"]
    s.release()
    assert not s.status()["running"]
    s.close()


# ---- Downloading ----


class Files(BaseHTTPRequestHandler):
    """Serves byte ranges of DATA; HEAD answers too, as on Hugging Face after its redirect."""

    data = b""
    gets = 0
    fail_after: int | None = None  # bytes into the file at which one request breaks off

    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(self.data)))
        self.end_headers()

    def do_GET(self):
        type(self).gets += 1
        start, end = map(int, re.match(r"bytes=(\d+)-(\d+)", self.headers["Range"]).groups())
        part = self.data[start : end + 1]
        self.send_response(206)
        self.send_header("Content-Length", str(len(part)))
        self.send_header("Content-Range", f"bytes {start}-{end}/{len(self.data)}")
        self.end_headers()
        cut = type(self).fail_after
        if cut is not None and start <= cut <= end:
            type(self).fail_after = None
            self.wfile.write(part[: cut - start])
            self.wfile.flush()
            self.connection.close()
            return
        self.wfile.write(part)


@pytest.fixture
def files():
    Files.data = bytes(range(256)) * 4096 + b"tail"  # a bit over 1 MB
    Files.gets = 0
    Files.fail_after = None
    server = ThreadingHTTPServer(("127.0.0.1", 0), Files)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}/model.gguf"
    server.shutdown()


def item(url: str, sha: str | None = None) -> Download:
    return Download(url, len(Files.data), sha or hashlib.sha256(Files.data).hexdigest(), "model.gguf")


def test_a_download_comes_in_parallel_pieces_and_is_checked(tmp_path, files, monkeypatch):
    monkeypatch.setattr(sm.time, "sleep", lambda s: None)
    got = []
    dest = tmp_path / "model.gguf"
    Files.fail_after = 300_000  # one piece breaks off and is fetched again
    fetch(item(files), dest, lambda: False, got.append, workers=4, chunk=128 << 10)
    assert dest.read_bytes() == Files.data
    assert sum(got) == len(Files.data)
    assert Files.gets == 10  # nine pieces, one twice
    assert not (tmp_path / "model.gguf.part").exists() and not (tmp_path / "model.gguf.chunks").exists()


def test_a_download_carries_on_where_it_stopped(tmp_path, files):
    dest = tmp_path / "model.gguf"
    stop = threading.Event()
    seen = []

    def count(n):
        seen.append(n)
        if sum(seen) > 400_000:
            stop.set()

    with pytest.raises(sm.Cancelled):
        fetch(item(files), dest, stop.is_set, count, workers=1, chunk=128 << 10)
    done_before = len((tmp_path / "model.gguf.chunks").read_text().split())
    assert done_before >= 3
    Files.gets = 0
    got = []
    fetch(item(files), dest, lambda: False, got.append, workers=2, chunk=128 << 10)
    assert dest.read_bytes() == Files.data
    assert Files.gets == 9 - done_before  # only what was missing
    assert got[0] == done_before * (128 << 10)  # counted as done from the start


def test_a_damaged_download_is_thrown_away(tmp_path, files):
    dest = tmp_path / "model.gguf"
    with pytest.raises(OSError, match="damaged"):
        fetch(item(files, sha="0" * 64), dest, lambda: False, lambda n: None, workers=2, chunk=128 << 10)
    assert not dest.exists() and not (tmp_path / "model.gguf.part").exists()


# ---- The phone's API ----


def test_ping_says_whether_the_desktop_can_summarize(env):
    r = env.remote.get("/api/v1/ping", headers=env.auth)
    assert r.json()["summaries"] is None
    make_ready(env.app.summaries)
    r = env.remote.get("/api/v1/ping", headers=env.auth)
    assert r.json()["summaries"] == {"id": "gemma-4-26b-a4b", "name": "Gemma 4 26B"}


def test_the_phone_gets_replies_to_its_prompts(env):
    r = env.remote.post("/api/v1/reply", json={"prompt": "Summarize: sleep", "maxTokens": 140}, headers=env.auth)
    assert r.status_code == 503
    assert "isn't set up" in r.json()["error"]
    make_ready(env.app.summaries)
    r = env.remote.post("/api/v1/reply", json={"prompt": "Summarize: sleep", "maxTokens": 140}, headers=env.auth)
    assert r.status_code == 200, r.text
    assert r.json() == {"text": "Reply to: Summarize: sleep", "model": "gemma-4-26b-a4b"}
    # Checked: the pairing code, and what's asked.
    assert env.remote.post("/api/v1/reply", json={"prompt": "x", "maxTokens": 10}).status_code == 401
    assert env.remote.post("/api/v1/reply", json={"prompt": "", "maxTokens": 10}, headers=env.auth).status_code == 400
    assert env.remote.post("/api/v1/reply", json={"prompt": "x", "maxTokens": 99999}, headers=env.auth).status_code == 400
    assert env.remote.post("/api/v1/reply", json={"prompt": "x" * 70_000}, headers=env.auth).status_code == 400


def test_settings_show_the_models_download(env):
    st = env.client.get("/ui/api/summaries").json()
    assert st["state"] == "missing" and st["model"] == "Gemma 4 26B"
    assert st["downloadBytes"] == sm.LLAMA.size + sm.MODEL.size
    make_ready(env.app.summaries)
    assert env.client.get("/ui/api/summaries").json()["state"] == "ready"
    st = env.client.delete("/ui/api/summaries", headers={"X-AudioCool": "1"}).json()
    assert st["state"] == "missing"
    assert not env.app.summaries.root.exists()
    # Only from this computer.
    assert env.remote.get("/ui/api/summaries").status_code == 403


def test_transcribing_frees_the_gpu_from_an_idle_summary_model(env):
    make_ready(env.app.summaries)
    env.app.summaries.reply("Hello", 10)
    assert env.app.summaries.status()["running"]
    env.sync_with_audio()
    assert env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth, json={}).status_code == 202
    env.wait_jobs()
    assert not env.app.summaries.status()["running"]


def test_a_server_left_from_before_is_stopped_first(tmp_path):
    s = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path))
    make_ready(s)
    s.reply("Hello", 10)
    old = s._proc
    # As if the desktop app had been killed: the next one finds the server still running.
    s._proc = None
    again = Summaries(tmp_path / "summaries", launcher=fake_launcher(tmp_path))
    assert again.reply("Hello again", 10) == "Reply to: Hello again"
    assert old.wait(timeout=10) is not None
    again.close()
