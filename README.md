# Stencil Artist

Turn any image into stencil layers to make stencils of ANY size.

A Compose Multiplatform app: Android, and an installable web app. The stencil tool began in
[GraffitiXR](https://github.com/HereLiesAz/GraffitiXR) and was carried over without OpenCV, so the
same Kotlin makes the same stencil on every target.

Status and roadmap: [`tasks/todo.md`](tasks/todo.md).

## What it does today

- Opens an image, removes its background on the device (U²-Net), and separates the subject into
  sheets, **by tone** (a grey ramp) or **by colour** (the picture's own paint colours).
- Any number of sheets, each with its own colour: greys by default in Tone mode, the colours found
  in the image in Colour mode.
- Depth-assisted background removal combines U²-Net saliency and relative depth around uncertain
  subject edges, preserving confident segmentation and falling back if depth is unavailable.
- Optional depth-based Lambert lighting (azimuth, elevation, intensity, ambient and depth strength)
  relights the **base image before any stencil sheets are generated**, with a base-image preview.
- Colour separation: the subject's colours are clustered (k-means in Oklab, deterministic, on a
  colour histogram so a 1600 px photo takes under a second on the JVM) into as many flat paint colours
  as there are sheets, up to 1024, and sprayed in an order of your choice: auto (dark base for a
  dark subject, light for a light one), light to dark, dark to light, or largest area first.
  An image with fewer distinct colours than sheets asked for makes that many sheets, not duplicates.
  Free Colour mode has a palette editor: select a sheet swatch and browse the full paint catalogue
  as a grid of uncapped, top-view spray cans. A tap opens a swipeable carousel with the whole can,
  the maker and colour name alongside it, and the edges of adjacent cans visible as navigation cues.
  The colour can be applied to that sheet, regenerating the stencil and subsequent exports. Brand
  filters and search narrow the gallery; the bounded lazy grid scrolls through every can
  without rendering all rows, while the carousel traverses every result. Exact hex and RGB sliders are available underneath for custom colours;
  duplicate colours are refused. **Use image colours again** restores the image-derived palette.
  The setting is `ColorParams.colors`. Can-based paint modes keep their own selections.
- Paints a stencil from real spray cans. In Colour mode, **Paint** is *Free* (any colours), *Pick for me*
  (you set the layer count and which brands or lines are allowed; the app matches the image's colours to
  distinct cans) or *My cans* (the same can-top grid and swipeable full-can carousel, plus a selected strip; the layers are
  exactly the chosen cans' distinct colours, and two cans of one colour make one layer). A **Paint list**
  shows each layer's can, code and how near it is (Exact, Close, Approximate); a can no pixel uses is
  noted as not needed and left off the lists unless you keep it. The print PDF is given a shopping list
  of the cans for the sheets it prints. Chosen cans, allowed brands and learned barcodes are kept on the
  device (SharedPreferences, `localStorage`).
- **Rack:** the exact uploaded transparent five-top spray-can PNG (724 × 2172)
  is stored byte-for-byte in Compose resources. The rack has ONE white background
  for the entire grid. Each visible can gets its OWN solid RGB colour circle behind
  its unmodified top-view image, drawn from the supplied strip without changing
  source pixels. The five top views cycle across entries.
  Only cans with an actual resolved RGB value appear in the rack or can carousel;
  unknown colours are excluded, not replaced with gray or guessed.
  **MTN 94 full-can carousel:** the other exact original 1122 × 1402 transparent
  PNG remains unchanged and sits over its independent solid paint-colour shoulder
  and white background. Other brands retain their existing side illustrations;
  retailer imagery is not embedded. If a user previously entered an RGB swatch,
  that can is visible until the swatch is cleared.
  Your colour stays in SharedPreferences (Android) or localStorage (web), keyed by the stable can
  identity, and may be corrected or cleared without changing the bundled catalogue. Saved colours
  are identified as user-entered and approximate, including in the print PDF. Retailer swatches (including
  [Spray Planet's MTN 94 selection](https://www.sprayplanet.com/collections/mtn-94)) are approximate
  references, not licensed source artwork or automatically imported colour measurements.
- Smoothing and edge closing for either mode; tonal bias and a forced dark or light base in Tone mode.
- A guided eight-stage studio separates source cleanup, lighting, layer planning, edge construction, inspection, image exports, printing and cutter output. Each stage explains the consequence of its controls instead of showing the entire configuration at once. A full-screen, blurred-and-dimmed processing overlay rotates concise stencil-making, cutting and painting tips and displays a determinate progress bar beneath them, based on completed processing stages rather than estimated elapsed time; matching cutting hazards and gradient-handling advice are also printed by affected details.
- A persistent source/result comparison (or single-image view) supports synchronized pinch-zoom and panning on both panes, plus explicit zoom and reset buttons. The result may show the processed image, finished stencil, or any sheet alone. Visual and touch behavior still require device verification.
- Exports the finished stencil as a transparent composite PNG; any selected sheet as its own
  transparent PNG; or every sheet plus the composite in one ZIP. Also exports a versioned
  [StencilPack](docs/STENCILPACK_V1.md) with individually named, aligned PNG sheets for
  Graffux and GraffitiXR. Every PNG uses the same paint
  order, sheet colours and alpha as the preview, at the current working-image pixel dimensions.
- Downloads one actual-size print PDF for the shown sheet or every sheet: a single instructions page,
  then per sheet an assembly map and the tiled pages. Nothing is trimmed: pages are laid left to right, top to
  bottom, each on top of the earlier ones, with the paper edge on thin guide marks the page underneath prints. A fixed
  3 mm border is unprintable, so at every join there is a 3 mm strip of blank paper (the top page's margin);
  the artwork either side is at its true place and cut lines simply continue across the strip. Printouts label trapped islands and
  delicate openings in either cutaway areas or surviving stock, whichever is nearer and safely fits without crossing a cut. Gradient-lift painting notes stay on surviving stock. Tile codes avoid cut lines; a note that cannot safely fit goes to a
  non-cutting reference page beside the affected detail. Pages with nothing to cut are skipped and
  shown as blank on the map.
- Optionally mounts the print on poster board (22 x 28 in, or a custom board of at least 18 in a side) with an
  uncut border of at least 1 in (default 2 in) on every side: the piece is divided into boards that butt exactly.
  The pages keep the normal overlap and form one grid over the boards laid edge to edge; where a page crosses a
  board edge it prints a dotted cut line exactly on that edge, and the offcut becomes the first piece of the
  next board. The stencil starts the border distance in from the board's edges.
- PDF export bounds pathological detail counts, diagnoses actual surviving components, and handles
  one stencil sheet at a time to keep memory use predictable.

- Traces every sheet into vector outlines and writes cutter files at a real physical size: SVG,
  DXF (R12), HPGL and G-code, whole or split across a cutting mat or sheet of paper.

Island planning: independent layer cut masks allow black or grey to be painted beneath a
lighter detail that existing later cuts already restore, eliminating unsupported stock islands
without a bridge. If full repainting is impossible, the app can add a negative-stroke bridge
only where later paint conceals its entire footprint. Small missing later-layer paint marks
can also be added when the processed source strongly supports their colour and the later cut creates no additional unsupported stock. Uncertain details remain flagged instead of silently omitted. It also locates
soft source-image gradients on cut edges and labels the appropriate edge for controlled
stencil lifting in the print PDF, with scrap-test guidance. **Stroke construction** offers
Classic (the original masks) and opt-in Brush strokes, which now reconstruct broad
later-layer openings from adjustable-width additive paint ribbons (1–24 working-image pixels)
following the processed image's luminance contours. Areas narrower than the chosen brush
keep their original solid, potentially blotchy, paint openings rather than forcing strokes. Small, thin features and the black base stay untouched; stencil
islands created by new gaps are filled back in. Explicit semantic reconstruction of
exposed islands is still outstanding. See [the implementation and limitations](docs/STROKE_ISLANDS.md).

## Cutter files

Set **Physical size**, then in **Cutter files** pick a format and choose **Whole**, a preset mat
or sheet, **Custom mat**, or **Roll**. Custom mats accept width, height, edge margin and tile
overlap in millimetres. Rolls accept width, edge and overlap; length is unlimited, so tiled
roll exports produce full-length strips. The panel reports the number of tiles and disables
downloads if a size/overlap is invalid or the plan exceeds its tile limit. Disconnected
pieces of concave shapes and clipped holes use independent closed cutter paths, not retraced
tile-border edges. Download the shown sheet or all sheets.

For **G-code**, select **Laser** or **Router** and set cutting feed, travel feed (mm/min),
controller-specific power/spindle S value, and passes. Router mode also exposes safe and
cut Z positions in millimetres. These settings are passed into actual exported G-code,
including every tile in ZIP exports. Confirm your machine's S range, cut depth and travel
motion; simulate or dry-run the output before enabling the tool. No machine is controlled
directly by the app.

| Format | For |
|---|---|
| SVG | Cricut Design Space, Brother CanvasWorkspace, Silhouette Studio (paid tiers), LightBurn, Inkscape |
| DXF (R12, millimetres) | Silhouette Studio's free tier, CAD/CAM |
| HPGL (`.plt`) | Roll plotters and vinyl cutters |
| G-code | GRBL lasers and routers (outline only; configurable feed, power, passes and router Z) |

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

**Mount on** chooses *Paper only*, *Poster board 22 x 28* or a *Custom board* (width and height in inches, at
least 18 each) with a *Border* in inches (default 2, at least 1). The piece is divided into boards whose cut
areas (the board less the border; 18 x 24 in for poster board at 2 in) butt exactly, in the orientation that
needs fewer boards (portrait on a tie). Think of a sheet's boards placed edge to edge as one big rectangle: one grid of pages covers it, starting at board 1's
top-left corner with page A1's paper edges on the board's edges, at the normal step (paper less 4 mm, later pages on top,
untrimmed). Where a page crosses a board edge it prints a **dotted cut line** exactly on that edge, across the page's
printed width, labelled once "cut here - board edge". The dots are always the opposite colour to what they cross (dark
over blank paper or light fill, white over a dark fill). Glue the page on its board, then cut the paper along the dotted
line only (the board is not cut), and use the offcut as the first piece of the next board, so a corner page gives pieces for
up to three other boards. The right and bottom edges of the whole rectangle get the same line; that offcut is waste.
Labels name the boards a page touches ("Sheet 2 of 5 - Boards 1+2 - C1"). Page A1 is always printed when anything is.
Painting goes left to right, top to bottom, so a board carries registration **tabs** only on its left and top
border, where it faces a board already painted: one or two 1 in wide, at most 0.5 in deep (never more than half the
border) windows cut to the neighbouring board's artwork for that sheet. Slide the board until the paint on the wall
shows exactly through its tabs; paint that lands through a tab falls where that colour goes anyway. Positions that
hold an edge of the artwork are preferred, since an edge gives something to line up to; a side with no usable
position gets no tab and the map says so. A board with nothing to cut on a sheet is skipped and shown as blank on the map. Use heavyweight poster board or
4-ply or 6-ply railroad board; thin poster board tears. Foam board and tri-fold boards are not offered.

Print at 100% / actual size. Lay the pages left to right, top to bottom, each later page on top of
the earlier ones, and slide it until its paper edge touches the guide marks the page underneath prints (ticks along
the edge, an L where a paper corner lands). Paper-only, sheets advance by the paper size less 4 mm: the top page's
paper edge lies 1 mm inside the under page's printed area, where the guide is. The top page's unprinted 3 mm margin
hides what lies under it, so each join has a 3 mm blank strip; nothing is shifted to hide it. The first page of the
PDF says so, with a diagram and a 100 mm check bar. Checked here with pypdf (strict), PyMuPDF renders and an
overlay of every page in stacking order (mounted: all of a sheet's boards composited with the board edges marked); **not printed on a real printer.**

**Paint list.** When the print is given a shopping list (`PrintOptions.shopping`, one item per layer in paint order), the
instructions gain a "Paint" block: per layer a swatch in the can's colour with a dark outline (so pale cans show), "Layer N",
brand, line and name, the maker's code, the barcode digits when known, and the match as words (exact, close or approximate;
"colour sampled, approximate" for our own samplings), then up to 3 equivalent cans of the same colour ("also: ... and N more").
It says to spray in that order, base first, and a step tells you to check colours against a real can's cap or a swatch card
before buying, since screen colours are approximate. Rows stay on page 1 while it scales no smaller than 0.9; the rest continue
on extra pages titled "Paint, continued" (`PrintPlan.instructionPages` counts them). No list text prints below 7 pt. Accented
Latin-1 letters in names (e-acute, n-tilde, u-umlaut) and curly quotes print; other Latin letters fold to ASCII; other scripts print "?".
Without a list the PDF is byte for byte as before.

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

Processing order: **source image → depth-assisted background removal → relit base image → separator
(by tone or by colour) → stencil sheets**. Changing the light always regenerates the sheets from the new base.


## Paint catalogue

`models/paints.tsv` lists 3,479 spray cans from 12 brands (Rust-Oleum 635, Montana Cans 580, MTN 543,
Loop Colors 305, Flame 273, Kobra 264, Clash 263, Krylon 229, Molotow 204, Ironlak 102, Liquitex 50,
Dupli-Color 31): brand, line, name, finish, code, colour value and barcode. It is a 134 kB tab-separated
text file that ships by the same route as the models (Android assets, `models/` on the web) and is read
by `core.paint.PaintCatalogue`, which searches it (ignoring case, accents and punctuation, so `rv 252`,
`RV-252` and `rv252` are one code), finds a can by barcode (UPC-A and EAN-13 are the same), and matches the
colours of an image to the nearest cans. Regenerate it with `python3 tools/catalogue/build.py`.

- 2,057 cans have a colour value (1,689 distinct). The other 1,422 (all of MTN, Flame, Molotow and Clash, and
  the effect finishes of other makers) have none: they can be listed, searched and scanned, not matched to
  a colour. Colour values are how a screen shows the paint, not the paint; 79 (Liquitex, Dupli-Color) are our
  own sampling of the maker's swatch and are flagged so.
- **Not for public release yet.** No licence to redistribute the data was found; `models/paints.NOTICE.txt`
  says where each brand's data came from. Check the makers' terms before shipping it.
- The scanner is wired into the My cans picker. Unknown barcodes can be linked to a known can in
  the same search/carousel, with that mapping persisted locally.

## Barcode scanner

**Scan a can** (`ScannerPanel`, built on `ScannerView`) reads the EAN-13, EAN-8, UPC-A or UPC-E barcode on a
spray can with the camera, so the cans the user owns can be added to their selection. The camera is used only
while a scan is on, and only after the user taps the button; nothing is recorded or sent anywhere. Every number
read must pass its check digit (`core.paint.GtinCheck`) or it is ignored, since a wrong can is worse than none;
UPC-A is reported as its EAN-13 form and UPC-E is expanded to the UPC-A it stands for; the same number is not reported
again for two seconds while it stays in view. A barcode can also be typed.

- **Permission.** Android asks for the camera the first time a scan starts (`CAMERA`; the camera is declared
  not required, so camera-less devices still install). A browser asks the same way. A refusal is shown as a
  message ("Camera blocked: allow camera access in your browser or app settings"), and typing still works.
- **Secure page.** Browsers only offer a camera to https pages (and localhost). Over plain http the scanner says
  so instead of failing silently. GitHub Pages is https.
- **Web decoding.** The browser's own `BarcodeDetector` is used when it lists all four retail formats (Chrome on
  Android, macOS and ChromeOS). Elsewhere (iPhone Safari, Firefox, Chrome on Windows and Linux) it falls back to
  [zxing-wasm](https://github.com/Sec-ant/zxing-wasm) (MIT, the reader build only; its licence is `zxing/LICENSE` in
  the web build). The fallback is not part of the app bundle: `scanner.js` loads it on the first scan, from `zxing/`
  next to the page, and the service worker keeps it for offline use. It is a 946 kB WebAssembly file (411 kB gzipped)
  and 37 kB of JavaScript (13 kB gzipped); a browser with a `BarcodeDetector` never downloads it. Frames are
  decoded about 8 times a second, scaled to at most 960 px on the long side.
- **Android decoding.** CameraX and ML Kit's on-device barcode scanner, the bundled model (no Play services download,
  works offline). It adds about 13.6 MB to the release APK (arm64 and armeabi-v7a, not minified), most of it ML Kit's
  native library.
- **Integrated:** The My cans picker scans and accepts manually typed barcodes, adds known cans
  without toggling off already-selected ones, and offers catalogue search to identify unknown codes.
  A recognized can without a colour value cannot form a stencil layer.
- **Tested** with a fake camera (a generated EAN-13, UPC-A and UPC-E picture played as video) in headless Chromium,
  on both decoders' paths as far as that browser allows, and in unit tests. **Not tested:** a real camera or can, an
  Android device, iPhone Safari, or a real `BarcodeDetector`.

## Layout

| Module | What it is |
|---|---|
| `core` | The stencil pipeline. Pure Kotlin (`jvm`, `wasmJs`), no platform dependencies. |
| `composeApp` | The app: UI and state, shared by Android and the web. Produces the web build. |
| `androidApp` | The Android wrapper: an Activity and a manifest. |
| `models` | Bundled U²-Net subject model and licence, and the paint catalogue; depth ONNX is downloaded on demand. |
| `tools/golden` | Generates the OpenCV fixtures the pipeline is tested against. |
| `tools/catalogue` | Builds `models/paints.tsv` from the research data in `source/`. |

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

## Android updates

The Android launcher checks for stable GitHub releases using AzNavRail 12.5. It sends no drawing or model data, and opens the release page only when the user chooses Update. The web build is unchanged.
