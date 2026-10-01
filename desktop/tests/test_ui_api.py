"""The web UI's API: library, search, export, edits, playback, import and settings."""

from __future__ import annotations

import io
import json
import shutil
import zipfile

from conftest import T0, aac_bytes, phone_session


def test_library_summary_and_detail(env):
    env.sync_with_audio()
    data = env.ui("GET", "/sessions").json()
    (s,) = data["sessions"]
    assert s["title"] == "Bio 101" and s["noteCount"] == 2 and s["durationMs"] == 2000
    assert s["recordings"][0]["hasAudio"] is True and s["recordings"][0]["transcriptSource"] is None
    detail = env.ui("GET", "/sessions/a1b2c3d4e5f6").json()
    assert detail["session"]["id"] == "a1b2c3d4e5f6"
    assert detail["folder"].endswith("_a1b2c3d4e5f6")
    assert env.ui("GET", "/sessions/nope").status_code == 404


def test_search_all_words_case_and_accent_insensitive(env):
    s = phone_session(notes=[
        {"id": "n1", "text": "Café au lait at the Krebs cycle lecture", "createdAt": T0, "recId": "r1", "offsetMs": 1000},
        {"id": "n2", "text": "Krebs only", "createdAt": T0},
    ])
    s["recordings"][0]["transcript"] = [
        {"s": 0, "e": 900, "t": "Welcome everyone."},
        {"s": 1000, "e": 1900, "t": "The KREBS cycle happens in the mitochondria, cafe or not."},
    ]
    env.put_session(s)
    res = env.ui("GET", "/search", params={"q": "krebs CAFÉ"}).json()
    texts = [(h["kind"], h["text"]) for h in res["hits"]]
    assert texts == [("note", "Café au lait at the Krebs cycle lecture"), ("speech", "The KREBS cycle happens in the mitochondria, cafe or not.")]
    note, speech = res["hits"]
    assert note["recId"] == "r1" and note["atMs"] == 1000 and note["label"] == "00:01"
    assert note["text"][note["matches"][0][0]:note["matches"][0][1]] == "Café"
    assert speech["atMs"] == 1000 and speech["line"] == 1
    assert [speech["text"][a:b] for a, b in speech["matches"]] == ["KREBS", "cafe"]
    assert env.ui("GET", "/search", params={"q": "krebs zebra"}).json()["hits"] == []
    assert env.ui("GET", "/search", params={"q": "   "}).json()["hits"] == []
    unlinked = env.ui("GET", "/search", params={"q": "only"}).json()["hits"][0]
    assert unlinked["atMs"] is None and unlinked["noteId"] == "n2"
    titles = env.ui("GET", "/search", params={"q": "bio 101"}).json()["hits"]
    assert [(h["kind"], h["text"]) for h in titles] == [("title", "Bio 101")]


def test_exports(env):
    s = phone_session()
    s["recordings"][0]["transcript"] = [{"s": 2050, "e": 5830, "t": "Ask not what your country can do for you."}]
    env.put_session(s)
    md = env.ui("GET", "/sessions/a1b2c3d4e5f6/export.md")
    assert md.status_code == 200 and "attachment" in md.headers["content-disposition"] and "Bio%20101.md" in md.headers["content-disposition"]
    text = md.text
    assert text.startswith("# Bio 101\n") and "## Transcript" in text and "- [00:02] Ask not what your country can do for you." in text
    srt = env.ui("GET", "/sessions/a1b2c3d4e5f6/export.srt").text
    assert srt == "1\n00:00:02,050 --> 00:00:05,830\nAsk not what your country can do for you.\n"
    txt = env.ui("GET", "/sessions/a1b2c3d4e5f6/export.txt").text
    assert "NOTES" in txt and "[00:01] Key point about mitochondria" in txt and "[00:02] Ask not" in txt
    assert env.ui("GET", "/sessions/a1b2c3d4e5f6/export.pdf").status_code == 404


def test_rename_survives_sync_until_phone_renames(env):
    env.put_session(phone_session())
    env.ui("PATCH", "/sessions/a1b2c3d4e5f6", json={"title": "Biology 101 – cells"})
    assert env.ui("GET", "/sessions/a1b2c3d4e5f6").json()["session"]["title"] == "Biology 101 – cells"
    # The phone syncs again with its old title: the desktop's name stays.
    env.put_session(phone_session(updated=T0 + 10))
    get = lambda: env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()["session"]["title"]
    assert get() == "Biology 101 – cells"
    # The phone renames it: the phone's newer name wins.
    env.put_session(phone_session(title="Bio lecture 3", updated=T0 + 20))
    assert get() == "Bio lecture 3"
    assert env.ui("PATCH", "/sessions/a1b2c3d4e5f6", json={"title": "  "}).status_code == 400


def test_edit_transcript_lines(env):
    s = phone_session()
    s["recordings"][0]["transcript"] = [{"s": 0, "e": 900, "t": "the crebs cycle"}]
    env.put_session(s)
    r = env.ui("PATCH", "/sessions/a1b2c3d4e5f6/recordings/r1/transcript/0", json={"text": "The Krebs cycle", "s": 0})
    assert r.status_code == 200 and r.json()["segment"]["t"] == "The Krebs cycle"
    rec = env.ui("GET", "/sessions/a1b2c3d4e5f6").json()["summary"]["recordings"][0]
    assert rec["transcriptSource"] == "phone-edited" and rec["edited"] is True
    # The correction survives the phone sending its own transcript again.
    env.put_session(s)
    body = env.client.get("/api/v1/sessions/a1b2c3d4e5f6", headers=env.auth).json()
    assert body["session"]["recordings"][0]["transcript"][0]["t"] == "The Krebs cycle"
    assert env.ui("PATCH", "/sessions/a1b2c3d4e5f6/recordings/r1/transcript/5", json={"text": "x"}).status_code == 400
    assert env.ui("PATCH", "/sessions/a1b2c3d4e5f6/recordings/r1/transcript/0", json={"text": "x", "s": 99}).status_code == 400


def test_audio_playback_supports_ranges(env):
    env.sync_with_audio()
    data = aac_bytes(env.tmp, 2.0)
    r = env.ui("GET", "/sessions/a1b2c3d4e5f6/audio/r1")
    assert r.status_code == 200 and r.content == data and r.headers["content-type"] == "audio/mp4"
    r = env.ui("GET", "/sessions/a1b2c3d4e5f6/audio/r1", headers={"Range": "bytes=10-19"})
    assert r.status_code == 206 and r.content == data[10:20]
    assert r.headers["content-range"] == f"bytes 10-19/{len(data)}"
    assert env.ui("GET", "/sessions/a1b2c3d4e5f6/audio/r9").status_code == 404


def _backup_folder(root, sid="cccccccccccc", title="From backup", audio=b""):
    s = phone_session(sid, title=title)
    s["recordings"][0]["transcript"] = [{"s": 0, "e": 1000, "t": "backup transcript"}]
    folder = root / f"2026-09-21_10-00_{sid}"
    folder.mkdir(parents=True)
    (folder / "session.json").write_text(json.dumps(s))
    (folder / "notes.md").write_text("# notes")
    (folder / "recording-1.m4a").write_bytes(audio)
    return folder, s


def test_import_backup_folder_by_path(env):
    backup = env.tmp / "Phone Backup"
    data = aac_bytes(env.tmp, 2.0)
    _backup_folder(backup, "cccccccccccc", audio=data)
    _backup_folder(backup, "dddddddddddd", title="Second", audio=data)
    (backup / "not-a-session").mkdir()
    r = env.ui("POST", "/import/path", json={"path": str(backup)})
    assert r.status_code == 200, r.text
    assert r.json()["added"] == 2
    ids = {s["id"] for s in env.ui("GET", "/sessions").json()["sessions"]}
    assert ids == {"cccccccccccc", "dddddddddddd"}
    lib_folder = next(env.library.glob("*_cccccccccccc"))
    assert (lib_folder / "recording-1.m4a").read_bytes() == data
    # Importing again changes nothing.
    assert env.ui("POST", "/import/path", json={"path": str(backup)}).json()["unchanged"] == 2
    assert env.ui("POST", "/import/path", json={"path": str(env.tmp / "missing")}).status_code == 400


def test_import_zip_upload(env):
    data = aac_bytes(env.tmp, 2.0)
    src = env.tmp / "zipsrc"
    _backup_folder(src / "AudioCool", "eeeeeeeeeeee", audio=data)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for p in src.rglob("*"):
            if p.is_file():
                z.write(p, p.relative_to(src))
        z.writestr("../evil.txt", "nope")
        z.writestr("AudioCool/x/../../escape/session.json", "{}")
    r = env.ui("POST", "/import/zip", content=buf.getvalue(), headers={"Content-Type": "application/zip"})
    assert r.status_code == 200, r.text
    assert r.json()["added"] == 1
    assert not (env.tmp / "evil.txt").exists()
    folder = next(env.library.glob("*_eeeeeeeeeeee"))
    assert (folder / "recording-1.m4a").read_bytes() == data
    assert env.ui("POST", "/import/zip", content=b"not a zip").status_code == 400


def test_settings_change_library(env):
    env.sync_with_audio()
    other = env.tmp / "other-library"
    r = env.ui("PUT", "/settings", json={"library": str(other), "name": "Study PC", "defaultModel": "fake-fast"})
    assert r.status_code == 200, r.text
    assert r.json()["library"] == str(other)
    assert env.ui("GET", "/sessions").json()["sessions"] == []
    assert env.client.get("/api/v1/ping", headers=env.auth).json()["name"] == "Study PC"
    models = env.client.get("/api/v1/ping", headers=env.auth).json()["models"]
    assert [m["id"] for m in models if m["default"]] == ["fake-fast"]
    assert env.ui("PUT", "/settings", json={"defaultModel": "nope"}).status_code == 400
    # Pointing the library at a folder with the backup layout uses it directly.
    shutil.copytree(env.library, other, dirs_exist_ok=True)
    env.ui("PUT", "/settings", json={"library": str(other)})
    assert [s["id"] for s in env.ui("GET", "/sessions").json()["sessions"]] == ["a1b2c3d4e5f6"]


def test_delete_session(env):
    env.sync_with_audio()
    assert env.ui("DELETE", "/sessions/a1b2c3d4e5f6").status_code == 200
    assert env.ui("GET", "/sessions").json()["sessions"] == []
    assert not list(env.library.glob("*_a1b2c3d4e5f6"))
