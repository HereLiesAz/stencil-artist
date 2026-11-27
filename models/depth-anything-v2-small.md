# On-demand depth model

- Model: Depth Anything V2 Small (relative inverse depth).
- Origin: [Depth Anything V2 Small](https://huggingface.co/depth-anything/Depth-Anything-V2-Small).
- ONNX weights: [onnx-community/depth-anything-v2-small](https://huggingface.co/onnx-community/depth-anything-v2-small).
- Licence: Apache-2.0 (model authors and ONNX distribution).
- Artifact: `onnx/model_quantized.onnx` (single-file quantised model, not the newer repository's external-data pair).
- Immutable revision: `f7421df0cc30f121782ab050d42f0a423291dcde`.
- SHA-256: `fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d`.
- Remote size: 27,258,801 bytes, from the upstream ONNX artifact pointer.
- Preprocessing: RGB in [0, 1], ImageNet mean/std, NCHW float32; aspect retained and dimensions rounded to ViT's 14-pixel patch multiple.
- Output: per-pixel relative disparity, normalised per image and resized to source dimensions. It is **not** a physical distance map.

The weights are deliberately **not committed to this repository or bundled into the APK/PWA**.
Android downloads them on first use, verifies the checksum, and caches the file privately. The
browser requests the same pinned artifact and the service worker caches successful downloads.

Depth and U²-Net are complementary, not substitutes. U²-Net supplies confident foreground and
background seeds, depth refines only ambiguous border pixels, then lighting alters the masked source
image *before* tonal separation. Failed model retrieval never removes the ability to make a stencil.

## Verified CPU comparison against MiDaS Small

[GitHub Actions run 45084318211](https://github.com/HereLiesAz/stencil-artist/actions/runs/45084318211)
executed both public ONNX files successfully on one Linux runner with ONNX Runtime 1.32.0,
two CPU inference threads, deterministic tensor inputs and one warm-up followed by three
measured passes. This compares **latency and model compatibility**, not depth accuracy.

| Model | Model bytes | Input | Warm median CPU inference |
|---|---:|---|---:|
| Depth Anything V2 Small, quantised | 27,258,801 | 518 × 518 | 669.6 ms |
| MiDaS v2.1 Small, float32 | 66,389,153 | 256 × 256 | 55.4 ms |

The Depth Anything V2 model also passed a non-square 1×3×266×518 input/output smoke test
and its SHA-256 check. The MiDaS model is MIT-licensed; its
[checkpoint](https://huggingface.co/Heliosoph/midas-small-onnx) is pinned at
`f3a5a5e4c852c7bfac10517767e5b0636cb42b92`, with SHA-256
`b0a5b3f12625137e626805167907fe0410665bec671685d59daaa2daab19f977`.

This result does **not** establish mask quality on photographs or performance on Android
and mobile browsers. The faster 256px MiDaS model trades away depth-map resolution; the
selected default remains Depth Anything V2 Small for the fine foreground boundaries and
surface detail sought by the stencil preprocessing phase. The shared `Inference` interface
keeps a later runtime/performance-based model switch possible.
