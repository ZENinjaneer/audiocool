"""Desktop persistence of the Android s/e/t/w transcript contract.

SessionJson.kt encodes w as List<Int> offsets from s; Timeline.kt pairs those
offsets with text.trim().split(' '). Model provenance remains desktop sidecar data.
"""

from __future__ import annotations

import copy
import json

import pytest

from conftest import phone_session
from audiocool_desktop import sessionfmt as fmt
from audiocool_desktop.engines.segmenter import Word, build_segments
from audiocool_desktop.library import Library, SIDECAR


def timed_segments():
    return [
        {"s": 1000, "e": 2000, "t": "Hello world.", "w": [0, 500], "we": [400, 1000]},
        {"s": 3000, "e": 4200, "t": "Another sentence.", "w": [100, 600], "we": [500, 1100]},
    ]


def phone_view(env, sid):
    response = env.client.get(f"/api/v1/sessions/{sid}", headers=env.auth)
    assert response.status_code == 200
    return response.json()["session"]


def test_engine_word_offsets_survive_save_reload_import_and_phone_sync(env):
    session = phone_session()
    assert env.put_session(session).status_code == 200
    segments = build_segments([Word("Hello", 1.0, 1.5), Word("world.", 1.5, 2.0)])
    assert [{k: s[k] for k in ("s", "e", "t", "w")} for s in segments] == [
        {"s": 1000, "e": 2000, "t": "Hello world.", "w": [0, 500]},
    ]
    segments[0]["we"] = [500, 1000]
    # Optional extension fields and model provenance must not be silently discarded.
    segments[0]["confidence"] = {"mean": 0.9}
    original = copy.deepcopy(segments)
    timings = {"recognize": 1.25, "align": 0.4}
    provenance = {"modelRevision": "revision-123", "alignerRevision": "revision-456"}
    env.app.library.set_transcript(
        session["id"], "r1", segments, model="test-model", model_name="Test model",
        timings=timings, provenance=provenance, audioDurationMs=5000, device="cpu",
    )
    # The in-memory library owns its copy, including nested extension metadata.
    segments[0]["w"][1] = 999
    segments[0]["confidence"]["mean"] = 0.1
    timings["recognize"] = 100
    provenance["modelRevision"] = "changed"
    entry = env.app.library.require(session["id"])
    assert entry.recording("r1")["transcript"] == original
    assert entry.desktop_transcript("r1")["timings"]["recognize"] == 1.25
    assert entry.desktop_transcript("r1")["provenance"]["modelRevision"] == "revision-123"
    folder = entry.folder
    assert json.loads((folder / "session.json").read_text())["recordings"][0]["transcript"] == original

    env.close()
    env.open()
    returned = phone_view(env, session["id"])
    assert returned["recordings"][0]["transcript"] == original
    assert returned["recordings"][0]["transcriptModel"] == "test-model"
    # Mirror SessionJson.kt's encoder, which retains s/e/t/w but not unknown extension fields.
    returned["recordings"][0]["transcript"] = [
        {k: v for k, v in s.items() if k in ("s", "e", "t", "w")}
        for s in returned["recordings"][0]["transcript"]
    ]
    assert env.put_session(returned).status_code == 200
    assert phone_view(env, session["id"])["recordings"][0]["transcript"] == original
    assert env.app.library.require(session["id"]).desktop_transcript("r1")["provenance"] == {
        "modelRevision": "revision-123", "alignerRevision": "revision-456",
    }

    # A backup containing session.json + the desktop sidecar retains both kinds of data.
    imported = Library(env.tmp / "imported")
    assert imported.import_folder(folder) == "added"
    restored = Library(imported.root).require(session["id"])
    assert restored.recording("r1")["transcript"] == original
    assert restored.desktop_transcript("r1") == env.app.library.require(session["id"]).desktop_transcript("r1")
    assert "Hello world." in (restored.folder / "notes.md").read_text()

    # Text exports intentionally keep segment timing; exporting must not mutate word timing.
    assert "00:00:01,000 --> 00:00:02,000" in env.ui("GET", f"/sessions/{session['id']}/export.srt").text
    assert "Hello world." in env.ui("GET", f"/sessions/{session['id']}/export.txt").text
    assert phone_view(env, session["id"])["recordings"][0]["transcript"] == original


@pytest.mark.parametrize("desktop_owned", [False, True])
def test_edit_invalidates_only_changed_line_alignment_and_survives_sync(env, desktop_owned):
    session = phone_session()
    session["recordings"][0].update(transcript=timed_segments(), transcriptModel="original-model")
    session["recordings"][0]["transcript"][0]["timingEstimated"] = True
    assert env.put_session(session).status_code == 200
    if desktop_owned:
        env.app.library.set_transcript(session["id"], "r1", session["recordings"][0]["transcript"], "original-model", "Original")
    path = f"/sessions/{session['id']}/recordings/r1/transcript/0"
    # A no-op correction does not throw away an alignment that still matches the text.
    assert env.ui("PATCH", path, json={"text": "Hello world."}).status_code == 200
    assert phone_view(env, session["id"])["recordings"][0]["transcript"][0]["w"] == [0, 500]
    # Same word count is insufficient: different words still require a new alignment.
    assert env.ui("PATCH", path, json={"text": "Hello there."}).status_code == 200
    rec = phone_view(env, session["id"])["recordings"][0]
    assert rec["transcript"][0] == {"s": 1000, "e": 2000, "t": "Hello there."}
    assert rec["transcript"][1] == timed_segments()[1]
    assert rec["transcriptModel"] == "original-model"
    # The original phone copy must not restore stale text or its offsets on a later sync.
    assert env.put_session(session).status_code == 200
    env.close()
    env.open()
    rec = phone_view(env, session["id"])["recordings"][0]
    assert rec["transcript"][0]["t"] == "Hello there." and "w" not in rec["transcript"][0]
    assert rec["transcript"][1]["w"] == [100, 600]


@pytest.mark.parametrize("words", [
    "0, 500", {}, [0], [0, 500, 600], [0, True], [0, 1.5], [0, "500"],
    [-1, 500], [500, 0], [0, 1001], [0, float("nan")], [0, float("inf")],
])
def test_rejects_invalid_optional_word_offsets(words):
    segment = {"s": 1000, "e": 2000, "t": "two words", "w": words}
    with pytest.raises(fmt.BadInput, match=r"transcript\[0\]\.w"):
        fmt.validate_transcript([segment])


def test_offset_validation_matches_android_ints_and_literal_space_tokenization():
    segments = [
        {"s": 10, "e": 10, "t": "two words", "w": [0, 0]},
        {"s": 10, "e": 50, "t": " two  words ", "w": [0, 20, 40]},
        {"s": 50, "e": 100, "t": "tab\tword", "w": [0]},
        {"s": 100, "e": 200, "t": "", "w": []},
        {"s": 0, "e": 2_147_483_647, "t": "two words", "w": [0, 2_147_483_647]},
    ]
    assert fmt.validate_transcript(segments) == segments
    segments[-1].update(e=2_147_483_648, w=[0, 2_147_483_648])
    with pytest.raises(fmt.BadInput, match="Android Int range"):
        fmt.validate_transcript(segments)


def test_old_transcripts_and_null_word_timings_remain_usable():
    old = {"s": 100, "e": 200, "t": "old transcript"}
    assert fmt.validate_transcript([old, {**old, "w": None}]) == [old, old]


@pytest.mark.parametrize("ends", ["500, 1000", {}, [500], [500, 1000, 1000], [500, True], [500, 999.5], [500, 499], [500, 1001]])
def test_rejects_invalid_word_end_offsets(ends):
    with pytest.raises(fmt.BadInput, match=r"transcript\[0\]\.we"):
        fmt.validate_transcript([{"s": 0, "e": 1000, "t": "two words", "w": [0, 500], "we": ends}])


def test_word_end_offsets_require_word_starts_and_estimate_flag_is_boolean():
    with pytest.raises(fmt.BadInput, match=r"\.we"):
        fmt.validate_transcript([{"s": 0, "e": 1000, "t": "two words", "we": [500, 1000]}])
    with pytest.raises(fmt.BadInput, match="timingEstimated"):
        fmt.validate_transcript([{"s": 0, "e": 1000, "t": "two words", "timingEstimated": "false"}])


def test_validation_and_bad_metadata_leave_existing_transcript_untouched(env):
    session = phone_session()
    assert env.put_session(session).status_code == 200
    lib = env.app.library
    lib.set_transcript(session["id"], "r1", timed_segments(), "original", "Original")
    folder = lib.require(session["id"]).folder
    before = {name: (folder / name).read_bytes() for name in ("session.json", SIDECAR, "notes.md")}
    bad = timed_segments()
    bad[0]["w"] = [False, 500]
    with pytest.raises(fmt.BadInput):
        lib.set_transcript(session["id"], "r1", bad, "new", "New")
    for metadata in ({"timings": {"align": float("nan")}}, {"provenance": object()}):
        with pytest.raises(fmt.BadInput, match="finite JSON"):
            lib.set_transcript(session["id"], "r1", timed_segments(), "new", "New", **metadata)
    for name, data in before.items():
        assert (folder / name).read_bytes() == data
    assert lib.require(session["id"]).desktop_transcript("r1")["model"] == "original"


def test_extra_metadata_cannot_override_transcript_ownership(env):
    session = phone_session()
    assert env.put_session(session).status_code == 200
    env.app.library.set_transcript(
        session["id"], "r1", timed_segments(), "correct-model", "Correct model",
        modelName="Wrong model", origin="phone", createdAt=1, edited=True,
        provenance={"revision": "keep-this"},
    )
    record = env.app.library.require(session["id"]).desktop_transcript("r1")
    assert record["modelName"] == "Correct model" and record["origin"] == "desktop"
    assert record["edited"] is False and record["createdAt"] > 1
    assert record["provenance"] == {"revision": "keep-this"}


def test_phone_upload_rejects_invalid_word_timing_without_replacing_good_data(env):
    session = phone_session()
    session["recordings"][0]["transcript"] = timed_segments()
    assert env.put_session(session).status_code == 200
    session["recordings"][0]["transcript"][0]["w"] = [0, -1]
    response = env.put_session(session)
    assert response.status_code == 400 and ".w[1]" in response.json()["error"]
    assert phone_view(env, session["id"])["recordings"][0]["transcript"] == timed_segments()
