"""AudioCool Desktop: a LAN companion for the AudioCool Android app.

The phone sends recording sessions (audio plus timestamped notes); the desktop keeps them in a
library folder with the same layout as the phone's backups, transcribes them on the GPU, and
serves a web UI for browsing, playback, search and export.
"""

import os as _os
from pathlib import Path as _Path

__version__ = "1.1.0"

#: Version of the phone-facing HTTP API (reported by /api/v1/ping).
API_VERSION = "1.0"

#: Identifies this server to the phone.
APP_ID = "audiocool-desktop"

#: The desktop/ folder (holds .venv and, by default, the model cache).
DESKTOP_DIR = _Path(__file__).resolve().parent.parent

# Model files go in desktop/.cache unless the user keeps a Hugging Face cache elsewhere. Set
# before anything imports huggingface_hub, which reads HF_HOME once at import time.
_os.environ.setdefault("HF_HOME", str(DESKTOP_DIR / ".cache" / "huggingface"))
_os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
_os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
_os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")
