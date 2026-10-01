"""The phone API (/api/v1): auth, every endpoint, validation and error codes."""

from __future__ import annotations

import json
import re
from urllib.parse import parse_qs, urlparse

import pytest

from conftest import T0, phone_session

API_ROUTES = [
    ("GET", "/api/v1/ping"),
    ("GET", "/api/v1/sessions"),
    ("PUT", "/api/v1/sessions/a1b2c3d4e5f6"),
    ("GET", "/api/v1/sessions/a1b2c3d4e5f6"),
    ("PUT", "/api/v1/sessions/a1b2c3d4e5f6/files/recording-1.m4a"),
    ("POST", "/api/v1/sessions/a1b2c3d4e5f6/transcribe"),
]


@pytest.mark.parametrize("method,path", API_ROUTES)
def test_every_route_needs_the_token(env, method, path):
    env.sync_with_audio()
    for headers in ({}, {"Authorization": "Bearer WRONG123"}, {"Authorization": env.config.token}, {"Authorization": "Basic abc"}):
        r = env.client.request(method, path, headers=headers, json={})
        assert r.status_code == 401, (headers, r.text)
        assert r.json() == {"error": "missing or wrong pairing code"}
        assert r.headers["www-authenticate"] == "Bearer"
    # ...also from another device on the LAN
    assert env.remote.request(method, path, json={}).status_code == 401


def test_token_is_8_easy_characters_and_case_insensitive(env):
    token = env.config.token
    assert re.fullmatch(r"[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}", token)
    for variant in (token, token.lower(), f"{token[:4]}-{token[4:]}"):
        r = env.client.get("/api/v1/ping", headers={"Authorization": f"Bearer {variant}"})
        assert r.status_code == 200


def test_token_persists_across_restarts(env):
    token = env.config.token
    env.close()
    env.open()
    assert env.config.token == token
    assert json.loads((env.home / "config.json").read_text())["token"] == token


def test_ping(env):
    r = env.client.get("/api/v1/ping", headers=env.auth)
    assert r.status_code == 200
    body = r.json()
    assert body["app"] == "audiocool-desktop"
    assert body["version"] == "1.0"
    assert body["name"] == env.config.name
    assert body["models"] == [
        {"id": "fake-best", "name": "Fake best", "default": True},
        {"id": "fake-fast", "name": "Fake fast", "default": False},
    ]


def test_sessions_list(env):
    assert env.client.get("/api/v1/sessions", headers=env.auth).json() == {"sessions": []}
    env.sync_with_audio()
    env.put_session(phone_session("bbbbbbbbbbbb", title="Older", recordings=[], notes=[]) | {"createdAt": T0 - 86_400_000})
    sessions = env.client.get("/api/v1/sessions", headers=env.auth).json()["sessions"]
    assert [s["id"] for s in sessions] == ["a1b2c3d4e5f6", "bbbbbbbbbbbb"]  # newest first
    assert sessions[0] == {
        "id": "a1b2c3d4e5f6", "title": "Bio 101", "createdAt": T0, "updatedAt": T0,
        "recordings": [{"id": "r1", "durationMs": 2000, "transcribed": False, "model": None}],
    }


def test_put_session_writes_the_backup_layout(env):
    s = phone_session()
    s["notes"][0]["extra"] = {"unknown": "fields are kept"}
    assert env.put_session(s).status_code == 200
    folders = [p for p in env.library.iterdir() if p.is_dir()]
    assert len(folders) == 1
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}_\d{2}-\d{2}_a1b2c3d4e5f6", folders[0].name)
    stored = json.loads((folders[0] / "session.json").read_text())
    assert stored["notes"][0]["extra"] == {"unknown": "fields are kept"}
    assert stored["title"] == "Bio 101"
    md = (folders[0] / "notes.md").read_text()
    assert md.startswith("# Bio 101\n")
    assert "- [00:01] Key point about mitochondria" in md
    assert "- unlinked note" in md
    # Updating keeps the same folder.
    s["title"] = "Bio 101 (week 2)"
    s["updatedAt"] = T0 + 1000
    env.put_session(s)
    assert [p.name for p in env.library.iterdir() if p.is_dir()] == [folders[0].name]
    assert json.loads((folders[0] / "session.json").read_text())["title"] == "Bio 101 (week 2)"


@pytest.mark.parametrize("mutate,expect", [
    (lambda b: b.pop("session"), "expected"),
    (lambda b: b["session"].update(id="other"), "does not match"),
    (lambda b: b["session"]["recordings"][0].update(file="../../etc/passwd"), "recording-N"),
    (lambda b: b["session"]["recordings"][0].update(file="recording-1.wav"), "recording-N"),
    (lambda b: b["session"].update(createdAt="yesterday"), "number"),
    (lambda b: b["session"]["recordings"].append(dict(b["session"]["recordings"][0])), "duplicate"),
    (lambda b: b["session"]["recordings"][0].update(transcript=[{"s": 1, "e": 2}, "x"]), "transcript"),
    (lambda b: b.update(files=[1, 2]), "files"),
    (lambda b: b.update(files={"recording-1.m4a": -5}), ">= 0"),
    (lambda b: b["session"].update(notes="nope"), "notes"),
])
def test_put_session_rejects_bad_input(env, mutate, expect):
    body = {"session": phone_session(), "files": {}}
    mutate(body)
    r = env.client.put("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth, json=body)
    assert r.status_code == 400, r.text
    assert expect in r.json()["error"]


def test_put_session_rejects_bad_json_and_ids(env):
    r = env.client.put("/api/v1/sessions/a1b2c3d4e5f6", headers={**env.auth, "Content-Type": "application/json"}, content=b"{nope")
    assert r.status_code == 400 and "invalid JSON" in r.json()["error"]
    r = env.client.put("/api/v1/sessions/bad%20id", headers=env.auth, json={"session": phone_session("bad id"), "files": {}})
    assert r.status_code == 400


def test_get_session(env):
    assert env.client.get("/api/v1/sessions/nosuchsession", headers=env.auth).status_code == 404
    s = phone_session()
    s["recordings"][0]["transcript"] = [{"s": 100, "e": 900, "t": "phone words"}]
    env.put_session(s)
    body = env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()
    assert body["transcriptModels"] == {}
    assert body["jobs"] == []
    assert body["session"]["recordings"][0]["transcript"] == [{"s": 100, "e": 900, "t": "phone words"}]
    assert body["session"]["notes"] == s["notes"]
    assert body["session"]["title"] == "Bio 101"


def test_transcribe_validation(env):
    assert env.client.post("/api/v1/sessions/nosuchsession/transcribe", headers=env.auth, json={}).status_code == 404
    env.put_session(phone_session())  # no audio yet
    r = env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth, json={"model": None, "recordingIds": None})
    assert r.status_code == 400 and "uploaded" in r.json()["error"]
    env.sync_with_audio()
    for body, msg in [
        ({"model": "nope"}, "unknown model"),
        ({"recordingIds": ["r9"]}, "unknown recording"),
        ({"recordingIds": "r1"}, "list"),
        ([], "object"),
    ]:
        r = env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth, json=body)
        assert r.status_code == 400 and msg in r.json()["error"], (body, r.text)


def test_transcribe_returns_202_with_jobs(env):
    env.sync_with_audio()
    r = env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth, json={"model": "fake-fast", "recordingIds": ["r1"]})
    assert r.status_code == 202
    jobs = r.json()["jobs"]
    assert len(jobs) == 1
    assert set(jobs[0]) == {"id", "recordingId", "model", "status"}
    assert jobs[0]["recordingId"] == "r1" and jobs[0]["model"] == "fake-fast" and jobs[0]["status"] in ("queued", "running")
    # An empty body means the default model and all recordings.
    env.wait_jobs()
    r = env.client.post("/api/v1/sessions/a1b2c3d4e5f6/transcribe", headers=env.auth)
    assert r.status_code == 202 and r.json()["jobs"][0]["model"] == "fake-best"


def test_unknown_api_paths_are_404_json(env):
    r = env.client.get("/api/v1/nothing", headers=env.auth)
    assert r.status_code == 404 and "error" in r.json()


# --- web UI access -------------------------------------------------------------------------


def test_web_ui_is_local_only(env):
    for path in ("/", "/static/app.js", "/static/app.css", "/ui/api/sessions", "/ui/api/pair"):
        assert env.client.get(path).status_code == 200, path
        r = env.remote.get(path)
        assert r.status_code == 403, path
        assert "only opens on this computer" in r.text
    # A page on another site can't use the UI API through DNS rebinding (wrong Host header)...
    assert env.client.get("/ui/api/pair", headers={"Host": "evil.example:8765"}).status_code == 403
    # ...or post to it without the custom header (a CORS preflight would be needed).
    r = env.client.post("/ui/api/pair/reset")
    assert r.status_code == 403
    assert env.client.post("/ui/api/pair/reset", headers={"X-AudioCool": "1"}).status_code == 200


def test_pair_page_qr_content(env):
    body = env.ui("GET", "/pair").json()
    assert body["token"] == env.config.token
    assert body["urls"] == ["http://192.168.1.20:8765"]
    code = body["codes"][0]
    assert code["content"] == f"audiocool://pair?url=http%3A%2F%2F192.168.1.20%3A8765&token={env.config.token}"
    q = parse_qs(urlparse(code["content"]).query)
    assert q["url"] == ["http://192.168.1.20:8765"] and q["token"] == [env.config.token]
    assert code["svg"].startswith("<svg") and "</svg>" in code["svg"]


def test_reset_pairing_code(env):
    old = env.config.token
    new = env.ui("POST", "/pair/reset").json()["token"]
    assert new != old and env.config.token == new
    assert env.client.get("/api/v1/ping", headers={"Authorization": f"Bearer {old}"}).status_code == 401
    assert env.client.get("/api/v1/ping", headers={"Authorization": f"Bearer {new}"}).status_code == 200
