"""Acts like the AudioCool phone app against a running AudioCool Desktop (for trying the API).

    .venv/bin/python tools/fake_phone.py --url http://localhost:8765 --token ABCD2345 \\
        --title "Bio 101" --audio lecture.m4a --note 12.5 "Key point" --note 95 "Exam question"

It sends the session (PUT /api/v1/sessions/{id}), uploads the audio files the desktop asks for,
starts transcription and waits for it, then prints the transcript the desktop sends back.
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def call(base: str, token: str, method: str, path: str, body=None, data: bytes | None = None, timeout: float = 600):
    headers = {"Authorization": f"Bearer {token}"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else None)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8765")
    p.add_argument("--token", required=True, help="the pairing code")
    p.add_argument("--title", default="Test session")
    p.add_argument("--id", default=None, help="session id (default: random, 12 hex digits like the phone)")
    p.add_argument("--audio", action="append", default=[], help="audio file(s); one recording each")
    p.add_argument("--note", nargs=2, action="append", default=[], metavar=("SECONDS", "TEXT"),
                   help="a note linked to the first recording at SECONDS (repeatable)")
    p.add_argument("--free-note", action="append", default=[], help="a note not linked to the audio")
    p.add_argument("--created", type=float, default=None, help="session start as epoch seconds (default: now - audio length)")
    p.add_argument("--model", default=None, help="model id (default: the desktop's default)")
    p.add_argument("--no-transcribe", action="store_true")
    args = p.parse_args()

    from audiocool_desktop.audio import probe_duration_ms

    status, ping = call(args.url, args.token, "GET", "/api/v1/ping")
    if status != 200:
        print(f"ping failed: {status} {ping}", file=sys.stderr)
        return 1
    print(f"Connected to {ping['name']} ({ping['app']} {ping['version']}); models: {', '.join(m['id'] for m in ping['models'])}")

    sid = args.id or secrets.token_hex(6)
    files = [Path(a) for a in args.audio]
    durations = [probe_duration_ms(f) for f in files]
    start = int((args.created or time.time() - sum(durations) / 1000 - 60) * 1000)
    recordings, t = [], start
    for i, (f, d) in enumerate(zip(files, durations), 1):
        recordings.append({"id": f"r{i}", "file": f"recording-{i}{f.suffix}", "createdAt": t, "durationMs": d})
        t += d + 60_000
    notes = [{"id": f"n{i}", "text": text, "createdAt": start + int(float(sec) * 1000) + 3000, "recId": "r1", "offsetMs": int(float(sec) * 1000)}
             for i, (sec, text) in enumerate(args.note, 1)]
    notes += [{"id": f"u{i}", "text": text, "createdAt": t + i * 1000} for i, text in enumerate(args.free_note, 1)]
    session = {"version": 1, "id": sid, "title": args.title, "createdAt": start, "updatedAt": t, "recordings": recordings, "notes": notes}
    sizes = {r["file"]: f.stat().st_size for r, f in zip(recordings, files)}

    status, body = call(args.url, args.token, "PUT", f"/api/v1/sessions/{sid}", {"session": session, "files": sizes})
    print(f"PUT session {sid}: {status} {body}")
    for name in body.get("needed", []):
        f = files[[r["file"] for r in recordings].index(name)]
        t0 = time.monotonic()
        status, _ = call(args.url, args.token, "PUT", f"/api/v1/sessions/{sid}/files/{name}", data=f.read_bytes())
        print(f"  uploaded {name} ({f.stat().st_size / 1e6:.1f} MB) in {time.monotonic() - t0:.1f} s: {status}")
    if args.no_transcribe or not recordings:
        return 0

    status, body = call(args.url, args.token, "POST", f"/api/v1/sessions/{sid}/transcribe", {"model": args.model, "recordingIds": None})
    print(f"transcribe: {status} {body}")
    ids = {j["id"] for j in body["jobs"]}
    while True:
        status, body = call(args.url, args.token, "GET", f"/api/v1/sessions/{sid}")
        jobs = [j for j in body["jobs"] if j["id"] in ids]
        print("  " + ", ".join(f"{j['recordingId']}: {j['status']} {j['progress'] * 100:.0f}%" for j in jobs), end="\r")
        if all(j["status"] in ("done", "error") for j in jobs):
            break
        time.sleep(1)
    print()
    for j in jobs:
        if j["status"] == "error":
            print(f"  {j['recordingId']} failed: {j['error']}")
    for rec in body["session"]["recordings"]:
        print(f"\n{rec['file']} — {body['transcriptModels'].get(rec['id'], 'no desktop transcript')}")
        for seg in rec.get("transcript", []):
            print(f"  [{seg['s'] / 1000:7.2f} – {seg['e'] / 1000:7.2f}] {seg['t']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
