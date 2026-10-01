"""Downloading the speech models ahead of the first transcription."""

from __future__ import annotations

import logging
import threading
import time

log = logging.getLogger(__name__)

# Enough for Transformers to load each model (skips e.g. Parakeet's .nemo and .gguf copies).
ALLOW = ["*.json", "*.safetensors", "*.jinja", "*.model", "*.txt"]
RECHECK_S = 30.0  # how long "not downloaded" is believed before looking at the disk again

_state: dict[str, str] = {}  # repo -> "cached" | "downloading" | "missing" | "error: ..."
_checked: dict[str, float] = {}  # repo -> when "missing" was last confirmed
_lock = threading.Lock()


def is_cached(repo: str) -> bool:
    try:
        from huggingface_hub import snapshot_download

        snapshot_download(repo, allow_patterns=ALLOW, local_files_only=True)
        return True
    except Exception:
        return False


def _repo_state(repo: str) -> str:
    with _lock:
        state = _state.get(repo)
        if state in ("cached", "downloading") or (state is not None and time.monotonic() - _checked.get(repo, 0) < RECHECK_S):
            return state
    cached = is_cached(repo)  # the user may have downloaded it some other way
    with _lock:
        if _state.get(repo) == "downloading":
            return "downloading"
        if cached:
            _state[repo] = "cached"
        elif not str(_state.get(repo, "")).startswith("error"):
            _state[repo] = "missing"
        _checked[repo] = time.monotonic()
        return _state[repo]


def status(repos: tuple[str, ...]) -> str:
    """'ready', 'downloading', 'missing' or 'error: ...' for a model made of these repos."""
    states = [_repo_state(r) for r in repos]
    if all(s == "cached" for s in states):
        return "ready"
    if any(s == "downloading" for s in states):
        return "downloading"
    errors = [s for s in states if s.startswith("error")]
    return errors[0] if errors else "missing"


def download(repos: tuple[str, ...]) -> None:
    from huggingface_hub import snapshot_download

    for repo in repos:
        with _lock:
            if _state.get(repo) in ("cached", "downloading"):
                continue
            _state[repo] = "downloading"
        try:
            if not is_cached(repo):
                log.info("Downloading %s from Hugging Face…", repo)
            snapshot_download(repo, allow_patterns=ALLOW)
            with _lock:
                _state[repo] = "cached"
            log.info("Model files ready: %s", repo)
        except Exception as e:  # offline, disk full...
            log.warning("Couldn't download %s: %s", repo, e)
            with _lock:
                _state[repo] = f"error: {str(e).splitlines()[0][:200]}"
                _checked[repo] = time.monotonic()


def prefetch_in_background(repos: tuple[str, ...]) -> threading.Thread:
    t = threading.Thread(target=download, args=(repos,), name="model-download", daemon=True)
    t.start()
    return t
