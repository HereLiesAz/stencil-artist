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

## MiDaS comparison

The alternative under review is MiDaS v2.1 Small (256px ONNX, released as `model-small.onnx`).
Its unquantised ONNX model is larger and its input resolution smaller than the quantised Depth
Anything V2 Small model. Equivalent on-device latency and real-image mask-quality measurements
have not yet been verified. Do not pass published results on different datasets off as an
app-specific benchmark. The depth processing path stays behind `Inference` so benchmarking and
swapping a future model will not alter the stencil separation contract.
