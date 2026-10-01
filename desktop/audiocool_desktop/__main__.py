"""Starts AudioCool Desktop: ``python -m audiocool_desktop [--port 8765] [--library DIR]``."""

from __future__ import annotations

import argparse
import errno
import logging
import os
import socket
import sys
from pathlib import Path



def port_free(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((host, port))
            return True
        except OSError as e:
            if e.errno in (errno.EADDRINUSE, errno.EACCES):
                return False
            raise


def main(argv: list[str] | None = None) -> int:
    from .config import DEFAULT_PORT

    p = argparse.ArgumentParser(prog="audiocool-desktop", description=__doc__)
    p.add_argument("--host", default=os.environ.get("AUDIOCOOL_HOST", "0.0.0.0"), help="address to listen on (default 0.0.0.0, i.e. the LAN)")
    p.add_argument("--port", type=int, default=int(os.environ.get("AUDIOCOOL_PORT", DEFAULT_PORT)), help="port (default 8765; the phone expects 8765)")
    p.add_argument("--library", default=os.environ.get("AUDIOCOOL_LIBRARY"), help="library folder for this run (default: the saved setting, initially ~/AudioCool Library)")
    p.add_argument("--home", default=None, help="settings folder (default ~/.config/audiocool-desktop or $AUDIOCOOL_HOME)")
    p.add_argument("--no-prefetch", action="store_true", help="don't download the default model at startup")
    p.add_argument("--log-level", default=os.environ.get("AUDIOCOOL_LOG", "info"))
    args = p.parse_args(argv)

    logging.basicConfig(level=args.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    for noisy in ("httpx", "huggingface_hub", "urllib3", "filelock"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

    if not port_free(args.host, args.port):
        print(f"\nPort {args.port} is already in use by another program.", file=sys.stderr)
        print(f"Stop it (see: ss -ltnp 'sport = :{args.port}'), or run on another port with --port.", file=sys.stderr)
        if args.port == DEFAULT_PORT:
            print("Note: the phone app connects to port 8765.", file=sys.stderr)
        return 2

    from . import __version__, models_cache, netinfo
    from .app import App
    from .config import Config
    from .engines import Registry, default_specs
    from .server import create_app

    config = Config(Path(args.home).expanduser() if args.home else None, library=args.library)
    registry = Registry(default_specs())
    app = App(config, registry)
    default = registry.get(registry.default_id)
    if not args.no_prefetch and default is not None:
        models_cache.prefetch_in_background(default.repos)

    lan = netinfo.lan_addresses()
    gpu = registry.gpu_name
    line = "─" * 64
    print(f"\n{line}\n  AudioCool Desktop {__version__}\n{line}")
    print(f"  Web UI (this computer):   http://localhost:{args.port}/")
    if args.host in ("0.0.0.0", "::"):
        for ip in lan:
            print(f"  Phone URL (same Wi-Fi):   http://{ip}:{args.port}")
        if not lan:
            print("  Phone URL:                none: no LAN address found (is Wi-Fi/Ethernet connected?)")
    else:
        print(f"  Listening on:             http://{args.host}:{args.port} (LAN access needs --host 0.0.0.0)")
    print(f"  Pairing code:             {config.token}")
    print(f"  Library:                  {config.library}")
    print(f"  GPU:                      {gpu + ' (CUDA)' if gpu else 'none found; transcribing on the CPU'}")
    print(f"  Default model:            {registry.name_of(registry.default_id)}")
    print(f"{line}\n  Pair the phone from the Pair page of the web UI (QR code). Ctrl+C stops.\n{line}\n", flush=True)

    import uvicorn

    try:
        uvicorn.run(create_app(app, port=args.port), host=args.host, port=args.port, log_level="warning", access_log=False)
    finally:
        app.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
