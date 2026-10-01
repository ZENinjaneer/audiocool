"""Transcription jobs with a fake engine: lifecycle, persistence, errors, cancel, GPU fallback."""

from __future__ import annotations

import threading

import pytest

from audiocool_desktop.engines import ModelSpec, Registry
from audiocool_desktop.engines.fake import FakeEngine
from conftest import Env, phone_session, wait_for


def transcribe(env, body=None, sid="a1b2c3d4e5f6"):
    r = env.client.post(f"/api/v1/sessions/{sid}/transcribe", headers=env.auth, json=body or {})
    assert r.status_code == 202, r.text
    return r.json()["jobs"]


def session_body(env, sid="a1b2c3d4e5f6"):
    return env.client.get(f"/api/v1/sessions/{sid}", headers=env.auth).json()


def test_job_lifecycle(env):
    fast = env.engines[1]
    fast.gate = threading.Event()  # hold the job in "running" until we look
    env.sync_with_audio()
    (job,) = transcribe(env, {"model": "fake-fast", "recordingIds": None})
    assert job["status"] in ("queued", "running")
    assert wait_for(lambda: session_body(env)["jobs"][0]["status"] == "running")
    running = session_body(env)["jobs"][0]
    assert set(running) == {"id", "recordingId", "model", "status", "progress", "error"}
    assert running["id"] == job["id"] and running["error"] is None and 0 <= running["progress"] <= 1
    fast.gate.set()
    env.wait_jobs()
    body = session_body(env)
    assert body["jobs"] == [{"id": job["id"], "recordingId": "r1", "model": "fake-fast", "status": "done", "progress": 1.0, "error": None}]
    assert body["transcriptModels"] == {"r1": "fake-fast"}
    transcript = body["session"]["recordings"][0]["transcript"]
    assert [seg["t"] for seg in transcript] == ["First half by fake-fast.", "Second half by fake-fast."]
    assert transcript[0]["s"] == 0 and transcript[-1]["e"] == 2000
    listed = env.client.get("/api/v1/sessions", headers=env.auth).json()["sessions"][0]["recordings"][0]
    assert listed == {"id": "r1", "durationMs": 2000, "transcribed": True, "model": "fake-fast"}
    # The library folder holds the merged session and the desktop's own record of it.
    folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    assert "First half by fake-fast." in (folder / "session.json").read_text()
    assert "First half by fake-fast." in (folder / "notes.md").read_text()
    assert '"model": "fake-fast"' in (folder / "audiocool-desktop.json").read_text()


def test_phone_transcript_does_not_replace_desktop_transcript(env):
    env.sync_with_audio()
    transcribe(env)
    env.wait_jobs()
    s = phone_session(updated=1790000009999)
    s["recordings"][0]["transcript"] = [{"s": 0, "e": 500, "t": "phone moonshine words"}]
    s["notes"].append({"id": "n3", "text": "added later on the phone", "createdAt": 1790000009000})
    assert env.put_session(s, {"recording-1.m4a": 1}).status_code == 200
    body = session_body(env)
    assert body["transcriptModels"] == {"r1": "fake-best"}
    assert body["session"]["recordings"][0]["transcript"][0]["t"] == "First half by fake-best."
    assert body["session"]["notes"][-1]["text"] == "added later on the phone"  # metadata did update
    assert body["session"]["updatedAt"] == 1790000009999


def test_failed_job_reports_error(env):
    env.engines[0].fail = "model exploded"
    env.sync_with_audio()
    transcribe(env)
    env.wait_jobs()
    job = session_body(env)["jobs"][0]
    assert job["status"] == "error" and job["error"] == "model exploded"
    assert session_body(env)["transcriptModels"] == {}


def test_duplicate_requests_share_a_job(env):
    env.engines[0].gate = threading.Event()
    env.sync_with_audio()
    a = transcribe(env)[0]
    b = transcribe(env)[0]
    assert a["id"] == b["id"]
    c = transcribe(env, {"model": "fake-fast"})[0]
    assert c["id"] != a["id"]
    env.engines[0].gate.set()
    env.wait_jobs()
    assert [j["status"] for j in env.app.jobs.for_session("a1b2c3d4e5f6")] == ["done", "done"]
    # The phone sees the newest job of each recording.
    assert [(j["id"], j["status"]) for j in session_body(env)["jobs"]] == [(c["id"], "done")]


def test_phone_sees_the_latest_job_of_each_recording(env):
    """An old failure that a later job fixed mustn't look like the current state to the phone."""
    env.sync_with_audio()
    env.engines[0].fail = "first try failed"
    transcribe(env)
    env.wait_jobs()
    assert [j["status"] for j in session_body(env)["jobs"]] == ["error"]
    env.engines[0].fail = None
    retry = transcribe(env)[0]
    env.wait_jobs()
    body = session_body(env)
    assert [(j["id"], j["status"], j["error"]) for j in body["jobs"]] == [(retry["id"], "done", None)]
    assert len(env.app.jobs.for_session("a1b2c3d4e5f6")) == 2  # the web UI keeps the history


def test_jobs_run_one_at_a_time_in_order(env):
    order = []
    for e in env.engines:
        e.delay_s = 0.05
    original = [e.transcribe_file for e in env.engines]
    active = []

    def wrap(engine, fn):
        def run(path, progress=lambda f: None, cancelled=lambda: False):
            active.append(1)
            assert len(active) == 1, "two jobs ran at once"
            order.append(engine.id)
            try:
                return fn(path, progress, cancelled)
            finally:
                active.pop()
        return run

    for e, fn in zip(env.engines, original):
        e.transcribe_file = wrap(e, fn)
    env.sync_with_audio()
    env.sync_with_audio("ffffffffffff")
    transcribe(env, {"model": "fake-fast"})
    transcribe(env, {"model": "fake-best"}, sid="ffffffffffff")
    transcribe(env, {"model": "fake-best"})
    env.wait_jobs()
    assert order == ["fake-fast", "fake-best", "fake-best"]


def test_queue_survives_restart(tmp_path):
    env = Env(tmp_path, start_worker=False)
    try:
        env.sync_with_audio()
        jobs = transcribe(env) + transcribe(env, {"model": "fake-fast"})
        # Pretend the server died in the middle of the first job.
        env.app.jobs._db.execute("UPDATE jobs SET status='running', progress=0.5 WHERE id=?", (jobs[0]["id"],))
        assert [j["status"] for j in env.app.jobs.for_session("a1b2c3d4e5f6")] == ["running", "queued"]
        env.close()
        env.start_worker = True
        env.open()
        env.wait_jobs()
        history = env.app.jobs.for_session("a1b2c3d4e5f6")
        assert [(j["id"], j["status"]) for j in history] == [(jobs[0]["id"], "done"), (jobs[1]["id"], "done")]
        assert session_body(env)["transcriptModels"] == {"r1": "fake-fast"}  # the later job's transcript is the current one
    finally:
        env.close()


def test_cancel_queued_and_running(env):
    best = env.engines[0]
    best.gate = threading.Event()
    best.delay_s = 0.4
    env.sync_with_audio()
    running = transcribe(env)[0]
    queued = transcribe(env, {"model": "fake-fast"})[0]
    assert wait_for(lambda: env.app.jobs.get(running["id"])["status"] == "running")
    assert env.ui("DELETE", f"/jobs/{queued['id']}").status_code == 200
    assert env.ui("DELETE", f"/jobs/{running['id']}").status_code == 200
    best.gate.set()
    env.wait_jobs()
    statuses = {j["id"]: (j["status"], j["error"]) for j in env.app.jobs.for_session("a1b2c3d4e5f6")}
    assert statuses == {running["id"]: ("error", "Cancelled"), queued["id"]: ("error", "Cancelled")}
    assert env.ui("DELETE", f"/jobs/{running['id']}").status_code == 404
    jobs_page = env.ui("GET", "/jobs").json()["jobs"]
    assert {j["id"] for j in jobs_page} == {running["id"], queued["id"]}
    assert jobs_page[0]["sessionTitle"] == "Bio 101"


class GpuFlakyEngine(FakeEngine):
    def transcribe_file(self, path, progress=lambda f: None, cancelled=lambda: False):
        if self.device == "cuda":
            raise RuntimeError("CUDA out of memory. Tried to allocate 2.00 GiB")
        return super().transcribe_file(path, progress, cancelled)


def test_gpu_failure_falls_back_to_cpu(tmp_path):
    env = Env(tmp_path, cuda=True)
    try:
        made = []

        def factory(dev):
            e = GpuFlakyEngine("flaky", "Flaky", dev)
            made.append(e)
            return e

        env.app.registry = env.registry = Registry([ModelSpec("flaky", "Flaky", "test", "cuda", factory)], cuda=True)
        env.app.jobs.runner = env.app.run_job
        env.sync_with_audio()
        transcribe(env, {"model": "flaky"})
        env.wait_jobs()
        job = env.app.jobs.for_session("a1b2c3d4e5f6")[0]
        assert job["status"] == "done", job
        assert job["device"] == "cpu" and "CPU" in job["info"]
        assert [e.device for e in made] == ["cuda", "cpu"]
    finally:
        env.close()


def test_batches_shrink_when_the_gpu_runs_out_of_memory():
    from audiocool_desktop.engines.base import OomSplitter

    calls = []

    def fn(sub):
        calls.append(len(sub))
        if len(sub) > 3:
            raise RuntimeError("CUDA out of memory. Tried to allocate 1.5 GiB")
        return [x * 10 for x in sub]

    s = OomSplitter()
    assert s.run(list(range(10)), fn) == [x * 10 for x in range(10)]
    assert calls == [10, 5, 2, 2, 2, 2, 2] and s.cap == 2
    assert s.run([1, 2, 3], fn) == [10, 20, 30]  # later batches start at the smaller size
    with pytest.raises(ValueError):
        OomSplitter().run([1, 2], lambda sub: (_ for _ in ()).throw(ValueError("not memory")))


def test_default_registry_offers_three_models():
    from audiocool_desktop.engines import default_specs

    gpu = Registry(default_specs(), cuda=True)
    assert [m["id"] for m in gpu.models()] == ["qwen3-asr-1.7b", "parakeet-tdt-0.6b-v3", "parakeet-tdt-0.6b-v3-cpu"]
    assert gpu.default_id == "qwen3-asr-1.7b"
    cpu = Registry(default_specs(), cuda=False)
    assert cpu.default_id == "parakeet-tdt-0.6b-v3-cpu"
    assert {d["id"]: d["device"] for d in cpu.describe()} == {
        "qwen3-asr-1.7b": "cpu", "parakeet-tdt-0.6b-v3": "cpu", "parakeet-tdt-0.6b-v3-cpu": "cpu"}
