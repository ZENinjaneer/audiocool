"""Transcription jobs: a queue in SQLite (so it survives restarts) and one background worker.

Jobs run one at a time, oldest first. A job that was running when the server stopped is queued
again on the next start. Statuses are those of the API: queued, running, done, error (a
cancelled job ends as an error with the message "Cancelled").
"""

from __future__ import annotations

import logging
import secrets
import sqlite3
import threading
import time
import traceback
from pathlib import Path
from typing import Callable

from .sessionfmt import now_ms

log = logging.getLogger(__name__)

ACTIVE = ("queued", "running")
KEEP_FINISHED = 500

Runner = Callable[[dict, Callable[[float], None], Callable[[], bool]], dict | None]


class JobQueue:
    def __init__(self, db_path: Path, runner: Runner, idle_callback: Callable[[], None] | None = None, idle_every_s: float = 30.0):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.runner = runner
        self.idle_callback = idle_callback
        self.idle_every_s = idle_every_s
        self._db = sqlite3.connect(str(self.db_path), check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._progress: dict[str, float] = {}
        self._cancel: set[str] = set()
        self._running: str | None = None
        self._idle = threading.Event()
        self._idle.set()
        self._init_db()

    def _init_db(self) -> None:
        with self._lock:
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute(
                """CREATE TABLE IF NOT EXISTS jobs (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT UNIQUE NOT NULL,
                    session_id TEXT NOT NULL,
                    recording_id TEXT NOT NULL,
                    model TEXT NOT NULL,
                    status TEXT NOT NULL,
                    progress REAL NOT NULL DEFAULT 0,
                    error TEXT,
                    device TEXT,
                    info TEXT,
                    created_at INTEGER NOT NULL,
                    started_at INTEGER,
                    finished_at INTEGER
                )"""
            )
            self._db.execute("CREATE INDEX IF NOT EXISTS jobs_session ON jobs(session_id)")
            # Whatever was running when the server stopped starts over.
            n = self._db.execute(
                "UPDATE jobs SET status='queued', progress=0, started_at=NULL WHERE status='running'"
            ).rowcount
            if n:
                log.info("Re-queued %d job(s) interrupted by a restart", n)

    # -- rows -------------------------------------------------------------------------------

    def _row(self, r: sqlite3.Row) -> dict:
        progress = self._progress.get(r["id"], r["progress"]) if r["status"] == "running" else r["progress"]
        return {
            "id": r["id"],
            "sessionId": r["session_id"],
            "recordingId": r["recording_id"],
            "model": r["model"],
            "status": r["status"],
            "progress": round(float(progress), 4),
            "error": r["error"],
            "device": r["device"],
            "info": r["info"],
            "createdAt": r["created_at"],
            "startedAt": r["started_at"],
            "finishedAt": r["finished_at"],
        }

    def get(self, job_id: str) -> dict | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            return self._row(r) if r else None

    def for_session(self, session_id: str) -> list[dict]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM jobs WHERE session_id=? ORDER BY seq", (session_id,)).fetchall()
            return [self._row(r) for r in rows]

    def all(self, limit: int = 300) -> list[dict]:
        """Active jobs first (in run order), then the most recent finished ones."""
        with self._lock:
            active = self._db.execute(
                "SELECT * FROM jobs WHERE status IN ('queued','running') ORDER BY (status='running') DESC, seq"
            ).fetchall()
            done = self._db.execute(
                "SELECT * FROM jobs WHERE status NOT IN ('queued','running') ORDER BY finished_at DESC, seq DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [self._row(r) for r in active] + [self._row(r) for r in done]

    def active_count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running')").fetchone()[0]

    # -- changes ----------------------------------------------------------------------------

    def submit(self, session_id: str, recording_id: str, model: str) -> dict:
        """Queues a job, or returns the identical one already queued or running."""
        with self._lock:
            r = self._db.execute(
                "SELECT * FROM jobs WHERE session_id=? AND recording_id=? AND model=? AND status IN ('queued','running') ORDER BY seq LIMIT 1",
                (session_id, recording_id, model),
            ).fetchone()
            if r is not None:
                return self._row(r)
            job_id = secrets.token_hex(6)
            self._db.execute(
                "INSERT INTO jobs (id, session_id, recording_id, model, status, progress, created_at) VALUES (?,?,?,?, 'queued', 0, ?)",
                (job_id, session_id, recording_id, model, now_ms()),
            )
            self._prune()
        self._wake.set()
        return self.get(job_id)

    def cancel(self, job_id: str) -> bool:
        with self._lock:
            job = self.get(job_id)
            if job is None or job["status"] not in ACTIVE:
                return False
            if job["status"] == "queued":
                self._finish(job_id, "error", "Cancelled")
            else:
                self._cancel.add(job_id)
            return True

    def forget_session(self, session_id: str) -> None:
        with self._lock:
            for job in self.for_session(session_id):
                if job["status"] == "running":
                    self._cancel.add(job["id"])
            self._db.execute("DELETE FROM jobs WHERE session_id=? AND status != 'running'", (session_id,))

    def _finish(self, job_id: str, status: str, error: str | None, device: str | None = None, info: str | None = None) -> None:
        with self._lock:
            self._db.execute(
                "UPDATE jobs SET status=?, error=?, progress=?, finished_at=?, device=COALESCE(?, device), info=COALESCE(?, info) WHERE id=?",
                (status, error, 1.0 if status == "done" else self._progress.get(job_id, 0.0), now_ms(), device, info, job_id),
            )
            self._progress.pop(job_id, None)
            self._cancel.discard(job_id)

    def _prune(self) -> None:
        self._db.execute(
            "DELETE FROM jobs WHERE status NOT IN ('queued','running') AND seq NOT IN "
            "(SELECT seq FROM jobs WHERE status NOT IN ('queued','running') ORDER BY seq DESC LIMIT ?)",
            (KEEP_FINISHED,),
        )

    # -- worker -----------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="transcription-worker", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        """Stops the worker; a job it interrupts is queued again for the next start."""
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout)
            self._thread = None

    def close(self) -> None:
        self.stop()
        with self._lock:
            self._db.close()

    def wait_idle(self, timeout: float = 30.0) -> bool:
        """For tests: waits until nothing is queued or running."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.active_count() == 0 and self._idle.is_set():
                return True
            time.sleep(0.02)
        return False

    def _next(self) -> dict | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY seq LIMIT 1").fetchone()
            if r is None:
                return None
            self._db.execute("UPDATE jobs SET status='running', progress=0, started_at=?, error=NULL WHERE id=?", (now_ms(), r["id"]))
            self._running = r["id"]
            self._progress[r["id"]] = 0.0
            self._idle.clear()
            return self.get(r["id"])

    def _loop(self) -> None:
        last_idle_check = time.monotonic()
        while not self._stop.is_set():
            job = self._next()
            if job is None:
                self._idle.set()
                self._wake.wait(timeout=5.0)
                self._wake.clear()
                if self.idle_callback and time.monotonic() - last_idle_check > self.idle_every_s:
                    last_idle_check = time.monotonic()
                    try:
                        self.idle_callback()
                    except Exception:
                        log.exception("Idle callback failed")
                continue
            self._run(job)

    def _run(self, job: dict) -> None:
        job_id = job["id"]
        last_write = [0.0]

        def progress(fraction: float) -> None:
            fraction = min(max(float(fraction), 0.0), 1.0)
            self._progress[job_id] = fraction
            now = time.monotonic()
            if now - last_write[0] > 2.0:
                last_write[0] = now
                with self._lock:
                    self._db.execute("UPDATE jobs SET progress=? WHERE id=?", (fraction, job_id))

        def cancelled() -> bool:
            return job_id in self._cancel or self._stop.is_set()

        log.info("Job %s: transcribing %s/%s with %s", job_id, job["sessionId"], job["recordingId"], job["model"])
        t0 = time.monotonic()
        try:
            outcome = self.runner(job, progress, cancelled) or {}
        except Exception as e:  # noqa: BLE001 - any failure ends the job, not the worker
            if self._stop.is_set() and job_id not in self._cancel:
                # Shutting down: leave it to be re-queued on the next start.
                log.info("Job %s interrupted by shutdown", job_id)
                with self._lock:
                    self._db.execute("UPDATE jobs SET status='queued', progress=0, started_at=NULL WHERE id=?", (job_id,))
                    self._progress.pop(job_id, None)
            else:
                name = type(e).__name__
                message = "Cancelled" if name == "Cancelled" else (str(e) or name)
                if name != "Cancelled":
                    log.error("Job %s failed: %s\n%s", job_id, message, traceback.format_exc())
                self._finish(job_id, "error", message)
        else:
            log.info("Job %s done in %.1f s", job_id, time.monotonic() - t0)
            self._finish(job_id, "done", None, device=outcome.get("device"), info=outcome.get("info"))
        finally:
            self._running = None
