#!/usr/bin/env python3
"""
Reproducible CPU smoke/latency comparison of Depth Anything V2 Small and MiDaS v2.1 Small.

Uses the same 2-thread ONNX Runtime CPU provider and a deterministic RGB-like synthetic tensor.
This measures model load time, input/output compatibility and inference latency, NOT depth
accuracy or segmentation quality. Real-image quality still requires labeled evaluation.
"""
import hashlib
import os
import statistics
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from huggingface_hub import HfApi, hf_hub_download


DEPTH_REVISION = "f7421df0cc30f121782ab050d42f0a423291dcde"
DEPTH_SHA256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d"
MODELS = (
    ("Depth Anything V2 Small quantized", "onnx-community/depth-anything-v2-small",
     "onnx/model_quantized.onnx", DEPTH_REVISION, 518),
    ("MiDaS v2.1 Small float32", "Heliosoph/midas-small-onnx", None, "main", 256),
)


def verify_depth_checksum(path: str) -> None:
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != DEPTH_SHA256:
        raise ValueError("Depth Anything checkpoint checksum differs from its pinned upstream release")


def bench_one(name: str, repo: str, filename: str | None, revision: str, side: int) -> None:
    if filename is None:
        files = HfApi().list_repo_files(repo)
        candidates = sorted(file for file in files if file.endswith(".onnx"))
        if not candidates:
            raise RuntimeError("MiDaS ONNX file not found")
        filename = candidates[0]
    started = time.perf_counter()
    path = hf_hub_download(repo_id=repo, filename=filename, revision=revision)
    download_seconds = time.perf_counter() - started
    if name.startswith("Depth Anything"):
        verify_depth_checksum(path)

    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    started = time.perf_counter()
    session = ort.InferenceSession(path, sess_options=options, providers=["CPUExecutionProvider"])
    session_seconds = time.perf_counter() - started
    model_input = session.get_inputs()[0]
    model_output = session.get_outputs()[0]
    print("MODEL:", name, flush=True)
    print("REMOTE:", repo, filename, "revision:", revision, flush=True)
    print("SIZE_BYTES:", Path(path).stat().st_size, flush=True)
    print("DOWNLOAD_SECONDS:", round(download_seconds, 2), flush=True)
    print("SESSION_SECONDS:", round(session_seconds, 2), flush=True)
    print("INPUT:", model_input.name, model_input.shape, model_input.type, flush=True)
    print("OUTPUT:", model_output.name, model_output.shape, model_output.type, flush=True)

    dimensions = [1, 3, side, side]
    if len(model_input.shape) != 4:
        raise ValueError("Expected an NCHW depth input")
    for index, value in enumerate(model_input.shape):
        if isinstance(value, int) and value > 0:
            dimensions[index] = value
    pixels = np.random.default_rng(1776).normal(size=dimensions).astype(np.float32)
    times = []
    for i in range(4):
        started = time.perf_counter()
        result = session.run(None, {model_input.name: pixels})[0]
        millis = (time.perf_counter() - started) * 1000
        times.append(millis)
        print("PASS:", i, "MS:", round(millis, 1), "SHAPE:", result.shape, flush=True)
    print("COLD_INFERENCE_MS:", round(times[0], 1), flush=True)
    print("WARM_MEDIAN_MS:", round(statistics.median(times[1:]), 1), flush=True)

    if name.startswith("Depth Anything") and all(not isinstance(n, int) for n in model_input.shape[2:]):
        # The Kotlin path uses aspect-preserved dimensions rounded to multiples of 14.
        portrait = np.zeros((1, 3, 266, 518), dtype=np.float32)
        result = session.run(None, {model_input.name: portrait})[0]
        if result.shape[-2:] != (266, 518):
            raise ValueError("Model returned a depth plane incompatible with the source aspect ratio")
        print("DYNAMIC_INPUT: 1x3x266x518 ->", result.shape, flush=True)


def main() -> None:
    print("ONNX_RUNTIME_VERSION:", ort.__version__, flush=True)
    for name, repo, filename, revision, side in MODELS:
        bench_one(name, repo, filename, revision, side)


if __name__ == "__main__":
    main()
