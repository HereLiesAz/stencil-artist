# Stencil Artist

Turn any image into stencil layers to make stencils of ANY size.

A Compose Multiplatform app: Android, and an installable web app. The stencil tool began in
[GraffitiXR](https://github.com/HereLiesAz/GraffitiXR) and was carried over without OpenCV, so the
same Kotlin makes the same stencil on every target.

Status and roadmap: [`tasks/todo.md`](tasks/todo.md).

## What it does today

- Opens an image, removes its background on the device (U²-Net), and separates the subject into
  sheets by tone.
- Any number of sheets, each with its own colour (a grey ramp by default).
- Depth-assisted background removal combines U²-Net saliency and relative depth around uncertain
  subject edges, preserving confident segmentation and falling back if depth is unavailable.
- Optional depth-based Lambert lighting (azimuth, elevation, intensity, ambient and depth strength)
  relights the **base image before any stencil sheets are generated**, with a base-image preview.
- Smoothing, edge closing, tonal bias, and a forced dark or light base.
- Shows the improved base image, the finished piece, or any one sheet alone.
- Downloads actual-size tiled print PDFs for every stencil sheet. Printouts label trapped islands,
  delicate openings, and tile joins inside uncut material. When a note cannot safely fit,
  a non-cutting reference page shows the affected detail immediately beside its tip.
- PDF export bounds pathological detail counts, diagnoses actual surviving components, and handles
  one stencil sheet at a time to keep memory use predictable.

Not yet: PNG/vector/cutter export. See the roadmap.

## Depth model

Depth Anything V2 Small uses the Apache-2.0 quantised ONNX checkpoint from
[onnx-community/depth-anything-v2-small](https://huggingface.co/onnx-community/depth-anything-v2-small).
Its immutable revision and SHA-256 are recorded in `models/depth-anything-v2-small.md`.
Android verifies and persists the download in private app storage; the PWA caches the downloaded
model using its service worker. The model is not included in the app bundle. Depth assistance is
enabled for background removal; lighting is opt-in. If the model cannot load, the standard U²-Net
mask and original unlit source remain available.

Processing order: **source image → depth-assisted background removal → relit base image → tonal
separator → stencil sheets**. Changing the light always regenerates the sheets from the new base.


## Layout

| Module | What it is |
|---|---|
| `core` | The stencil pipeline. Pure Kotlin (`jvm`, `wasmJs`), no platform dependencies. |
| `composeApp` | The app: UI and state, shared by Android and the web. Produces the web build. |
| `androidApp` | The Android wrapper: an Activity and a manifest. |
| `models` | Bundled U²-Net subject model and licence; depth ONNX is downloaded on demand. |
| `tools/golden` | Generates the OpenCV fixtures the pipeline is tested against. |

## Build

~~~
./gradlew :core:jvmTest :core:wasmJsNodeTest       # tests
./gradlew :composeApp:wasmJsBrowserDevelopmentRun  # web, served locally
./gradlew :composeApp:wasmJsBrowserDistribution    # web, static bundle in composeApp/build/dist
./gradlew :androidApp:assembleDebug                # Android APK
~~~

Android builds need the SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`).

## Automation

Workflows come from the central [`HereLiesAz/workflows`](https://github.com/HereLiesAz/workflows)
catalog; see `.github/WORKFLOW_POLICY.md`.
