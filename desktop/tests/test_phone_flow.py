"""The app's own sync logic (DesktopSync.kt on main), replayed against this server.

The phone sends the session with the sizes of its finished recordings, uploads what the desktop
asks for, asks for transcripts of the recordings the desktop hasn't transcribed (and isn't
working on), then polls until no job is active, takes every transcript listed in
``transcriptModels``, and reports a failure if the last job it sees ended in an error.
"""

from __future__ import annotations

from conftest import T0, aac_bytes, phone_session


class Phone:
    """A Python port of DesktopSync.run() and the bits of DesktopClient it uses."""

    def __init__(self, env, session: dict, audio: dict[str, bytes]):
        self.env, self.session, self.audio = env, session, audio
        self.transcripts: dict[str, tuple[list, str]] = {}
        self.uploads: list[str] = []
        self.transcribe_requests: list[dict] = []

    def call(self, method, path, **kw):
        r = self.env.client.request(method, path, headers=self.env.auth, **kw)
        assert 200 <= r.status_code < 300, (method, path, r.status_code, r.text)
        return r.json() if r.content else {}

    def send(self) -> tuple[str, str | None]:
        sid = self.session["id"]
        recordings = [r for r in self.session["recordings"] if r["durationMs"] > 0]
        sizes = {r["file"]: len(self.audio[r["file"]]) for r in recordings}
        needed = self.call("PUT", f"/api/v1/sessions/{sid}", json={"session": self.session, "files": sizes})["needed"]
        for name in needed:
            self.call("PUT", f"/api/v1/sessions/{sid}/files/{name}", content=self.audio[name])
            self.uploads.append(name)
        remote = self.call("GET", f"/api/v1/sessions/{sid}")
        missing = [r["id"] for r in recordings if r["id"] not in remote["transcriptModels"]
                   and not any(j["recordingId"] == r["id"] and j["status"] in ("queued", "running") for j in remote["jobs"])]
        if missing:
            body = {"model": None, "recordingIds": missing}
            self.transcribe_requests.append(body)
            self.call("POST", f"/api/v1/sessions/{sid}/transcribe", json=body)
        self.env.wait_jobs()  # instead of polling every 2 s
        remote = self.call("GET", f"/api/v1/sessions/{sid}")
        assert not [j for j in remote["jobs"] if j["status"] in ("queued", "running")]
        for rec in remote["session"]["recordings"]:
            model = remote["transcriptModels"].get(rec["id"])
            if model is not None:
                self.transcripts[rec["id"]] = (rec.get("transcript") or [], model)
                for mine in self.session["recordings"]:
                    if mine["id"] == rec["id"]:
                        mine["transcript"], mine["transcriptModel"] = rec.get("transcript") or [], model
        failed = next((j for j in reversed(remote["jobs"]) if j["status"] == "error"), None)
        return ("FAILED", failed["error"]) if failed else ("DONE", None)


def two_recordings(env):
    s = phone_session(recordings=[
        {"id": "r1", "file": "recording-1.m4a", "createdAt": T0, "durationMs": 2000},
        {"id": "r2", "file": "recording-2.aac", "createdAt": T0 + 60_000, "durationMs": 0},  # still recording
    ])
    return s, {"recording-1.m4a": aac_bytes(env.tmp, 2.0), "recording-2.aac": aac_bytes(env.tmp, 1.0, "aac")}


def test_first_send_transcribes_and_brings_transcripts_back(env):
    s, audio = two_recordings(env)
    phone = Phone(env, s, audio)
    assert phone.send() == ("DONE", None)
    assert phone.uploads == ["recording-1.m4a"]
    assert phone.transcribe_requests == [{"model": None, "recordingIds": ["r1"]}]
    segments, model = phone.transcripts["r1"]
    assert model == "fake-best" and segments[0]["t"] == "First half by fake-best."
    assert "r2" not in phone.transcripts


def test_sending_again_redoes_nothing(env):
    s, audio = two_recordings(env)
    phone = Phone(env, s, audio)
    phone.send()
    phone.uploads.clear()
    phone.transcribe_requests.clear()
    s["notes"].append({"id": "n9", "text": "a later note", "createdAt": T0 + 99_000})
    s["updatedAt"] += 1
    assert phone.send() == ("DONE", None)
    assert phone.uploads == [] and phone.transcribe_requests == []
    # The finished second recording goes up and only it is transcribed.
    s["recordings"][1]["durationMs"] = 1000
    assert phone.send() == ("DONE", None)
    assert phone.uploads == ["recording-2.aac"]
    assert phone.transcribe_requests == [{"model": None, "recordingIds": ["r2"]}]


def test_a_failure_then_a_successful_retry_ends_as_done(env):
    s, audio = two_recordings(env)
    phone = Phone(env, s, audio)
    env.engines[0].fail = "GPU on fire"
    assert phone.send() == ("FAILED", "GPU on fire")
    env.engines[0].fail = None
    assert phone.send() == ("DONE", None)  # the old error job no longer counts
    assert phone.transcripts["r1"][1] == "fake-best"
