"""A successful recognition can still need review; preserve that information end to end."""

import json

from audiocool_desktop.engines import Result


def test_job_quality_survives_restart_and_reaches_desktop_ui(env, monkeypatch):
    quality = {
        "version": 1,
        "coverage": {"audioMs": 2000, "audibleGapCount": 1, "gapsTruncated": False},
        "gaps": [{"s": 500, "e": 1500, "kind": "vad_skipped", "audible": True}],
        "alignment": {"fallbackCount": 1, "estimatedTiming": [{"s": 1500, "e": 2000, "reason": "failed"}]},
        "warnings": [{"code": "untranscribed_audio", "message": "Untranscribed audio requires review."}],
    }
    result = Result(
        segments=[{"s": 1500, "e": 2000, "t": "Two words", "w": [0, 250]}],
        duration_ms=2000, device="cpu", speech_s=0.5, chunks=1, quality=quality,
    )
    monkeypatch.setattr(env.engines[0], "transcribe_file", lambda *args: result)
    env.sync_with_audio()
    sid = "a1b2c3d4e5f6"
    response = env.ui("POST", f"/sessions/{sid}/transcribe", json={})
    assert response.status_code == 200
    env.wait_jobs()
    job = env.app.jobs.for_session(sid)[0]
    assert job["status"] == "done"
    assert "requires review" in job["info"]
    saved = json.loads((env.app.library.get(sid).folder / "audiocool-desktop.json").read_text())
    transcript = saved["transcripts"]["r1"]
    assert transcript["quality"] == quality
    assert transcript["speechSeconds"] == 0.5
    assert transcript["chunks"] == 1
    env.close()
    env.open()
    detail = env.ui("GET", f"/sessions/{sid}").json()
    assert detail["summary"]["recordings"][0]["quality"] == quality
    assert detail["session"]["recordings"][0]["transcript"][0]["w"] == [0, 250]
    # Desktop-only diagnostic metadata does not change the phone's recording contract.
    phone = env.client.get(f"/api/v1/sessions/{sid}", headers=env.auth).json()
    assert "quality" not in phone["session"]["recordings"][0]


def test_old_transcripts_without_quality_remain_readable(env):
    env.sync_with_audio()
    env.app.library.set_transcript(
        "a1b2c3d4e5f6", "r1", [], model="old-model", model_name="Old model",
    )
    detail = env.ui("GET", "/sessions/a1b2c3d4e5f6").json()
    assert detail["summary"]["recordings"][0]["quality"] is None
    assert detail["session"]["recordings"][0]["transcript"] == []
