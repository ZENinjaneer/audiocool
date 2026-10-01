"""Downloading the speech models ahead of the first transcription."""

from __future__ import annotations

import logging
import threading

log = logging.getLogger(__name__)

# Enough for Transformers to load each model (skips e.g. Parakeet's .nemo and .gguf copies).
ALLOW = ["*.json", "*.safetensors", "*.jinja", "*.model", "*.txt"]

_state: dict[str, str] = {}  # repo -> "cached" | "downloading" | "error: ..."
_lock = threading.Lock()


def is_cached(repo: str) -> bool:
    try:
        from huggingface_hub import snapshot_download

        snapshot_download(repo, allow_patterns=ALLOW, local_files_only=True)
        return True
    except Exception:
        return False


def status(repos: tuple[str, ...]) -> str:
    """'ready', 'downloading' or 'missing' for a model made of these repos."""
    with _lock:
        states = [_state.get(r) for r in repos]
    if all(s == "cached" for s in states):
        return "ready"
    if any(s == "downloading" for s in states):
        return "downloading"
    if all(s == "cached" or (s is None and is_cached(r)) for s, r in zip(states, repos)):
        with _lock:
            for r in repos:
                _state[r] = "cached"
        return "ready"
    return "missing"


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
                _state[repo] = f"error: {e}"


def prefetch_in_background(repos: tuple[str, ...]) -> threading.Thread:
    t = threading.Thread(target=download, args=(repos,), name="model-download", daemon=True)
    t.start()
    return t
