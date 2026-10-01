"""Screenshots of the web UI, light and dark, with Playwright (Chromium).

    .venv/bin/pip install playwright && .venv/bin/python -m playwright install chromium
    .venv/bin/python tools/screenshots.py --url http://localhost:8765 --session <id> --query princess

Saves docs/screenshots/<page>-<light|dark>.png. Fails if a page logs a JavaScript error.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "screenshots"


def main() -> int:
    from playwright.sync_api import sync_playwright

    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8765")
    p.add_argument("--session", required=True, help="session id to show")
    p.add_argument("--at", type=int, default=98_000, help="playback position (ms) for the session page")
    p.add_argument("--query", default="princess")
    p.add_argument("--out", default=str(OUT))
    args = p.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    shots = [
        ("library", "#/", None),
        ("session", f"#/session/{args.session}?t={args.at}", ".line.active"),
        ("search", f"#/search?q={args.query}", ".hit"),
        ("jobs", "#/jobs", ".job"),
        ("pair", "#/pair", ".qr-box svg"),
        ("import", "#/import", ".drop"),
        ("settings", "#/settings", ".model-row"),
    ]
    errors: list[str] = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(args=["--autoplay-policy=no-user-gesture-required"])
        for scheme in ("light", "dark"):
            ctx = browser.new_context(viewport={"width": 1320, "height": 860}, color_scheme=scheme, device_scale_factor=1)
            page = ctx.new_page()
            page.on("console", lambda m: errors.append(f"{m.type}: {m.text}") if m.type == "error" else None)
            page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))
            for name, route, wait_for in shots:
                page.goto(f"{args.url}/{route}")
                page.wait_for_selector(wait_for or ".session-card, .empty", timeout=15_000)
                if name == "session":
                    page.wait_for_timeout(1200)
                    page.evaluate("document.getElementById('audio').pause()")
                    page.wait_for_timeout(600)
                else:
                    page.wait_for_timeout(400)
                page.mouse.move(0, 0)
                path = out / f"{name}-{scheme}.png"
                page.screenshot(path=str(path))
                print(path)
            ctx.close()
        # One at phone width, to show the responsive layout.
        ctx = browser.new_context(viewport={"width": 390, "height": 844}, color_scheme="light", device_scale_factor=2, is_mobile=True)
        page = ctx.new_page()
        page.goto(f"{args.url}/#/session/{args.session}?t={args.at}")
        page.wait_for_selector(".line.active", timeout=15_000)
        page.wait_for_timeout(1200)
        page.evaluate("document.getElementById('audio').pause()")
        page.screenshot(path=str(out / "session-mobile-light.png"))
        print(out / "session-mobile-light.png")
        browser.close()
    if errors:
        print("JavaScript errors:\n" + "\n".join(errors), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
