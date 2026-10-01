"""Settings kept in a small JSON file: the pairing code, library location and computer name.

The file lives in the app home (``$AUDIOCOOL_HOME``, default ``~/.config/audiocool-desktop``),
next to the job queue database. It is created with a fresh pairing code on first run.
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import socket
import threading
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

DEFAULT_PORT = 8765
# No 0/O, 1/I/L: easy to read off the screen and type on a phone keyboard.
TOKEN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
TOKEN_LENGTH = 8


def app_home() -> Path:
    env = os.environ.get("AUDIOCOOL_HOME")
    if env:
        return Path(env).expanduser()
    base = os.environ.get("XDG_CONFIG_HOME") or "~/.config"
    return Path(base).expanduser() / "audiocool-desktop"


def default_library() -> Path:
    return Path("~/AudioCool Library").expanduser()


def new_token() -> str:
    return "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(TOKEN_LENGTH))


def normalize_token(value: str) -> str:
    """Case and separators don't matter when the code is typed in by hand."""
    return "".join(ch for ch in value.upper() if ch.isalnum())


def computer_name() -> str:
    name = socket.gethostname().split(".")[0]
    return name or "AudioCool Desktop"


def atomic_write_text(path: Path, text: str, mode: int | None = None) -> None:
    """Writes a file so readers see either the old or the new content, never half of it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{secrets.token_hex(4)}.tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        if mode is not None:
            os.chmod(tmp, mode)
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


class Config:
    def __init__(self, home: Path | None = None, library: Path | None = None):
        self.home = home or app_home()
        self.path = self.home / "config.json"
        self._lock = threading.Lock()
        self._data: dict[str, Any] = {}
        self._load()
        # A library given on the command line / environment wins for this run without being saved.
        self._library_override = Path(library).expanduser() if library else None

    def _load(self) -> None:
        self.home.mkdir(parents=True, exist_ok=True)
        data: dict[str, Any] = {}
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if not isinstance(data, dict):
                    raise ValueError("not an object")
            except (OSError, ValueError) as e:
                log.warning("Couldn't read %s (%s); starting with fresh settings", self.path, e)
                data = {}
        changed = False
        token = data.get("token")
        if not isinstance(token, str) or len(normalize_token(token)) < 6:
            data["token"] = new_token()
            changed = True
        if not isinstance(data.get("library"), str) or not data["library"]:
            data["library"] = str(default_library())
            changed = True
        if not isinstance(data.get("name"), str) or not data["name"].strip():
            data["name"] = computer_name()
            changed = True
        self._data = data
        if changed:
            self._save()

    def _save(self) -> None:
        atomic_write_text(self.path, json.dumps(self._data, indent=2) + "\n", mode=0o600)

    # -- values ---------------------------------------------------------------------------

    @property
    def token(self) -> str:
        return self._data["token"]

    def check_token(self, presented: str) -> bool:
        return secrets.compare_digest(normalize_token(presented).encode(), normalize_token(self.token).encode())

    def reset_token(self) -> str:
        with self._lock:
            self._data["token"] = new_token()
            self._save()
            return self._data["token"]

    @property
    def library(self) -> Path:
        if self._library_override is not None:
            return self._library_override
        return Path(self._data["library"]).expanduser()

    @property
    def library_overridden(self) -> bool:
        return self._library_override is not None

    @property
    def name(self) -> str:
        return self._data["name"]

    @property
    def default_model(self) -> str | None:
        value = self._data.get("defaultModel")
        return value if isinstance(value, str) and value else None

    def update(self, **values: Any) -> None:
        """Saves new values; ``library`` also clears a command-line override."""
        with self._lock:
            for key, value in values.items():
                if key == "library":
                    self._library_override = None
                    self._data["library"] = str(Path(value).expanduser())
                elif key == "defaultModel":
                    if value:
                        self._data["defaultModel"] = value
                    else:
                        self._data.pop("defaultModel", None)
                else:
                    self._data[key] = value
            self._save()
