#!/usr/bin/env bash
# AudioCool Desktop. Sets up desktop/.venv on the first run, then starts the server on
# 0.0.0.0:8765 and prints the local URL, the LAN URL for the phone and the pairing code.
#
#   ./run.sh                   start the server (options: ./run.sh --help)
#   ./run.sh --port 8766       e.g. another port, or --library DIR for another library folder
#   ./run.sh test [args]       run the tests (pytest args, e.g. -m "not slow")
#   ./run.sh benchmark [args]  measure speed and WER (tools/benchmark.py --help)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
HERE="$PWD"
VENV="$HERE/.venv"

# PyTorch with CUDA 12.8 supports the RTX 50xx (Blackwell, sm_120). Without an NVIDIA GPU, the
# much smaller CPU build is installed instead.
TORCH_VERSION="2.8.0"
if command -v nvidia-smi >/dev/null 2>&1 || [ -x /usr/lib/wsl/lib/nvidia-smi ]; then
  TORCH_INDEX="https://download.pytorch.org/whl/cu128"
else
  TORCH_INDEX="https://download.pytorch.org/whl/cpu"
fi
WANT="torch==$TORCH_VERSION $TORCH_INDEX $(sha256sum requirements.txt | cut -c1-16)"
STAMP="$VENV/.audiocool-deps"

if [ ! -x "$VENV/bin/python" ]; then
  echo "Creating $VENV ..."
  if command -v uv >/dev/null 2>&1; then
    uv venv --python 3.12 "$VENV"
  else
    PY="${PYTHON:-$(command -v python3.12 || command -v python3)}"
    "$PY" -m venv "$VENV" || { echo "Couldn't create a venv; on Ubuntu: sudo apt install python3.12-venv" >&2; exit 1; }
  fi
fi

if [ "$(cat "$STAMP" 2>/dev/null || true)" != "$WANT" ]; then
  echo "Installing dependencies into $VENV (the first time this downloads PyTorch, a few GB) ..."
  if command -v uv >/dev/null 2>&1; then
    uv pip install --python "$VENV/bin/python" "torch==$TORCH_VERSION" --index-url "$TORCH_INDEX"
    uv pip install --python "$VENV/bin/python" -r requirements.txt
  else
    "$VENV/bin/python" -m pip install --upgrade pip
    "$VENV/bin/python" -m pip install "torch==$TORCH_VERSION" --index-url "$TORCH_INDEX"
    "$VENV/bin/python" -m pip install -r requirements.txt
  fi
  echo "$WANT" > "$STAMP"
fi

case "${1:-}" in
  test) shift; exec "$VENV/bin/python" -m pytest "$@" ;;
  benchmark) shift; exec "$VENV/bin/python" tools/benchmark.py "$@" ;;
  *) exec "$VENV/bin/python" -m audiocool_desktop "$@" ;;
esac
