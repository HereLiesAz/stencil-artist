#!/usr/bin/env python3
"""Generates core/src/commonTest/.../Golden.kt: fixtures computed by OpenCV itself.

The Kotlin raster operations claim to reproduce the OpenCV calls GraffitiXR's StencilProcessor
made. This script makes those calls (same functions, same arguments) on fixed pseudo-random
inputs and writes inputs and outputs as Kotlin constants, so the claim is tested on every target
without OpenCV being a dependency of the build.

The end-to-end fixtures run a line-for-line transcription of StencilProcessor.stencil and
StencilTones (GraffitiXR, core/common/.../util) and record the tone of every pixel.

Usage: pip install opencv-python-headless numpy && python3 tools/golden/generate.py
"""
import os
import cv2
import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "commonTest", "kotlin",
                   "com", "hereliesaz", "stencilartist", "core", "Golden.kt")


def blobs(rng, w, h, n=6):
    """A smooth-ish 8-bit plane: a few soft discs on a gradient, plus noise."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    img = xx / w * 90 + yy / h * 40
    for _ in range(n):
        cx, cy, r, v = rng.uniform(0, w), rng.uniform(0, h), rng.uniform(3, w / 3), rng.uniform(-120, 160)
        img += v * np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * r * r)))
    img += rng.normal(0, 12, (h, w))
    return np.clip(img, 0, 255).astype(np.uint8)


def hexs(a):
    return bytes(np.asarray(a, dtype=np.uint8).ravel()).hex()


def kotlin_hex(name, a):
    s = hexs(a)
    chunks = [s[i:i + 100] for i in range(0, len(s), 100)] or [""]
    body = " +\n        ".join('"%s"' % c for c in chunks)
    return "    const val %s =\n        %s\n" % (name, body)


# --- StencilTones, transcribed from GraffitiXR -------------------------------------------------

def thresholds(hist):
    total = int(hist.sum())
    if total == 0:
        return None

    def quantile(f):
        target = f * total
        seen = 0
        for v in range(256):
            seen += int(hist[v])
            if seen >= target:
                return float(v)
        return 255.0

    c = [quantile(1 / 6), quantile(3 / 6), quantile(5 / 6)]
    for _ in range(50):
        s = [0.0] * 3
        n = [0] * 3
        for v in range(256):
            k = int(hist[v])
            if k == 0:
                continue
            best = 0
            for j in (1, 2):
                if abs(v - c[j]) < abs(v - c[best]):
                    best = j
            s[best] += float(v) * k
            n[best] += k
        moved = False
        for j in range(3):
            if n[j] == 0:
                continue
            nxt = s[j] / n[j]
            if abs(nxt - c[j]) > 1e-6:
                moved = True
            c[j] = nxt
        if not moved:
            break
    c.sort()
    lo = min(max(int((c[0] + c[1]) / 2), 0), 254)
    hi = min(max(int((c[1] + c[2]) / 2), lo + 1), 255)
    return lo, hi


def is_dark_by_area(luma_hist):
    dark = light = 0
    for level in range(256):
        n = int(luma_hist[level])
        if n == 0:
            continue
        adjusted = (level + -40 - 128) * 2.0 + 128
        if adjusted >= 128.0:
            light += n
        else:
            dark += n
    return dark >= light


def stencil_processor(rgba):
    """StencilProcessor.stencil up to the ranks, on an RGBA mat. Returns (ranks, dark)."""
    h, w = rgba.shape[:2]
    r, g, b, a = cv2.split(rgba)
    v = cv2.max(cv2.max(r, g), b)
    v = cv2.medianBlur(v, 9)
    hist = np.zeros(256, np.int64)
    luma_hist = np.zeros(256, np.int64)
    for y in range(h):
        for x in range(w):
            if a[y, x] > 0:
                hist[v[y, x]] += 1
                luma = (0.299 * int(r[y, x]) + 0.587 * int(g[y, x]) + 0.114 * int(b[y, x])) / 255.0
                luma_hist[min(max(int(luma * 255.0), 0), 255)] += 1
    lo, hi = thresholds(hist)
    dark = is_dark_by_area(luma_hist)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    if dark:
        _, middle = cv2.threshold(v, lo, 255, cv2.THRESH_BINARY)
        _, top = cv2.threshold(v, hi, 255, cv2.THRESH_BINARY)
    else:
        _, middle = cv2.threshold(v, hi, 255, cv2.THRESH_BINARY_INV)
        _, top = cv2.threshold(v, lo, 255, cv2.THRESH_BINARY_INV)
    middle = cv2.morphologyEx(middle, cv2.MORPH_CLOSE, kernel)
    top = cv2.morphologyEx(top, cv2.MORPH_CLOSE, kernel)
    observed = a > 0
    # filledSilhouette
    mask = np.where(observed, 255, 0).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    padded = cv2.copyMakeBorder(mask, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
    flood_mask = np.zeros((h + 4, w + 4), np.uint8)
    cv2.floodFill(padded, flood_mask, (0, 0), 255)
    silhouette = (mask != 0) | (padded[1:-1, 1:-1] == 0)
    # StencilTones.ranks, as paint-order positions: 0 base, 1 midtone, 2 secondary, 255 none.
    ranks = np.full((h, w), 255, np.uint8)
    ranks[silhouette] = 0
    seen = silhouette & observed
    ranks[seen & (middle != 0)] = 1
    ranks[seen & (top != 0)] = 2
    return ranks, dark


def subject(rng, w, h, bright):
    """An RGBA image: a blobby subject with a hole in it, on a transparent background."""
    base = blobs(rng, w, h)
    if bright:
        base = np.clip(base.astype(np.int32) + 110, 0, 255).astype(np.uint8)
    else:
        base = (base * 0.55).astype(np.uint8)
    rgb = np.dstack([base, np.roll(base, 3, 1), np.roll(base, 5, 0)])
    yy, xx = np.mgrid[0:h, 0:w]
    inside = ((xx - w / 2) / (w * 0.42)) ** 2 + ((yy - h / 2) / (h * 0.40)) ** 2 < 1
    hole = (xx - w * 0.6) ** 2 + (yy - h * 0.45) ** 2 < 9
    alpha = np.where(inside & ~hole, 255, 0).astype(np.uint8)
    rgb[alpha == 0] = 0  # what a premultiplied bitmap holds where it is transparent
    return np.dstack([rgb, alpha])


def main():
    rng = np.random.default_rng(20261122)
    out = ["// GENERATED by tools/golden/generate.py from OpenCV %s. Do not edit; regenerate.\n" % cv2.__version__,
           "package com.hereliesaz.stencilartist.core\n\n",
           "/** Inputs, and what OpenCV made of them. See tools/golden/generate.py. */\n",
           "internal object Golden {\n"]

    w, h = 37, 29
    plane = blobs(rng, w, h)
    out.append("    const val PLANE_W = %d\n    const val PLANE_H = %d\n" % (w, h))
    out.append(kotlin_hex("PLANE", plane))
    for k in (3, 5, 9):
        out.append(kotlin_hex("MEDIAN_%d" % k, cv2.medianBlur(plane, k)))

    mask = np.where(blobs(rng, w, h) > 120, 255, 0).astype(np.uint8)
    out.append(kotlin_hex("MASK", mask))
    for k in (2, 3, 4, 5, 7):
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k, k))
        out.append(kotlin_hex("CLOSE_%d" % k, cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)))

    padded = cv2.copyMakeBorder(mask, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=0)
    cv2.floodFill(padded, np.zeros((h + 4, w + 4), np.uint8), (0, 0), 255)
    filled = np.where((mask != 0) | (padded[1:-1, 1:-1] == 0), 255, 0)
    out.append(kotlin_hex("FILLED", filled))

    sw, sh = 48, 40
    out.append("    const val SUBJECT_W = %d\n    const val SUBJECT_H = %d\n" % (sw, sh))
    for name, bright in (("DARK", False), ("LIGHT", True)):
        rgba = subject(rng, sw, sh, bright)
        ranks, dark = stencil_processor(rgba)
        assert dark == (not bright), "fixture %s was judged dark=%s" % (name, dark)
        assert len(set(ranks.ravel().tolist())) == 4, "fixture %s does not use every tone" % name
        out.append(kotlin_hex("SUBJECT_%s_RGBA" % name, rgba))
        out.append(kotlin_hex("SUBJECT_%s_RANKS" % name, ranks))
    out.append("}\n")
    with open(OUT, "w") as f:
        f.write("".join(out))
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
