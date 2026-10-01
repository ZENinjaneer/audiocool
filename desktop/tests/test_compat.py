"""Compatibility with the app's newer session format and with damaged or older data.

The app now records which model made each transcript (``transcriptModel``) and attaches photos to
notes (``photo``, ``photoText``, ``thumbnail``); its backups carry the photo files.
"""

from __future__ import annotations

import json

from conftest import T0, aac_bytes, phone_session

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


def test_phone_transcript_model_is_kept_and_desktop_model_replaces_it(env):
    s = phone_session()
    s["recordings"][0]["transcript"] = [{"s": 0, "e": 900, "t": "phone words"}]
    s["recordings"][0]["transcriptModel"] = "parakeet-unified-en-0.6b"
    data = aac_bytes(env.tmp, 2.0)
    env.put_session(s, {"recording-1.m4a": len(data)})
    env.upload(s["id"], "recording-1.m4a", data)
    body = env.client.get(f"/api/v1/sessions/{s['id']}", headers=env.auth).json()
    assert body["session"]["recordings"][0]["transcriptModel"] == "parakeet-unified-en-0.6b"
    assert body["transcriptModels"] == {}
    rec = env.ui("GET", f"/sessions/{s['id']}").json()["summary"]["recordings"][0]
    assert rec["transcriptSource"] == "phone" and rec["modelName"] == "Parakeet 0.6B (phone)"
    env.client.post(f"/api/v1/sessions/{s['id']}/transcribe", headers=env.auth, json={})
    env.wait_jobs()
    body = env.client.get(f"/api/v1/sessions/{s['id']}", headers=env.auth).json()
    assert body["transcriptModels"] == {"r1": "fake-best"}
    assert body["session"]["recordings"][0]["transcriptModel"] == "fake-best"
    # The phone stores the desktop's transcript and sends it back labelled with that model.
    s["recordings"][0]["transcript"] = body["session"]["recordings"][0]["transcript"]
    s["recordings"][0]["transcriptModel"] = "fake-best"
    env.put_session(s, {"recording-1.m4a": len(data)})
    assert env.client.get(f"/api/v1/sessions/{s['id']}", headers=env.auth).json()["transcriptModels"] == {"r1": "fake-best"}


def test_editing_a_phone_transcript_keeps_its_model_label(env):
    s = phone_session()
    s["recordings"][0]["transcript"] = [{"s": 0, "e": 900, "t": "the crebs cycle"}]
    s["recordings"][0]["transcriptModel"] = "parakeet-unified-en-0.6b"
    env.put_session(s)
    env.ui("PATCH", f"/sessions/{s['id']}/recordings/r1/transcript/0", json={"text": "The Krebs cycle"})
    body = env.client.get(f"/api/v1/sessions/{s['id']}", headers=env.auth).json()
    assert body["transcriptModels"] == {"r1": "parakeet-unified-en-0.6b"}
    assert body["session"]["recordings"][0]["transcript"][0]["t"] == "The Krebs cycle"
    rec = env.ui("GET", f"/sessions/{s['id']}").json()["summary"]["recordings"][0]
    assert rec["transcriptSource"] == "phone-edited" and rec["edited"]


def _backup_with_photos(root, sid="ab12ab12ab12"):
    s = phone_session(sid, title="Slides lecture", notes=[
        {"id": "n1", "text": "Slide 3", "createdAt": T0 + 1000, "recId": "r1", "offsetMs": 500,
         "photo": "photo-n1.jpg", "photoText": "Mitochondria\nThe powerhouse of the cell\nATP synthesis"},
        {"id": "n2", "text": "", "createdAt": T0 + 2000, "photo": "photo-n2.jpg"},
        {"id": "n3", "text": "plain note", "createdAt": T0 + 3000},
    ])
    s["thumbnail"] = "n2"
    folder = root / f"2026-09-21_10-00_{sid}"
    folder.mkdir(parents=True)
    (folder / "session.json").write_text(json.dumps(s))
    (folder / "photo-n1.jpg").write_bytes(JPEG)
    (folder / "photo-n2.jpg").write_bytes(JPEG + b"2")
    (folder / "photo-stray.jpg").write_bytes(JPEG)  # not referenced by any note
    return folder, s


def test_photos_from_a_backup(env):
    _, s = _backup_with_photos(env.tmp / "backup")
    assert env.ui("POST", "/import/path", json={"path": str(env.tmp / "backup")}).json()["added"] == 1
    lib_folder = next(env.library.glob("*_ab12ab12ab12"))
    assert (lib_folder / "photo-n1.jpg").read_bytes() == JPEG
    assert not (lib_folder / "photo-stray.jpg").exists()
    detail = env.ui("GET", "/sessions/ab12ab12ab12").json()
    assert set(detail["photos"]) == {"photo-n1.jpg", "photo-n2.jpg"}
    assert detail["session"]["notes"][0]["photoText"].startswith("Mitochondria")
    summary = next(x for x in env.ui("GET", "/sessions").json()["sessions"] if x["id"] == "ab12ab12ab12")
    assert summary["thumbnail"].endswith("/photo/photo-n2.jpg") and summary["photoCount"] == 2
    r = env.ui("GET", "/sessions/ab12ab12ab12/photo/photo-n1.jpg")
    assert r.status_code == 200 and r.content == JPEG and r.headers["content-type"] == "image/jpeg"
    assert env.ui("GET", "/sessions/ab12ab12ab12/photo/photo-stray.jpg").status_code == 404
    assert env.ui("GET", "/sessions/ab12ab12ab12/photo/..%2Fsession.json").status_code == 404
    # Photos show in notes.md and the exports the way the app writes them.
    md = (lib_folder / "notes.md").read_text()
    assert "- [00:00] ![Slide 3](photo-n1.jpg)" in md and "- ![Photo](photo-n2.jpg)" in md
    txt = env.ui("GET", "/sessions/ab12ab12ab12/export.txt").text
    assert "[00:00] [photo photo-n1.jpg] Slide 3" in txt


def test_photos_through_the_api_extension(env):
    """Photos can also be uploaded like recordings (an addition to the contract)."""
    data = aac_bytes(env.tmp, 2.0)
    s = phone_session(notes=[
        {"id": "n1", "text": "slide", "createdAt": T0, "recId": "r1", "offsetMs": 100, "photo": "photo-n1.jpg"},
    ])
    files = {"recording-1.m4a": len(data), "photo-n1.jpg": len(JPEG), "photo-other.jpg": 5}
    assert env.put_session(s, files).json() == {"needed": ["recording-1.m4a", "photo-n1.jpg"]}
    assert env.upload(s["id"], "photo-other.jpg", JPEG).status_code == 404  # no note uses it
    assert env.upload(s["id"], "photo-n1.jpg", JPEG).status_code == 204
    assert env.put_session(s, files).json() == {"needed": ["recording-1.m4a"]}
    assert env.ui("GET", f"/sessions/{s['id']}/photo/photo-n1.jpg").content == JPEG
    summary = env.ui("GET", "/sessions").json()["sessions"][0]
    assert summary["thumbnail"].endswith("/photo/photo-n1.jpg")
    # The note is deleted on the phone: its photo goes too.
    s["notes"] = []
    env.put_session(s, {"recording-1.m4a": len(data)})
    assert not list(env.library.glob("*/photo-n1.jpg"))


def test_search_finds_text_on_photos(env):
    _backup_with_photos(env.tmp / "backup")
    env.ui("POST", "/import/path", json={"path": str(env.tmp / "backup")})
    hits = env.ui("GET", "/search", params={"q": "powerhouse cell"}).json()["hits"]
    assert [(h["kind"], h["text"]) for h in hits] == [("photo", "The powerhouse of the cell")]
    assert hits[0]["noteId"] == "n1" and hits[0]["atMs"] == 500
    hits = env.ui("GET", "/search", params={"q": "mitochondria atp"}).json()["hits"]
    assert hits[0]["text"] == "Mitochondria · ATP synthesis"
    assert [hits[0]["text"][a:b] for a, b in hits[0]["matches"]] == ["Mitochondria", "ATP"]


def test_zip_import_brings_photos(env):
    import io
    import zipfile

    folder, _ = _backup_with_photos(env.tmp / "zipsrc")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for p in folder.iterdir():
            z.write(p, f"Backup/{folder.name}/{p.name}")
    assert env.ui("POST", "/import/zip", content=buf.getvalue()).json()["added"] == 1
    assert (next(env.library.glob("*_ab12ab12ab12")) / "photo-n1.jpg").exists()


def test_damaged_sidecar_doesnt_break_the_library(env):
    env.sync_with_audio()
    folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    (folder / "audiocool-desktop.json").write_text(json.dumps({"transcripts": [], "title": "x", "audio": 3}))
    env.app.library.refresh(force=True)
    sessions = env.ui("GET", "/sessions").json()["sessions"]
    assert [x["id"] for x in sessions] == ["a1b2c3d4e5f6"]
    (folder / "audiocool-desktop.json").write_text(json.dumps({"transcripts": {"r1": {"model": "m", "segments": "nope"}}}))
    env.app.library.refresh(force=True)
    body = env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()
    assert body["transcriptModels"] == {}
    (folder / "audiocool-desktop.json").write_text("{not json")
    env.app.library.refresh(force=True)
    assert env.client.get("/api/v1/sessions", headers=env.auth).status_code == 200


def test_older_backup_doesnt_overwrite_newer_audio(env):
    env.sync_with_audio(seconds=2.0)
    lib_folder = next(env.library.glob("*_a1b2c3d4e5f6"))
    current = (lib_folder / "recording-1.m4a").read_bytes()
    backup = env.tmp / "old-backup" / "2026-09-21_10-00_a1b2c3d4e5f6"
    backup.mkdir(parents=True)
    old = phone_session(updated=T0 - 5000)  # older than the library's copy
    (backup / "session.json").write_text(json.dumps(old))
    (backup / "recording-1.m4a").write_bytes(aac_bytes(env.tmp, 1.0))  # a different (shorter) file
    result = env.ui("POST", "/import/path", json={"path": str(env.tmp / "old-backup")}).json()
    assert result["unchanged"] == 1
    assert (lib_folder / "recording-1.m4a").read_bytes() == current


def test_absurd_timestamps_are_rejected(env):
    s = phone_session()
    s["createdAt"] = 10**17
    r = env.put_session(s)
    assert r.status_code == 400 and "too large" in r.json()["error"]


def test_saving_only_the_name_keeps_the_library(env):
    before = env.ui("GET", "/settings").json()
    assert before["libraryOverridden"] is True  # the tests start with --library
    env.ui("PUT", "/settings", json={"name": "Lab PC"})
    after = env.ui("GET", "/settings").json()
    assert after["library"] == before["library"] and after["libraryOverridden"] is True
    assert json.loads((env.home / "config.json").read_text())["library"] != before["library"]
