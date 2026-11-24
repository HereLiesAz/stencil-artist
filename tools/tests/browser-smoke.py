"""Browser regression against the actual Wasm distribution, not a mock of Compose.

Use the browser's accessibility tree and native file chooser/download events.
A transparent input deliberately avoids model inference; depth and physical-device
checks remain separate acceptance gates. Save screenshots even on failure.
"""
import argparse
import io
import json
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from PIL import Image
from playwright.sync_api import sync_playwright


def control(page, name):
    """Resolve one visible real control, without relying on screen coordinates."""
    # Every control exercised here is a real Compose Button or OutlinedButton.
    # Do not choose a text fallback based on an instantaneous count: modal removal
    # and stage navigation rebuild the accessibility tree asynchronously.
    return page.get_by_role("button", name=name, exact=True).first


def smoke(browser, url, width, height, directory, profile=False):
    context = browser.new_context(viewport={"width": width, "height": height},
                                  device_scale_factor=1, accept_downloads=True,
                                  has_touch=width < 720)
    page = context.new_page()
    errors = []
    records = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    if profile:
        page.on("console", lambda message: records.append(message.text)
                if message.text.startswith("StencilPerf ") else None)
        parts = urlsplit(url)
        query = [(key, value) for key, value in parse_qsl(parts.query, keep_blank_values=True)
                 if key != "profile"]
        url = urlunsplit(parts._replace(query=urlencode(query + [("profile", "1")])))
    page.set_default_timeout(20000)
    label = f"{width}x{height}"
    try:
        response = page.goto(url, wait_until="load", timeout=90000)
        assert response and response.ok, f"Failed to load {url}"
        page.locator("#loading").wait_for(state="detached", timeout=90000)
        page.locator("canvas").first.wait_for(state="visible")
        # Compose's semantic divs are overlays; the real canvas receives pointer events.
        # Forced locator clicks still send physical browser pointer input at those bounds.
        # Do not invoke DOM click handlers or bypass the actual chooser/download assertions.
        # Enable the browser accessibility tree before looking up Compose semantics.
        page.keyboard.press("Tab")
        control(page, "Open image").wait_for(state="visible")
        page.screenshot(path=directory / f"{label}-initial.png")

        fixture = Image.new("RGBA", (80, 48), (0, 0, 0, 0))
        fixture.paste((240, 48, 24, 255), (8, 8, 32, 40))
        fixture.paste((30, 210, 180, 255), (40, 8, 72, 40))
        buffer = io.BytesIO()
        fixture.save(buffer, format="PNG")
        with page.expect_file_chooser() as chooser:
            control(page, "Open image").click(force=True)
        chooser.value.set_files({"name": "smoke.png", "mimeType": "image/png",
                                 "buffer": buffer.getvalue()})
        # Completion acknowledgement is mandatory; do not skip it by sleeping.
        # The native browser dialog is a real modal DOM element, not a canvas overlay.
        modal = page.locator("dialog#stencil-loading-notice[open]")
        modal.get_by_role("button", name="Continue", exact=True).wait_for(timeout=120000)
        page.screenshot(path=directory / f"{label}-acknowledgement.png")
        assert control(page, "Next: Palette").count() == 0, "Workspace exposed through the modal"
        modal.get_by_role("button", name="Continue", exact=True).click()
        modal.wait_for(state="detached")
        control(page, "Continue").wait_for(state="hidden")
        # Require actual Subject navigation after the modal has gone away.
        control(page, "Next: Palette").wait_for(state="visible")
        page.screenshot(path=directory / f"{label}-subject.png")

        for stage in ("Palette", "Light", "Adjust", "Edges", "Inspect", "Images"):
            control(page, f"Next: {stage}").wait_for(state="visible")
            control(page, f"Next: {stage}").click(force=True)
        with page.expect_download(timeout=30000) as download:
            control(page, "Download composite PNG").click(force=True)
        output = directory / f"{label}-composite.png"
        download.value.save_as(output)
        with Image.open(output) as decoded:
            assert decoded.size == (80, 48), f"Wrong export dimensions: {decoded.size}"
            rgba = decoded.convert("RGBA")
            assert rgba.getpixel((0, 0))[3] == 0, "Exterior became opaque"
            assert rgba.getpixel((18, 24))[3] != 0, "Left subject disappeared"
            assert rgba.getpixel((56, 24))[3] != 0, "Right subject disappeared"
            assert rgba.getpixel((18, 24)) != rgba.getpixel((56, 24)), "Distinct regions collapsed"
        page.screenshot(path=directory / f"{label}-images.png")
        # Observe the persisted checkpoint through the real storage transaction; do not assume
        # that completion of a download implies the asynchronously saved stage is committed.
        page.get_by_label("Workspace saved", exact=True).wait_for(state="visible", timeout=30000)
        page.reload(wait_until="load", timeout=90000)
        page.locator("#loading").wait_for(state="detached", timeout=90000)
        page.keyboard.press("Tab")
        modal.get_by_role("button", name="Continue", exact=True).wait_for(timeout=120000)
        modal.get_by_role("button", name="Continue", exact=True).click()
        modal.wait_for(state="detached")
        control(page, "Download composite PNG").wait_for(state="visible")
        with page.expect_download(timeout=30000) as restored_download:
            control(page, "Download composite PNG").click(force=True)
        restored = directory / f"{label}-restored.png"
        restored_download.value.save_as(restored)
        with Image.open(output) as before, Image.open(restored) as after:
            assert before.size == after.size
            assert before.convert("RGBA").tobytes() == after.convert("RGBA").tobytes(), "Restored stencil changed"
        page.screenshot(path=directory / f"{label}-restored-screen.png")
        assert not errors, f"Uncaught browser errors: {errors}"
        if profile:
            assert any("status=ok " in record and "total_ms=" in record for record in records), (
                "No successful processing timing record")
        print(f"PASS {label}: startup, transparent image import, acknowledgement, stages, PNG download")
    finally:
        try:
            page.screenshot(path=directory / f"{label}-last.png")
            # Record which semantic controls remain when a transition fails.
            (directory / f"{label}-accessibility.txt").write_text(
                page.locator("body").aria_snapshot())
        finally:
            if profile:
                (directory / f"{label}-timings.json").write_text(json.dumps(records, indent=2) + "\n")
            context.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--url")
    target.add_argument("--distribution", type=Path)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--chromium", help="Optional path to an installed Chromium executable")
    parser.add_argument("--profile", action="store_true", help="Capture local processing timings")
    args = parser.parse_args()
    args.artifacts.mkdir(parents=True, exist_ok=True)
    server = None
    url = args.url
    if args.distribution:
        candidates = [index.parent for index in args.distribution.rglob("index.html")
                      if (index.parent / "stencil-artist.js").is_file()]
        if len(candidates) != 1:
            parser.error(f"Expected exactly one distribution, found {candidates}")
        server = ThreadingHTTPServer(("127.0.0.1", 0),
                                     partial(SimpleHTTPRequestHandler, directory=candidates[0]))
        Thread(target=server.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{server.server_port}/"
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=args.chromium,
                args=["--force-renderer-accessibility"])
            try:
                for width, height in ((1280, 900), (390, 844)):
                    smoke(browser, url, width, height, args.artifacts, args.profile)
            finally:
                browser.close()
    finally:
        if server:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
