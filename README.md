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
- Downloads one actual-size print PDF for the shown sheet or every sheet: a single instructions page,
  then per sheet an assembly map and the tiled pages. Pages are used edge to edge except a fixed
  3 mm border the printer cannot print; neighbouring pages overlap by a fixed 1 mm, and that band
  carries thin L and tick alignment marks, identical on both pages. Printouts label trapped islands,
  delicate openings and tile codes inside uncut material; a note that cannot safely fit goes to a
  non-cutting reference page beside the affected detail. Pages with nothing to cut are skipped and
  shown as blank on the map.
- PDF export bounds pathological detail counts, diagnoses actual surviving components, and handles
  one stencil sheet at a time to keep memory use predictable.

- Traces every sheet into vector outlines and writes cutter files at a real physical size: SVG,
  DXF (R12), HPGL and G-code, whole or split across a cutting mat or sheet of paper.

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

Printing on paper is the separate **Print PDF** section (see below), not a cutter format.

The formats are checked by parsing them back and with outside readers (ezdxf, pypdf, a browser).
**None has been loaded into the cutter applications named above or run on a machine.** Cut a test
piece first. Islands (a region enclosed by a cut) are traced faithfully and will fall out; nothing
adds bridges yet.

## Print PDF

Set **Physical size**, then in **Print PDF** choose paper (US Letter, Legal, Tabloid, A4, A3, A2 or a
custom size), portrait or landscape, and download the shown sheet or all sheets as one PDF. Size can
also be set as **sheets wide** or **sheets high** (decimals allowed), and **Fit to whole pages** nudges
the width so the last column or row is not a sliver (under 15% of a page). The section shows how many
pages a sheet takes and how many are skipped as blank.

Print at 100% / actual size. Each page keeps a fixed 3 mm border unprinted; after trimming the left and
top border of every page that has a neighbour there, lay each page over its neighbour so the 1 mm
bands coincide and the marks sit on the marks beneath, then tape. The first page of the PDF says so,
with a diagram and a 100 mm check bar. Checked here with pypdf (strict), PyMuPDF renders and an overlay
of two neighbouring pages; **not printed on a real printer.**

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
