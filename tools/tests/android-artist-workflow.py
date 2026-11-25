"""Exercise the installed Android APK, real touch events and Storage Access Framework dialogs.

The test never invokes a stencil or exporter API directly. It saves the opaque photo,
UI screenshots, XML accessibility dumps, actual files and logcat for inspection.
Run only on a disposable emulator: its app data and fixture downloads are cleared.
"""
import argparse
import io
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from PIL import Image
from pypdf import PdfReader
from skimage import data

PACKAGE = "com.hereliesaz.stencil_artist"
FIXTURE = "stencil-acceptance-photo.png"


def adb(*args, check=True, timeout=45):
    return subprocess.run(["adb", *args], check=check, capture_output=True, timeout=timeout).stdout


class Device:
    """Operate on real Android accessibility nodes and screen coordinates."""
    def __init__(self, evidence):
        self.evidence = evidence
        self.width, self.height = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p"))).size

    def tree(self):
        for _ in range(3):
            adb("shell", "uiautomator", "dump", "/sdcard/stencil-ui.xml", check=False)
            raw = adb("shell", "cat", "/sdcard/stencil-ui.xml", check=False)
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                time.sleep(1)
        raise AssertionError("Android accessibility hierarchy unavailable")

    def shot(self, name):
        (self.evidence / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
        (self.evidence / (name + ".xml")).write_bytes(ET.tostring(self.tree(), encoding="utf-8"))

    @staticmethod
    def match(root, labels):
        for node in root.iter("node"):
            if node.get("enabled") != "true":
                continue
            if not any(node.get(key) in labels for key in ("text", "content-desc")):
                continue
            coords = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
            if len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1]:
                return ((coords[0] + coords[2]) // 2, (coords[1] + coords[3]) // 2)
        return None

    def tap(self, *labels, seconds=45, scroll=False):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            point = self.match(self.tree(), labels)
            if point:
                adb("shell", "input", "tap", str(point[0]), str(point[1]))
                print("TAPPED", labels, flush=True)
                time.sleep(.45)
                return
            if scroll:
                # Only swipe inside the lower settings panel, never across the preview.
                adb("shell", "input", "swipe", str(self.width // 2), str(int(self.height * .84)),
                    str(self.width // 2), str(int(self.height * .61)), "350")
            time.sleep(.5)
        raise AssertionError(f"Could not reach {labels}")

    def optional(self, *labels):
        point = self.match(self.tree(), labels)
        if point:
            adb("shell", "input", "tap", str(point[0]), str(point[1]))
            time.sleep(.5)
            return True
        return False

    def choose_photo(self):
        # GetContent may open the system Photo Picker or DocumentsUI. Use Browse
        # when available to select the exact named fixture from Downloads.
        self.tap("Open image", seconds=90)
        self.shot("01-picker")
        if self.optional("More options"):
            self.optional("Browse", "Browse files")
        self.optional("Browse", "Browse files")
        if self.optional("Show roots", "Open navigation drawer"):
            self.tap("Downloads", "Download")
        self.optional("Downloads", "Download")
        self.tap(FIXTURE, seconds=60, scroll=True)

    def save(self, label, filename):
        self.tap(label, seconds=90, scroll=True)
        self.shot("save-" + filename)
        # The app uses ACTION_CREATE_DOCUMENT; do not inject a result URI.
        if self.optional("Show roots", "Open navigation drawer"):
            self.tap("Downloads", "Download")
        self.tap("Save", "SAVE", seconds=90)
        remote = "/sdcard/Download/" + filename
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if adb("shell", "ls", remote, check=False).strip() == remote.encode():
                path = self.evidence / filename
                adb("pull", remote, str(path))
                if path.stat().st_size:
                    print("EXPORTED", filename, path.stat().st_size, flush=True)
                    return path
            time.sleep(1)
        raise AssertionError(f"Document picker did not save {filename} in Downloads")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    out = args.artifacts
    out.mkdir(parents=True, exist_ok=True)
    Image.fromarray(data.astronaut()).convert("RGB").resize((256, 256)).save(out / FIXTURE)
    adb("install", "-r", str(args.apk), timeout=180)
    adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "mkdir", "-p", "/sdcard/Download")
    filenames = ("stencil-composite.png", "stencil-pngs.zip", "stencil-layers.stencilpack",
                 "stencil-print.pdf", "stencil.svg", "stencil.dxf", "stencil.plt", "stencil.gcode")
    for name in (FIXTURE, *filenames):
        adb("shell", "rm", "-f", "/sdcard/Download/" + name)
    adb("push", str(out / FIXTURE), "/sdcard/Download/" + FIXTURE)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file:///sdcard/Download/" + FIXTURE, check=False)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    device = Device(out)
    try:
        device.choose_photo()
        device.tap("Continue", seconds=300)
        device.shot("02-subject")
        for stage in ("Palette", "Light", "Adjust", "Edges", "Inspect", "Images"):
            device.tap("Next: " + stage, stage + " →")
            device.shot(stage.lower())
        device.tap("Show download controls", "Downloads ↓")
        composite = device.save("Download composite PNG", filenames[0])
        with Image.open(composite) as image:
            rgba = image.convert("RGBA")
            assert rgba.size == (256, 256)
            assert rgba.getchannel("A").getextrema() == (0, 255), "Subject isolation failed"
            assert len({p[:3] for p in rgba.getdata() if p[3] == 255}) > 1
        device.tap("Show download controls", "Downloads ↓")
        archive = device.save("Download all sheets + composite ZIP", filenames[1])
        with zipfile.ZipFile(archive) as pack:
            assert pack.testzip() is None
            assert len([n for n in pack.namelist() if n.endswith(".png")]) > 2
        device.tap("Show download controls", "Downloads ↓")
        layered = device.save("Export layered StencilPack for Graffux / GraffitiXR", filenames[2])
        with zipfile.ZipFile(layered) as pack:
            manifest = json.loads(pack.read("manifest.json"))
            assert manifest["format"] == "com.hereliesaz.stencilpack"
            assert manifest["order"] == "bottom-to-top"
            result = Image.new("RGBA", (256, 256))
            for layer in manifest["layers"]:
                with Image.open(io.BytesIO(pack.read(layer["file"]))) as image:
                    result = Image.alpha_composite(result, image.convert("RGBA"))
            with Image.open(composite) as image:
                assert result.tobytes() == image.convert("RGBA").tobytes()
        device.tap("Next: Print", "Print →")
        device.tap("Show download controls", "Downloads ↓")
        pdf = device.save("Download all sheets print PDF", filenames[3])
        assert len(PdfReader(pdf, strict=True).pages) > 1
        device.tap("Next: Cutter", "Cutter →")
        for format, filename in zip(("SVG", "DXF", "HPGL", "G-code"), filenames[4:]):
            # A stage always starts at the top; move back up before changing format.
            for _ in range(8):
                adb("shell", "input", "swipe", str(device.width // 2), str(int(device.height * .61)),
                    str(device.width // 2), str(int(device.height * .84)), "200")
            device.tap(format)
            device.tap("Show download controls", "Downloads ↓")
            exported = device.save("Download all sheets " + format, filename)
            if format == "SVG":
                assert ET.parse(exported).getroot().tag.endswith("svg")
            else:
                markers = {"DXF": ("SECTION", "EOF"), "HPGL": ("IN;", "PU"), "G-code": ("G0", "G1")}
                assert all(marker in exported.read_text() for marker in markers[format])
        print("PASS: Android photo import, isolation, stages and all native saves", flush=True)
    finally:
        try:
            device.shot("last")
        finally:
            (out / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "3000", check=False))


if __name__ == "__main__":
    main()
