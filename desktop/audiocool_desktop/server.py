"""HTTP server: the phone API (/api/v1, token required) and the web UI (localhost only).

Phone API: see README.md ("API"). Errors are JSON ``{"error": "..."}`` with 400 (bad input),
401 (missing/wrong token), 404 (unknown session or file).

Web UI: ``/`` serves a single-page app from ``static/``; it talks to ``/ui/api/...``. Both are
only served to this computer (loopback or one of its own addresses, with a matching Host
header), and requests that change something must carry an ``X-AudioCool: 1`` header, which a
cross-site page can't add without a CORS preflight that this server never approves.
"""

from __future__ import annotations

import io
import json
import logging
import os
import secrets
import shutil
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import quote

import segno
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.requests import ClientDisconnect

from . import APP_ID, API_VERSION, __version__
from . import models_cache, netinfo
from . import sessionfmt as fmt
from .app import App
from .library import PHONE_MODEL, Entry, NotFound
from .search import search_entries
from .sessionfmt import AUDIO_NAME, SESSION_ID, BadInput

log = logging.getLogger(__name__)

STATIC_DIR = Path(__file__).parent / "static"
JSON_LIMIT = 64 * 1024 * 1024
WRITE_BLOCK = 1 << 20
SAFE_METHODS = ("GET", "HEAD", "OPTIONS")


def error(status: int, message: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"error": message}, status_code=status, headers=headers)


def check_session_id(sid: str) -> None:
    if not SESSION_ID.fullmatch(sid):
        raise BadInput("invalid session id")


async def read_json(request: Request, limit: int = JSON_LIMIT, required: bool = True) -> Any:
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > limit:
            raise BadInput("request body too large")
    if not body:
        if required:
            raise BadInput("expected a JSON body")
        return None
    try:
        return json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise BadInput(f"invalid JSON: {e}") from e


def _fsync_close(f) -> None:
    f.flush()
    os.fsync(f.fileno())
    f.close()


async def receive_to_file(request: Request, path: Path) -> int:
    """Streams a request body into ``path`` without holding it in memory; returns its size."""
    received = 0
    f = open(path, "wb")
    try:
        buf = bytearray()
        async for chunk in request.stream():
            buf += chunk
            received += len(chunk)
            if len(buf) >= WRITE_BLOCK:
                data, buf = bytes(buf), bytearray()
                await run_in_threadpool(f.write, data)
        if buf:
            await run_in_threadpool(f.write, bytes(buf))
        await run_in_threadpool(_fsync_close, f)
    except BaseException:
        f.close()
        path.unlink(missing_ok=True)
        raise
    expected = request.headers.get("content-length")
    if expected is not None and expected.isdigit() and int(expected) != received:
        path.unlink(missing_ok=True)
        raise BadInput(f"upload incomplete: got {received} of {expected} bytes")
    return received


# ---------------------------------------------------------------------------------------------
# JSON shapes


def api_job(job: dict) -> dict:
    return {k: job[k] for k in ("id", "recordingId", "model", "status", "progress", "error")}


def api_session(entry: Entry) -> dict:
    """session.json as the phone knows it, with the desktop's transcripts in place."""
    s = json.loads(json.dumps(entry.session))
    for rec in s.get("recordings", []):
        rec.pop("transcriptModel", None)
    return s


def recording_info(app: App, entry: Entry, rec: dict) -> dict:
    owned = entry.desktop_transcript(rec["id"])
    if owned is not None:
        source = "desktop" if owned["model"] != PHONE_MODEL else "phone-edited"
    elif rec.get("transcript") is not None:
        source = "phone"
    else:
        source = None
    return {
        "id": rec["id"],
        "file": rec["file"],
        "createdAt": rec.get("createdAt", 0),
        "durationMs": entry.audio_duration_ms(rec),
        "hasAudio": entry.audio_file(rec) is not None,
        "transcriptSource": source,
        "model": owned["model"] if owned else None,
        "modelName": (owned.get("modelName") or app.registry.name_of(owned["model"])) if owned else ("Phone" if source == "phone" else None),
        "edited": bool(owned and owned.get("edited")),
        "transcribedAt": owned.get("createdAt") if owned else None,
        "device": owned.get("device") if owned else None,
        "segments": len(rec.get("transcript") or []),
    }


def session_summary(app: App, entry: Entry, jobs: list[dict]) -> dict:
    s = entry.session
    recs = [recording_info(app, entry, r) for r in s.get("recordings", [])]
    active = [j for j in jobs if j["status"] in ("queued", "running")]
    return {
        "id": entry.id,
        "title": s.get("title", ""),
        "createdAt": s.get("createdAt", 0),
        "updatedAt": s.get("updatedAt", 0),
        "durationMs": sum(r["durationMs"] for r in recs),
        "noteCount": len(s.get("notes", [])),
        "recordings": recs,
        "activeJobs": active,
        "lastError": next((j["error"] for j in reversed(jobs) if j["status"] == "error" and j["error"] != "Cancelled"), None),
    }


def qr_svg(content: str) -> str:
    buf = io.BytesIO()
    segno.make(content, error="m").save(buf, kind="svg", scale=6, border=3, dark="#000", light="#fff", xmldecl=False, svgclass="qr")
    return buf.getvalue().decode()


def pair_info(app: App, port: int) -> dict:
    token = app.config.token
    urls = [f"http://{ip}:{port}" for ip in netinfo.lan_addresses()]
    codes = []
    for url in urls:
        content = f"audiocool://pair?url={quote(url, safe='')}&token={quote(token, safe='')}"
        codes.append({"url": url, "content": content, "svg": qr_svg(content)})
    return {"token": token, "urls": urls, "codes": codes, "port": port, "name": app.config.name}


# ---------------------------------------------------------------------------------------------


def create_app(app: App, port: int = 8765) -> FastAPI:
    api = FastAPI(title="AudioCool Desktop", version=__version__, docs_url=None, redoc_url=None, openapi_url=None)
    api.state.app = app
    lib = app.library
    local_addrs = netinfo.all_local_addresses()
    failures: dict[str, list[float]] = {}

    def is_local(request: Request) -> bool:
        client = request.client.host if request.client else ""
        if client.startswith("::ffff:"):
            client = client[7:]
        if not (client.startswith("127.") or client in local_addrs or client == "testclient"):
            return False
        host = (request.headers.get("host") or "").rsplit(":", 1)[0].strip("[]").lower()
        node = os.uname().nodename.lower()
        return host in {"localhost", "127.0.0.1", "::1", "testserver", node, f"{node}.local", app.config.name.lower()} | local_addrs

    @api.middleware("http")
    async def guard(request: Request, call_next):
        path = request.url.path
        if path.startswith("/api/"):
            auth = request.headers.get("authorization", "")
            scheme, _, token = auth.partition(" ")
            if scheme.lower() != "bearer" or not token or not app.config.check_token(token.strip()):
                ip = request.client.host if request.client else "?"
                recent = [t for t in failures.get(ip, []) if time.monotonic() - t < 60]
                recent.append(time.monotonic())
                failures[ip] = recent[-50:]
                if len(recent) > 10:  # slow down guessing
                    import asyncio

                    await asyncio.sleep(1.0)
                return error(401, "missing or wrong pairing code", {"WWW-Authenticate": "Bearer"})
            return await call_next(request)
        if not is_local(request):
            return PlainTextResponse(
                f"The AudioCool Desktop web UI only opens on this computer: http://localhost:{port}/\n", status_code=403
            )
        if request.method not in SAFE_METHODS and request.headers.get("x-audiocool") != "1":
            return error(403, "missing X-AudioCool header")
        response = await call_next(request)
        if path == "/" or path.startswith("/ui/api/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

    @api.exception_handler(BadInput)
    async def _bad(_: Request, exc: BadInput):
        return error(400, str(exc))

    @api.exception_handler(NotFound)
    async def _missing(_: Request, exc: NotFound):
        return error(404, str(exc))

    @api.exception_handler(RequestValidationError)
    async def _invalid(_: Request, exc: RequestValidationError):
        return error(400, "invalid request")

    @api.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException):
        return error(exc.status_code, str(exc.detail) if exc.detail else "error")

    def entry_or_404(sid: str) -> Entry:
        check_session_id(sid)
        entry = lib.get(sid)
        if entry is None:
            raise NotFound(f"unknown session {sid}")
        return entry

    def submit_jobs(entry: Entry, body: Any) -> list[dict]:
        if body is None:
            body = {}
        if not isinstance(body, dict):
            raise BadInput("expected a JSON object")
        model = body.get("model")
        if model is None:
            model = app.registry.default_id
        if not isinstance(model, str) or app.registry.get(model) is None:
            raise BadInput(f"unknown model {model!r}; see /api/v1/ping for the list")
        rec_ids = body.get("recordingIds")
        recs = entry.session.get("recordings", [])
        if rec_ids is None:
            targets = [r for r in recs if entry.has_audio(r)]
            if not targets:
                raise BadInput("none of this session's recordings has its audio uploaded yet")
        else:
            if not isinstance(rec_ids, list) or not all(isinstance(r, str) for r in rec_ids):
                raise BadInput("recordingIds must be a list of recording ids or null")
            targets = []
            for rid in dict.fromkeys(rec_ids):
                rec = entry.recording(rid)
                if rec is None:
                    raise BadInput(f"unknown recording {rid!r}")
                if not entry.has_audio(rec):
                    raise BadInput(f"the audio for recording {rid!r} ({rec['file']}) hasn't been uploaded yet")
                targets.append(rec)
        return [app.jobs.submit(entry.id, r["id"], model) for r in targets]

    # ---------------------------------------------------------------------------------------
    # Phone API

    @api.get("/api/v1/ping")
    def ping():
        return {"app": APP_ID, "version": API_VERSION, "name": app.config.name, "models": app.registry.models()}

    @api.get("/api/v1/sessions")
    def list_sessions():
        out = []
        for entry in sorted(lib.entries(), key=lambda e: e.session.get("createdAt", 0), reverse=True):
            models = entry.transcript_models()
            s = entry.session
            out.append({
                "id": entry.id,
                "title": s.get("title", ""),
                "createdAt": s.get("createdAt", 0),
                "updatedAt": s.get("updatedAt", 0),
                "recordings": [
                    {"id": r["id"], "durationMs": r.get("durationMs", 0), "transcribed": r["id"] in models, "model": models.get(r["id"])}
                    for r in s.get("recordings", [])
                ],
            })
        return {"sessions": out}

    @api.put("/api/v1/sessions/{sid}")
    async def put_session(sid: str, request: Request):
        check_session_id(sid)
        body = await read_json(request)
        if not isinstance(body, dict) or "session" not in body:
            raise BadInput('expected {"session": {...}, "files": {...}}')
        session = fmt.validate_session(body["session"], expected_id=sid)
        files = fmt.validate_files(body.get("files", {}))
        needed = await run_in_threadpool(lib.put_session, session, files)
        return {"needed": needed}

    @api.get("/api/v1/sessions/{sid}")
    def get_session(sid: str):
        entry = entry_or_404(sid)
        return {
            "session": api_session(entry),
            "transcriptModels": entry.transcript_models(),
            "jobs": [api_job(j) for j in app.jobs.for_session(sid)],
        }

    @api.put("/api/v1/sessions/{sid}/files/{name}", status_code=204)
    async def put_file(sid: str, name: str, request: Request):
        check_session_id(sid)
        if not AUDIO_NAME.fullmatch(name):
            raise BadInput("file names must look like recording-N.aac or recording-N.m4a")
        entry = await run_in_threadpool(lib.get, sid)
        if entry is None:
            raise NotFound(f"unknown session {sid}")
        if not any(r["file"] == name for r in entry.session.get("recordings", [])):
            raise NotFound(f"{name} is not a file of this session")
        tmp = entry.folder / f".{name}.{secrets.token_hex(6)}.part"
        try:
            await receive_to_file(request, tmp)
        except ClientDisconnect:
            return Response(status_code=400)
        await run_in_threadpool(lib.commit_audio, sid, name, tmp)
        return Response(status_code=204)

    @api.post("/api/v1/sessions/{sid}/transcribe", status_code=202)
    async def transcribe(sid: str, request: Request):
        entry = entry_or_404(sid)
        body = await read_json(request, required=False)
        jobs = submit_jobs(entry, body)
        return JSONResponse({"jobs": [{k: j[k] for k in ("id", "recordingId", "model", "status")} for j in jobs]}, status_code=202)

    @api.api_route("/api/{rest:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
    def api_unknown(rest: str):
        raise NotFound("no such API endpoint")

    # ---------------------------------------------------------------------------------------
    # Web UI API (local only)

    @api.get("/ui/api/info")
    def ui_info():
        reg = app.registry
        models = reg.describe()
        for m in models:
            spec = reg.get(m["id"])
            m["status"] = models_cache.status(spec.repos) if spec and spec.repos else None
        return {
            "app": APP_ID,
            "version": __version__,
            "apiVersion": API_VERSION,
            "name": app.config.name,
            "library": str(lib.root),
            "libraryOverridden": app.config.library_overridden,
            "models": models,
            "defaultModel": reg.default_id,
            "cuda": reg.cuda_available,
            "gpu": reg.gpu_name,
            "port": port,
            "activeJobs": app.jobs.active_count(),
        }

    @api.get("/ui/api/sessions")
    def ui_sessions():
        entries = sorted(lib.entries(), key=lambda e: e.session.get("createdAt", 0), reverse=True)
        jobs_by_session: dict[str, list[dict]] = {}
        for j in reversed(app.jobs.all(limit=500)):
            jobs_by_session.setdefault(j["sessionId"], []).append(j)
        return {"sessions": [session_summary(app, e, jobs_by_session.get(e.id, [])) for e in entries], "library": str(lib.root)}

    def ui_detail(entry: Entry) -> dict:
        jobs = app.jobs.for_session(entry.id)
        return {
            "session": entry.session,
            "summary": session_summary(app, entry, jobs),
            "jobs": jobs,
            "folder": str(entry.folder),
        }

    @api.get("/ui/api/sessions/{sid}")
    def ui_session(sid: str):
        return ui_detail(entry_or_404(sid))

    @api.patch("/ui/api/sessions/{sid}")
    async def ui_rename(sid: str, request: Request):
        entry_or_404(sid)
        body = await read_json(request)
        if not isinstance(body, dict) or not isinstance(body.get("title"), str):
            raise BadInput("expected {\"title\": \"...\"}")
        await run_in_threadpool(lib.rename, sid, body["title"])
        return ui_detail(entry_or_404(sid))

    @api.delete("/ui/api/sessions/{sid}")
    def ui_delete(sid: str):
        entry_or_404(sid)
        app.jobs.forget_session(sid)
        lib.delete(sid)
        return {"deleted": sid}

    @api.patch("/ui/api/sessions/{sid}/recordings/{rid}/transcript/{index}")
    async def ui_edit_line(sid: str, rid: str, index: int, request: Request):
        entry_or_404(sid)
        body = await read_json(request)
        if not isinstance(body, dict) or not isinstance(body.get("text"), str):
            raise BadInput("expected {\"text\": \"...\"}")
        start = body.get("s")
        if start is not None and (isinstance(start, bool) or not isinstance(start, int)):
            raise BadInput("s must be a number")
        seg = await run_in_threadpool(lib.edit_transcript_line, sid, rid, index, body["text"], start)
        return {"segment": seg}

    @api.post("/ui/api/sessions/{sid}/transcribe")
    async def ui_transcribe(sid: str, request: Request):
        entry = entry_or_404(sid)
        body = await read_json(request, required=False)
        return {"jobs": submit_jobs(entry, body)}

    @api.get("/ui/api/sessions/{sid}/audio/{rid}")
    def ui_audio(sid: str, rid: str):
        entry = entry_or_404(sid)
        rec = entry.recording(rid)
        if rec is None:
            raise NotFound("unknown recording")
        path = entry.audio_file(rec)
        if path is None:
            raise NotFound("this recording's audio isn't on the desktop yet")
        media = "audio/mp4" if path.suffix == ".m4a" else "audio/aac"
        return FileResponse(path, media_type=media, headers={"Cache-Control": "no-cache"})

    @api.get("/ui/api/sessions/{sid}/export.{ext}")
    def ui_export(sid: str, ext: str, recording: str | None = None, download: int = 1):
        entry = entry_or_404(sid)
        s = entry.session
        base = fmt.safe_filename(s.get("title", ""), entry.id)
        names = entry.model_names()
        if ext == "md":
            body, media, filename = fmt.session_markdown(s, True, names), "text/markdown", f"{base}.md"
        elif ext == "txt":
            body, media, filename = fmt.session_text(s, names), "text/plain", f"{base}.txt"
        elif ext == "srt":
            recs = s.get("recordings", [])
            rec = entry.recording(recording) if recording else next((r for r in recs if r.get("transcript")), recs[0] if recs else None)
            if rec is None:
                raise NotFound("no such recording")
            if not rec.get("transcript"):
                raise BadInput("this recording has no transcript yet")
            n = fmt.recording_number(s, rec["id"])
            body, media = fmt.recording_srt(rec), "application/x-subrip"
            filename = f"{base}.srt" if len(recs) == 1 else f"{base} - recording {n}.srt"
        else:
            raise NotFound("export formats: md, txt, srt")
        disposition = "attachment" if download else "inline"
        headers = {"Content-Disposition": f"{disposition}; filename*=UTF-8''{quote(filename)}"}
        return Response(body.encode("utf-8"), media_type=f"{media}; charset=utf-8", headers=headers)

    @api.get("/ui/api/search")
    def ui_search(q: str = "", limit: int = 300):
        return search_entries(lib.entries(), q, limit=max(1, min(limit, 2000)))

    @api.get("/ui/api/jobs")
    def ui_jobs():
        jobs = app.jobs.all(limit=300)
        titles: dict[str, Any] = {}
        for j in jobs:
            sid = j["sessionId"]
            if sid not in titles:
                titles[sid] = lib.get(sid)
            entry = titles[sid]
            j["sessionTitle"] = entry.session.get("title") if entry else None
            j["recordingNumber"] = fmt.recording_number(entry.session, j["recordingId"]) if entry else 0
            j["recordingCount"] = len(entry.session.get("recordings", [])) if entry else 0
            j["modelName"] = app.registry.name_of(j["model"])
        return {"jobs": jobs}

    @api.delete("/ui/api/jobs/{jid}")
    def ui_cancel(jid: str):
        if not app.jobs.cancel(jid):
            raise NotFound("no such queued or running job")
        return {"cancelled": jid}

    @api.get("/ui/api/pair")
    def ui_pair():
        return pair_info(app, port)

    @api.post("/ui/api/pair/reset")
    def ui_pair_reset():
        app.config.reset_token()
        return pair_info(app, port)

    @api.post("/ui/api/import/path")
    async def ui_import_path(request: Request):
        body = await read_json(request)
        if not isinstance(body, dict) or not isinstance(body.get("path"), str) or not body["path"].strip():
            raise BadInput("expected {\"path\": \"/path/to/backup\"}")
        path = Path(os.path.expanduser(body["path"].strip()))
        if not path.exists():
            raise BadInput(f"{path} doesn't exist")
        return await run_in_threadpool(lib.import_tree, path)

    @api.post("/ui/api/import/zip")
    async def ui_import_zip(request: Request):
        work = Path(tempfile.mkdtemp(prefix="audiocool-import-"))
        try:
            archive = work / "upload.zip"
            await receive_to_file(request, archive)
            return await run_in_threadpool(_import_zip, lib, archive, work / "x")
        finally:
            shutil.rmtree(work, ignore_errors=True)

    @api.get("/ui/api/settings")
    def ui_settings():
        return {"library": str(lib.root), "name": app.config.name, "defaultModel": app.registry.default_id,
                "libraryOverridden": app.config.library_overridden}

    @api.put("/ui/api/settings")
    async def ui_settings_put(request: Request):
        body = await read_json(request)
        if not isinstance(body, dict):
            raise BadInput("expected an object")
        if "name" in body:
            if not isinstance(body["name"], str) or not body["name"].strip():
                raise BadInput("the computer name can't be empty")
            app.config.update(name=body["name"].strip()[:60])
        if "defaultModel" in body:
            model = body["defaultModel"]
            if model is not None and app.registry.get(model) is None:
                raise BadInput(f"unknown model {model!r}")
            app.config.update(defaultModel=model)
            app.registry.preferred_default = model
        if "library" in body:
            if not isinstance(body["library"], str) or not body["library"].strip():
                raise BadInput("the library folder can't be empty")
            target = Path(os.path.expanduser(body["library"].strip()))
            try:
                target.mkdir(parents=True, exist_ok=True)
            except OSError as e:
                raise BadInput(f"can't use {target}: {e}") from e
            await run_in_threadpool(app.set_library, target)
        return ui_settings()

    @api.api_route("/ui/{rest:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
    def ui_unknown(rest: str):
        raise NotFound("not found")

    # ---------------------------------------------------------------------------------------
    # The single-page app

    @api.get("/")
    def index():
        return FileResponse(STATIC_DIR / "index.html", media_type="text/html")

    api.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return api


def _import_zip(lib, archive: Path, dest: Path) -> dict:
    """Unpacks only what a session needs (session.json, notes.md, audio, sidecar), safely."""
    allowed = {"session.json", "notes.md", "audiocool-desktop.json"}
    try:
        zf = zipfile.ZipFile(archive)
    except zipfile.BadZipFile as e:
        raise BadInput("that isn't a zip file") from e
    count = 0
    with zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            parts = [p for p in info.filename.replace("\\", "/").split("/") if p not in ("", ".")]
            if not parts or any(p == ".." or p.startswith(".") for p in parts) or len(parts) > 6:
                continue
            name = parts[-1]
            if name not in allowed and not AUDIO_NAME.fullmatch(name):
                continue
            target = dest.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, WRITE_BLOCK)
            count += 1
    if count == 0:
        raise BadInput("no AudioCool sessions in that zip (looking for folders with session.json)")
    return lib.import_tree(dest, max_depth=6)
