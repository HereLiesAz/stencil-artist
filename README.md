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
- Exports the finished stencil as a transparent composite PNG; any selected sheet as its own
  transparent PNG; or every sheet plus the composite in one ZIP. Every PNG uses the same paint
  order, sheet colours and alpha as the preview, at the current working-image pixel dimensions.
- Downloads actual-size tiled print PDFs for every stencil sheet. Printouts label trapped islands,
  delicate openings, and tile joins inside uncut material. When a note cannot safely fit,
  a non-cutting reference page shows the affected detail immediately beside its tip.
- PDF export bounds pathological detail counts, diagnoses actual surviving components, and handles
  one stencil sheet at a time to keep memory use predictable.

- Traces every sheet into vector outlines and writes cutter files at a real physical size: SVG,
  DXF (R12), HPGL, G-code and vector PDF, whole or split across a cutting mat or sheet of paper.

Not yet: bridges for islands, colour separation, custom mat sizes in the UI. See the roadmap.

## Cutter files

Set **Physical size**, then in **Cutter files** pick a format and, if the piece is larger than
the machine takes, a mat or paper size to split it across. Download the shown sheet or all of them.

| Format | For |
|---|---|
| SVG | Cricut Design Space, Brother CanvasWorkspace, Silhouette Studio (paid tiers), LightBurn, Inkscape |
| DXF (R12, millimetres) | Silhouette Studio's free tier, CAD/CAM |
| HPGL (`.plt`) | Roll plotters and vinyl cutters |
| G-code | GRBL lasers and routers (outline only; default feed and power, edit before running) |
| PDF | Printing and cutting by hand; split across pages it has an assembly map and registration marks |

The formats are checked by parsing them back and with outside readers (ezdxf, pypdf, a browser).
**None has been loaded into the cutter applications named above or run on a machine.** Cut a test
piece first. Islands (a region enclosed by a cut) are traced faithfully and will fall out; nothing
adds bridges yet.

## PNG exports

In **Raster PNG**, download the composite, select a swatch to download one sheet, or download
all sheet PNGs and the composite together as `stencil-pngs.zip`. The first sheet is the base
and subsequent sheets follow the paint order. Unpainted pixels are transparent, not white.
Export reuses the final separation, including any depth-assisted background removal or relighting
performed **before** stencil generation; neither export mode reprocesses the image.

PNGs use the current working-image dimensions (up to 1600 pixels on the longest edge), without
upscaling or changing cut geometry. Since the ZIP writer holds files in memory, bundle payloads
over 32 MiB are rejected with a message; the individually selected sheet and composite exports
remain available. The Android Save As chooser is held to one outstanding save at a time.

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
