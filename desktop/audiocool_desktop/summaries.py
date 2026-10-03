"""Summaries for the phone: Google's Gemma 4 26B, run by llama.cpp on this computer.

The phone decides what to summarize, writes the prompts and reads the replies. When it's paired with
this computer and can reach it, it sends those prompts here instead of running its own much smaller
model, and gets the replies back. llama.cpp's server runs them as a child process on a local port:
started by the first request and stopped after a while without one, to give the GPU memory back.

Turned on from Settings, which downloads llama.cpp (its CUDA 12.8 build, 171 MB) and the model
(Google's quantization-aware 4-bit build, 14.4 GB) into desktop/.cache/summaries. The CUDA libraries
come from the PyTorch install that the speech models already use.
"""

from __future__ import annotations

import ctypes
import hashlib
import importlib.util
import json
import logging
import os
import platform
import queue
import shutil
import signal
import socket
import subprocess
import tarfile
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Download:
    url: str
    size: int
    sha256: str
    name: str  # its file name in the summaries folder


LLAMA_BUILD = "b11364"
LLAMA = Download(
    f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_BUILD}/llama-{LLAMA_BUILD}-bin-ubuntu-cuda-12.8-x64.tar.gz",
    171_329_376,
    "0b815d920e70390fa97454df6406f10654a04c6e8209e723f9decb558e123831",
    f"llama-{LLAMA_BUILD}.tar.gz",
)
MODEL = Download(
    "https://huggingface.co/google/gemma-4-26B-A4B-it-qat-q4_0-gguf/resolve/d1c082be9cf3c8a514acf63b8761f4b41935842e/gemma-4-26B_q4_0-it.gguf",
    14_439_363_584,
    "3eca3b8f6d7baf218a7dd6bba5fb59a56ee25fe2d567b6f5f589b4f697eca51d",
    "gemma-4-26B_q4_0-it.gguf",
)
#: What the phone records as having written a summary (it adds "@desktop").
MODEL_ID = "gemma-4-26b-a4b"
MODEL_NAME = "Gemma 4 26B"

#: Long enough for the phone's longest prompt (a session's chapter summaries) and its reply.
CONTEXT = 8192
#: Stop the server after this long without a request.
IDLE_S = 300.0
#: Loading 14 GB from disk onto the GPU; the first time can be slow.
START_TIMEOUT_S = 300.0
MAX_PROMPT_CHARS = 60_000
MAX_TOKENS = 2048

#: Starts a server for [model] on [port], its output going to [log]. Tests swap in a stand-in.
Launcher = Callable[[Path, int, Path], subprocess.Popen]


class Cancelled(Exception):
    pass


def fetch(
    item: Download, dest: Path, cancelled: Callable[[], bool], on_bytes: Callable[[int], None], workers: int = 16, chunk: int = 32 << 20
) -> None:
    """Downloads [item] to [dest] in parallel byte ranges (some connections are throttled one by
    one), resuming what an earlier try got, and checks its SHA-256."""
    count = (item.size + chunk - 1) // chunk
    part = dest.with_name(dest.name + ".part")
    done_list = dest.with_name(dest.name + ".chunks")
    done = {int(x) for x in done_list.read_text().split()} if done_list.exists() and part.exists() else set()
    if not part.exists() or part.stat().st_size != item.size:
        done = set()
        with open(part, "wb") as f:
            f.truncate(item.size)
    on_bytes(sum(min(item.size, (i + 1) * chunk) - i * chunk for i in done))
    lock = threading.Lock()
    resolved: list = [None, 0.0]

    def url() -> str:
        # Hugging Face and GitHub redirect to signed addresses that expire; look again every 20 minutes.
        with lock:
            if resolved[0] is None or time.monotonic() - resolved[1] > 1200:
                req = urllib.request.Request(item.url, method="HEAD", headers={"User-Agent": "AudioCool-Desktop"})
                resolved[0] = urllib.request.urlopen(req, timeout=60).geturl()
                resolved[1] = time.monotonic()
            return resolved[0]

    todo: queue.Queue[int] = queue.Queue()
    for i in range(count):
        if i not in done:
            todo.put(i)
    errors: list[Exception] = []

    def work() -> None:
        while not cancelled() and not errors:
            try:
                i = todo.get_nowait()
            except queue.Empty:
                return
            start, end = i * chunk, min(item.size, (i + 1) * chunk) - 1
            for attempt in range(12):
                if cancelled():
                    return
                got = 0
                try:
                    req = urllib.request.Request(url(), headers={"Range": f"bytes={start}-{end}", "User-Agent": "AudioCool-Desktop"})
                    with urllib.request.urlopen(req, timeout=60) as r, open(part, "r+b") as f:
                        if r.status != 206:
                            raise OSError(f"the server ignored the byte range (HTTP {r.status})")
                        f.seek(start)
                        while not cancelled():
                            b = r.read(1 << 20)
                            if not b:
                                break
                            f.write(b)
                            got += len(b)
                            on_bytes(len(b))
                    if got == end - start + 1:
                        with lock:
                            with open(done_list, "a") as d:
                                d.write(f"{i}\n")
                        break
                    if cancelled():
                        return
                    raise OSError("the connection closed early")
                except Exception as e:  # noqa: BLE001 - retried, then reported
                    on_bytes(-got)
                    if isinstance(e, urllib.error.HTTPError) and e.code in (401, 403, 410):
                        with lock:
                            resolved[0] = None  # the signed address expired
                    if attempt == 11:
                        errors.append(e)
                        return
                    time.sleep(min(30.0, 2.0**attempt))

    threads = [threading.Thread(target=work, daemon=True) for _ in range(min(workers, max(1, todo.qsize())))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if cancelled():
        raise Cancelled()
    if errors:
        raise errors[0]
    if sha256(part) != item.sha256:
        part.unlink(missing_ok=True)
        done_list.unlink(missing_ok=True)
        raise OSError(f"{item.name} was damaged on the way; try again")
    part.replace(dest)
    done_list.unlink(missing_ok=True)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(8 << 20):
            h.update(b)
    return h.hexdigest()


def cuda_libraries() -> list[str]:
    """Where PyTorch's CUDA runtime and cuBLAS are, which llama.cpp's CUDA build needs."""
    spec = importlib.util.find_spec("nvidia")
    if spec is None or not spec.submodule_search_locations:
        return []
    dirs = []
    for base in spec.submodule_search_locations:
        for sub in ("cuda_runtime/lib", "cublas/lib"):
            if (Path(base) / sub).is_dir():
                dirs.append(str(Path(base) / sub))
    return dirs


_libc = ctypes.CDLL(None, use_errno=True) if platform.system() == "Linux" else None
_PR_SET_PDEATHSIG = 1


def _die_with_parent() -> None:
    """In the child, before llama-server starts: end it when the thread that started it ends (with the
    whole app, however that goes), so it can't keep its GPU memory."""
    if _libc is not None:
        _libc.prctl(_PR_SET_PDEATHSIG, signal.SIGTERM)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Summaries:
    """The model's files, its server, and the replies; thread-safe."""

    def __init__(self, root: Path, launcher: Launcher | None = None, idle_s: float = IDLE_S):
        self.root = Path(root)
        self.idle_s = idle_s
        self._launcher = launcher or self._launch_llama
        self._lock = threading.Lock()  # one reply at a time, and the server's start and stop
        self._state_lock = threading.Lock()
        self._proc: subprocess.Popen | None = None
        self._port = 0
        self._busy = False
        self._last_used = 0.0
        self._downloading: threading.Thread | None = None
        self._cancel = threading.Event()
        self._got = 0
        self._error: str | None = None
        self._stopper: threading.Thread | None = None
        self._closed = threading.Event()
        self.last_seconds: float | None = None
        # Servers are started from one long-lived thread: a child set to end with its parent ends with
        # the thread that started it, and request threads come and go.
        self._launches: queue.Queue = queue.Queue()
        threading.Thread(target=self._launch_loop, name="summaries-launcher", daemon=True).start()

    def _launch_loop(self) -> None:
        while True:
            launch, done = self._launches.get()
            try:
                done.put(launch())
            except BaseException as e:  # noqa: BLE001 - handed to the caller
                done.put(e)

    def _launch(self, port: int, logfile: Path) -> subprocess.Popen:
        done: queue.Queue = queue.Queue()
        self._launches.put((lambda: self._launcher(self.model_path, port, logfile), done))
        result = done.get()
        if isinstance(result, BaseException):
            raise result
        return result

    # ---- Files ----

    @property
    def model_path(self) -> Path:
        return self.root / MODEL.name

    @property
    def server_path(self) -> Path:
        return self.root / f"llama-{LLAMA_BUILD}" / "llama-server"

    @staticmethod
    def supported() -> bool:
        return platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64")

    @property
    def ready(self) -> bool:
        return self.server_path.is_file() and self.model_path.is_file() and self.model_path.stat().st_size == MODEL.size

    def status(self) -> dict:
        with self._state_lock:
            downloading = self._downloading is not None and self._downloading.is_alive()
            total = LLAMA.size + MODEL.size
            if not self.supported():
                state = "unsupported"
            elif downloading:
                state = "downloading"
            elif self.ready:
                state = "ready"
            elif self._error:
                state = "error"
            else:
                state = "missing"
            return {
                "state": state,
                "progress": min(1.0, self._got / total) if downloading else (1.0 if state == "ready" else 0.0),
                "error": self._error if state == "error" else None,
                "model": MODEL_NAME,
                "id": MODEL_ID,
                "downloadBytes": total,
                "running": self._proc is not None and self._proc.poll() is None,
                "lastSeconds": self.last_seconds,
            }

    def download(self) -> None:
        """Fetches whatever's missing, in the background; status() follows it."""
        with self._state_lock:
            if (self._downloading and self._downloading.is_alive()) or self.ready or not self.supported():
                return
            self._cancel.clear()
            self._error = None
            self._got = 0
            self._downloading = threading.Thread(target=self._download, name="summaries-download", daemon=True)
            self._downloading.start()

    def cancel(self) -> None:
        self._cancel.set()

    def _count(self, n: int) -> None:
        with self._state_lock:
            self._got += n

    def _download(self) -> None:
        try:
            self.root.mkdir(parents=True, exist_ok=True)
            if not self.server_path.is_file():
                archive = self.root / LLAMA.name
                if not archive.is_file():
                    fetch(LLAMA, archive, self._cancel.is_set, self._count, workers=8)
                else:
                    self._count(LLAMA.size)
                with tarfile.open(archive) as tar:
                    tar.extractall(self.root, filter="data")
                archive.unlink(missing_ok=True)
                if not self.server_path.is_file():
                    raise OSError("llama.cpp's archive didn't have llama-server in it")
            else:
                self._count(LLAMA.size)
            if not self.model_path.is_file():
                log.info("Downloading %s (%.1f GB)…", MODEL_NAME, MODEL.size / 1e9)
                fetch(MODEL, self.model_path, self._cancel.is_set, self._count, workers=32)
            log.info("%s is ready for the phone's summaries", MODEL_NAME)
        except Cancelled:
            log.info("Stopped downloading %s", MODEL_NAME)
        except Exception as e:  # noqa: BLE001 - shown in Settings
            log.warning("Couldn't download %s: %s", MODEL_NAME, e)
            with self._state_lock:
                self._error = str(e).splitlines()[0][:200] or e.__class__.__name__

    def delete(self) -> None:
        self.cancel()
        if self._downloading:
            self._downloading.join(timeout=30)
        with self._lock:
            self._stop()
        shutil.rmtree(self.root, ignore_errors=True)
        with self._state_lock:
            self._error = None

    # ---- The server ----

    def _launch_llama(self, model: Path, port: int, logfile: Path) -> subprocess.Popen:
        env = dict(os.environ)
        env["LD_LIBRARY_PATH"] = os.pathsep.join([str(self.server_path.parent), *cuda_libraries(), env.get("LD_LIBRARY_PATH", "")]).rstrip(os.pathsep)
        cmd = [
            str(self.server_path), "-m", str(model), "--host", "127.0.0.1", "--port", str(port),
            "-c", str(CONTEXT), "-np", "1", "--jinja", "--reasoning", "off", "--no-webui",
            # As the phone's model is set: a summary should say what's there, the same way each time.
            "--temp", "0.3", "--top-k", "40", "--top-p", "0.95", "--seed", "1",
        ]  # fmt: skip
        out = open(logfile, "ab")
        try:
            return subprocess.Popen(
                cmd, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, env=env, start_new_session=True, preexec_fn=_die_with_parent
            )
        finally:
            out.close()

    def _get(self, path: str, timeout: float = 5.0) -> tuple[int, dict]:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self._port}{path}", timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, {}

    @property
    def _pid_file(self) -> Path:
        return self.root / "llama-server.pid"

    def _stop_stale(self) -> None:
        """Stops a server left running by an earlier run that didn't get to (killed, say): it would
        still hold its GPU memory."""
        try:
            pid = int(self._pid_file.read_text())
            cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
        except (OSError, ValueError):
            return
        if str(self.model_path).encode() in cmdline:
            log.info("Stopping a summary server left from before (pid %d)", pid)
            os.kill(pid, signal.SIGTERM)
            for _ in range(50):
                if not Path(f"/proc/{pid}").exists():
                    break
                time.sleep(0.1)
        self._pid_file.unlink(missing_ok=True)

    def _ensure_running(self) -> None:
        if self._proc is not None and self._proc.poll() is None:
            return
        self._stop()
        self.root.mkdir(parents=True, exist_ok=True)
        self._stop_stale()
        logfile = self.root / "llama-server.log"
        if logfile.exists() and logfile.stat().st_size > 5_000_000:
            logfile.unlink()
        self._port = free_port()
        log.info("Starting %s for the phone's summaries", MODEL_NAME)
        started = time.monotonic()
        self._proc = self._launch(self._port, logfile)
        self._pid_file.write_text(str(self._proc.pid))
        while True:
            if self._proc.poll() is not None:
                tail = logfile.read_text(errors="replace").strip().splitlines()[-3:] if logfile.exists() else []
                self._proc = None
                raise RuntimeError(f"{MODEL_NAME} wouldn't start" + (f": {tail[-1]}" if tail else ""))
            try:
                status, _ = self._get("/health", timeout=2)
                if status == 200:
                    break
            except OSError:
                pass  # not listening yet
            if time.monotonic() - started > START_TIMEOUT_S:
                self._stop()
                raise RuntimeError(f"{MODEL_NAME} took too long to start")
            time.sleep(0.25)
        log.info("%s is up (%.1f s)", MODEL_NAME, time.monotonic() - started)
        if self._stopper is None or not self._stopper.is_alive():
            self._stopper = threading.Thread(target=self._stop_when_idle, name="summaries-idle", daemon=True)
            self._stopper.start()

    def _stop(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None or proc.poll() is not None:
            return
        self._pid_file.unlink(missing_ok=True)
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        log.info("Stopped %s", MODEL_NAME)

    def _stop_when_idle(self) -> None:
        while not self._closed.wait(min(15.0, self.idle_s / 4)):
            if self._proc is None:
                return
            if not self._busy and time.monotonic() - self._last_used > self.idle_s and self._lock.acquire(blocking=False):
                try:
                    self._stop()
                finally:
                    self._lock.release()
                return

    def release(self) -> None:
        """Gives the GPU memory back now, unless a reply is being written."""
        if self._lock.acquire(blocking=False):
            try:
                self._stop()
            finally:
                self._lock.release()

    def close(self) -> None:
        self._closed.set()
        self.cancel()
        if self._lock.acquire(timeout=2):
            try:
                self._stop()
            finally:
                self._lock.release()
        elif self._proc is not None:
            # Mid-reply: don't wait for it to finish.
            self._proc.terminate()
            self._pid_file.unlink(missing_ok=True)

    # ---- Replies ----

    def reply(self, prompt: str, max_tokens: int) -> str:
        """What the model says to [prompt] (as the phone's model would be asked it), in at most
        [max_tokens] tokens. Starts the server if it isn't running."""
        if not self.ready:
            raise RuntimeError(f"{MODEL_NAME} isn't downloaded")
        with self._lock:
            self._busy = True
            try:
                self._ensure_running()
                started = time.monotonic()
                body = json.dumps({
                    "messages": [{"role": "user", "content": prompt}],
                    "max_tokens": max_tokens,
                    "temperature": 0.3, "top_k": 40, "top_p": 0.95, "seed": 1,
                    "stream": False,
                }).encode()  # fmt: skip
                req = urllib.request.Request(
                    f"http://127.0.0.1:{self._port}/v1/chat/completions", data=body, headers={"Content-Type": "application/json"}, method="POST"
                )
                try:
                    with urllib.request.urlopen(req, timeout=900) as r:
                        answer = json.loads(r.read())
                except urllib.error.HTTPError as e:
                    detail = e.read().decode(errors="replace")[:300]
                    raise RuntimeError(f"{MODEL_NAME} couldn't answer (HTTP {e.code}): {detail}") from None
                except OSError:
                    # The server may have died (out of memory, say): the next request starts it again.
                    self._stop()
                    raise
                self.last_seconds = round(time.monotonic() - started, 2)
                return answer["choices"][0]["message"].get("content") or ""
            finally:
                self._busy = False
                self._last_used = time.monotonic()
