# Stencil Artist — plan

Source: GraffitiXR `StencilTones.kt`, `StencilProcessor.kt`, `SubjectIsolator.kt`, `StencilTonesTest.kt`
(copied; GraffitiXR is not modified).

Status: **original planned phases implemented; approved 3D extrusion/scene-lighting work in progress.** What has not been verified on real hardware is listed under Review.

## Guided studio redesign (2026-11-23)

- [x] Split the one-screen settings column into eight named, focused stages with context explaining each decision; preserve the underlying processing and export code.
- [x] Add an original/result comparison with shared, fitted-image-bounded zoom and pan, plus single-view and explicit zoom controls.
- [x] Show rotating creation/cutting/painting advice over a blurred, dimmed full-screen loading overlay, with a determinate horizontal progress indicator below the tip reflecting finished pipeline stages; permit cutaway or retained stock for cutting notes and require retained stock for painting notes. Preserve a reference-page fallback for unsafe locations.
- [x] Put advanced lighting and edge-adjustment sliders behind deliberate disclosure; leave depth-lighting behavior unchanged pending design review.
- [ ] Visually inspect both wide web and narrow Android layouts, including keyboard and touch input, and verify pinch-pan, clipped zoom and side-by-side accuracy on devices.
- [x] Approve the 3D extrusion, actual scene lighting and rendered-source stencil architecture. See `docs/DEPTH_RELIGHT_DISCUSSION.md`.

## True 3D relief and lighting (approved 2026-11-24)

- [ ] Connect the shared closed relief mesh to a production scene renderer. Foundation added: depth-displaced front, silhouette walls/back cap, UVs, mask holes and disconnected solids, common tests (including all 512 3×3 masks). Not checked off: no app caller yet.
- [ ] Generate bounded geometry from the masked image and normalized model depth without erasing thin source features or silently changing source/export resolution.
- [ ] Render an orbitable 3D scene preview and a separately locked source-aligned orthographic view; start with directional light plus ambient, actual visibility and self-shadowing. Verify silhouette/alpha and non-square framing.
- [ ] Make the rendered RGBA image the prepared-image input to the existing tone/colour separator; retain original-lighting and no-depth fallback, cancellation/generation guards, and exact preview/export correspondence.
- [ ] Add multiple positioned directional, point and spot lights, handles, key/fill/rim presets, colour/intensity/softness and shadow-only/combined views, optional background receiver.
- [ ] Add thickness, relief strength, smoothing, flatten/clamp, depth inversion/remapping and artist depth corrections; separate material/photographic-shadow choices from geometry.
- [ ] Add separation-boundary and island/cut-risk diagnostics from the same committed render.
- [ ] Verify Android and Wasm scene rendering, visual comparison, cancellation, transparent/empty/flat inputs, shadow reversal/occlusion, deterministic results, performance and hardware usability.

## Decisions (answered)

- GraffitiXR keeps its stencil tool untouched; this is a copy.
- v1 = port + adjustable sheets + per-sheet PNG + tiled true-scale PDF + CNC vector export +
  depth map with adjustable lighting.
- Subject isolation: u2netp ONNX on both targets.
- PWA hosted on GitHub Pages.
- Islands/bridging: deferred, to be designed later.
- **Colour stencils are coming, and the sheet count is the user's to set.** Nothing may assume
  sheets are greys or that there are three (or any fixed number) of them.

## Architecture

~~~
stencil-artist/
  core/         KMP library, commonMain only, no platform deps
                tone/     StencilTones (N sheets), k-means thresholds, dark/light call
                raster/   median blur, morphological close, flood fill   (replaces OpenCV)
                light/    current: depth -> normals -> relit luminance; approved: mesh -> scene -> render
                vector/   contour trace, simplify, islands
                export/   PNG planes, PDF writer + tiler, SVG, DXF, HPGL, G-code
  composeApp/   Compose Multiplatform UI and state: commonMain + androidMain + wasmJsMain
                expect/actual: image pick + decode, pixels -> ImageBitmap; later ONNX, save/share
  androidApp/   Activity + manifest (AGP 9 keeps the application plugin out of KMP modules)
  tasks/        todo.md, lessons.md
~~~

- Package `com.hereliesaz.stencilartist`.
- **No OpenCV.** Median, close and flood fill are rewritten in pure Kotlin so one implementation
  serves Android and Wasm. The only platform code is ONNX inference and file I/O.
- Pipeline is byte/int-array in, byte/int-array out; the UI owns bitmaps.
- **`Separation` is the seam.** It is a list of `Sheet(color: ARGB)` in paint order plus a
  per-pixel sheet index. Preview, PNG, vectoriser and every exporter read only that. How it was
  made is the separator's business: `TonalSeparator` today, a colour separator later, sharing
  nothing but the output type. The numeric caps are `TonalSeparator`'s 256 (the levels 8-bit
  brightness has) and `ColorSeparator`'s 1024 (a guard on memory and time, not a stencil limit).
  `ColorSeparator` exists now and the claim held: see phase 13.
- `core` targets `jvm` (consumed by Android, fastest tests) and `wasmJs`.

## Phases

Each phase is a commit series on `ccr-14f6f879-ovc7dn`, one draft PR, verified before the next.

- [x] **0. Scaffold (core)** — Gradle 9.9.1, Kotlin 2.4.21, `:core` KMP module, version catalog.
      App modules, README and workflow request land with phase 3 / 11.
- [x] **1. Core port** — `Tones` + pure-Kotlin `Raster` (median, close, hole fill);
      `StencilTonesTest` ported to `commonTest`; raster ops held to fixtures generated by OpenCV
      itself (`tools/golden/generate.py`).
- [x] **2. Adjustable sheets** — any sheet count, any sheet colours (`TonalParams`): smoothing,
      edge closing, tonal bias, forced dark/light base. Defaults reproduce GraffitiXR's
      `StencilProcessor` pixel for pixel on a dark and a light cut-out subject (golden test against
      a transcription of it running on real OpenCV).
- [x] **3. App shell** — pick image, preview, sheet strip (All + one swatch per sheet), sheet
      stepper, base, smoothing, closing, bias. Monochrome dark UI. Web build driven in headless
      Chromium; Android builds (`assembleDebug`) but has not been run on a device.
- [x] **4. Subject isolation** — u2netp (`models/`, one copy, packaged into both builds). Pre- and
      post-processing in `core` (`Saliency`, `Resample`, held to OpenCV fixtures); inference behind
      `Inference` (ONNX Runtime on Android, ONNX Runtime Web loaded on first use). On by default;
      no subject or no runtime falls back to the whole frame with a note. Verified in headless
      Chromium. Android inference compiles and packages but has not been run on a device.
- [x] **5. Depth + lighting** — Depth Anything V2 Small (Apache-2.0, quantised ONNX) is
      downloaded and cached on first use. U²-Net's ambiguous background-removal boundaries are
      refined with depth; normals drive Lambert + ambient lighting on the **base image before**
      stencil separation. Controls: azimuth, elevation, intensity, ambient, depth strength,
      depth-assisted mask toggle, and processed-base preview. Shared tests verify preprocessing,
      alpha preservation, silhouette refinement and light direction. A CI CPU benchmark confirmed
      both Depth Anything V2 Small and MiDaS Small ONNX models and non-square depth inputs.
      **Not device-verified:** Android/model loading and PWA browser inference; tracked under review.
- [x] **6. Raster export** — native-resolution, transparent PNG for a selected sheet or the
      finished composite, plus one ZIP containing all sheet PNGs in paint order and the composite.
      Uses existing `Separation.render`, Android Bitmap and web Skia encoders, the existing
      `Zip.store` archive writer, and the platform save/download interface. ZIP payloads are
      bounded at 32 MiB; individual PNGs are always available. Existing geometry/alpha render
      tests are extended with opaque and semi-transparent, non-grey and non-three-sheet fixtures.
      Export is disabled while separation regeneration is pending or a save chooser is open.
- [x] **7. Vectorise** — `core/vector`: exact pixel-edge tracing with holes and nested islands,
      Douglas-Peucker simplify, speck filter, Chaikin smoothing. Tracing round-trips masks exactly.
- [x] **8. Cutter export** — `core/export`: SVG (filled or dark cut lines), DXF R12, HPGL, G-code;
      true physical size (PDF is the single Print PDF, phase 9); split across 12x12/12x24 mats, Letter, A4, A3 (custom and
      roll exist in core, not in the UI). Each text format is parsed back in tests; DXF opened
      with ezdxf, PDF with pypdf, SVG rendered in Chromium. Downloaded from the running web app
      and re-validated. **Not loaded into any cutter application or run on a machine.**
- [x] **9. Print PDF** — one exporter (`PrintPdf`) for the whole set: an instructions page, then per
      sheet an assembly map, true-size vector tile pages and reference pages for cutting tips that
      cannot be placed. Fixed 3 mm printer border. Nothing is trimmed: later pages lie on top of earlier
      ones (left to right, top to bottom), sheets advancing by paper size less 4 mm so the top page's paper
      edge is 1 mm inside the under page's printed area, where the under page prints guide marks (ticks and
      Ls) just outside the edge; each join has a 3 mm blank strip (the top page's margin), artwork stays at
      its true place; blank tiles are skipped. Sizing by width, sheets wide or sheets
      high, with a sliver snap. The raster "Print with cutting tips" exporter and the old vector PDF
      (single-page whole-piece and tiled) are gone; the tip detection lives on.
- [x] **9b. Mount on board** — `PrintOptions.mount` (`MountOptions(Board, borderMm)`): the piece is split
      into boards (cut area = board less the border; poster board 22 x 28 in at 2 in gives 18 x 24 in; board at
      least 18 in a side, border at least 1 in), in the orientation needing fewer boards. A sheet's boards laid
      edge to edge form one rectangle covered by ONE page grid from board 1's top-left corner, at the normal
      step (paper less 4 mm, later pages on top, untrimmed). A page crossing a board edge prints a dotted cut
      line exactly on the edge (dark, or white clipped to dark fills by sheet luminance); the user glues, cuts
      the paper along it and reuses the offcut on the next board; outer right/bottom edges are waste. Blank
      skipping is over the whole rectangle; the top-left page is always printed. Labels read
      "Sheet 2 of 5 - Boards 1+2 - C1"; the map shows the rectangle, grid and dotted lines; the instructions
      add board, glue and cut steps with a board diagram. `PrintSizing` gained boards wide/high and a board-aware
      snap. Boards register against each other on the wall by tabs: each board has up to two small windows in its left
      and top border (only where a board already painted lies that way), cut to the neighbour's artwork for the
      sheet, edge-bearing positions preferred; pages holding a tab are printed. Not tested on a real board or wall.
- [x] **10. PWA** — manifest, icons, service worker (shell network-first; WebAssembly, ONNX runtime
      and models cache-first on first use), offline start, installable. Deployed at
      https://hereliesaz.com/stencil-artist/ and checked there in headless Chromium: worker
      active, a photo isolated and stencilled, a fresh page starting offline from cache.
      Not checked: the install prompt on a real phone.
- [x] **11. Release wiring** — `github-pages-deploy` bound and deploying (file identical to
      photo-unroll's, so no catalog change). CI runs here with `ci-report`. Android: trigger file
      added and a profile proposed in HereLiesAz/workflows#216 (`raw-jks`, as camera-unroll);
      **merge that first**. The app version comes from `-PversionName`/`-PversionCode`, else
      `version.properties`. Release APKs are ARM-only (72 MB; all four ABIs was 153 MB).
      Not exercised: the central signing/publishing run, which happens only after merge.
- [x] **12. Review** — see Review below.
- [x] **13. Colour separation** — `ColorSeparator` (`core`) makes the same `Separation` as `TonalSeparator`.
      `ColorParams(sheets, smoothing, closing, order, colors)`: 1..1024 sheets, `ColorOrder` AUTO / LIGHT_TO_DARK /
      DARK_TO_LIGHT / LARGEST_FIRST, optional exact `colors` (nearest-colour assignment, no clustering). Same
      silhouette as the tonal stencil (shared `Subject`); per-channel median of the straight colour; weighted
      k-means in Oklab (own arithmetic-only conversion, so JVM and Wasm agree) on a 5-bit colour histogram with
      deterministic greedy seeding; every pixel assigned exactly; all sheets closed at once as a grey-scale closing
      of the index (proved equal to `Raster.close` per sheet in a test). `Raster.medianBlur` now tracks its median
      instead of scanning (same output, held by the OpenCV fixtures). App: "Separate by" Tone / Colour, each mode
      keeps its own settings, "Order" in Colour mode, "Base" and "Tonal bias" in Tone mode only; the sheet strip's
      number is black or white by contrast. `StencilState.setSheetColor` and `ColorParams.colors` are ready;
      **done in follow-up: the Free Colour-mode editor now browses top views of all catalogue cans
      in a bounded lazy grid with search/brand filters and the existing full-can swipe carousel
      (adjacent can edges peek).
      A valid can colour calls `StencilState.setSheetColor`; hex/RGB editing, duplicate-colour
      rejection and reset to image-derived colours remain.**
      Verified: 26 new tests on JVM and Wasm (flat regions of 2/5/9/24 colours, determinism, given colours,
      every order, invariants for 1..60 sheets on dark and light subjects, fewer colours than sheets, null cases,
      Oklab round trip, and a colour separation through the vectoriser, SVG/DXF/HPGL/G-code and `PrintPdf` with
      and without mounting). The web build was driven in headless Chromium on the astronaut photo (4 and 8
      sheets, one sheet, composite PNG, SVG: fills are the sheet colours). Separating a 1600 px cut-out takes
      about 0.6 s on the JVM at 4 to 100 sheets. **Not verified:** Android on a device or emulator, timing in a
      browser on a phone, Colour mode with depth lighting or on photographs other than the astronaut, a sheet
      colour edited by hand on a real device (the editor is now wired).
- [x] **14. Paint catalogue (foundation)** — `models/paints.tsv` (134 kB, 3,479 cans of 12 brands, built by
      `tools/catalogue/build.py` from the research files in `tools/catalogue/source/`; deterministic, validates hex and
      GS1 check digits, prints counts) is packaged by the same route as the models (assets on Android, `models/` on the
      web), and the service worker caches `.tsv` cache-first (VERSION bumped to v3). `core.paint`: `PaintCatalogue.parse`
      (about 3 ms for the whole file), search (case, accent, punctuation tolerant; exact code, code prefix, name prefix,
      word, substring), lookup by key / code / barcode (`GtinCheck`, UPC-A and EAN-13 are one), `UserBarcodes` (a learned
      barcode beats the catalogue), `Selection.distinctColours()` (cans of one colour value become one layer, because
      `ColorParams.colors` must be as many as sheets and all different), `bestCans` / `pickFor` (nearest in Oklab, no two
      layers the same colour), `unusedLayers`, and `ShoppingList` (Exact / Close / Approximate, sampled flag). App side:
      only `CatalogueLoader` (`rememberCatalogueLoader`, `loadPaintCatalogue`) so far.
      Rows per brand: Rust-Oleum 635, Montana Cans 580, MTN 543, Loop Colors 305, Flame 273, Kobra 264, Clash 263,
      Krylon 229, Molotow 204, Ironlak 102, Liquitex 50, Dupli-Color 31. **1,422 cans have no colour value** (all of MTN,
      Flame, Molotow and Clash; 16 Montana; 87 Rust-Oleum; 34 Krylon; 2 Liquitex): they can be listed, searched and
      scanned but not matched to an image colour. 79 values (Liquitex, Dupli-Color) are our own sampling, flagged.
      **Do not ship this data publicly yet:** no licence to redistribute was found (see `models/paints.NOTICE.txt`).
      **Next: the picker UI (brands, search, selection, "pick for my image"), the PDF shopping list, the barcode scanner.**
      Verified: 35 new tests on JVM and Wasm, including every row of the real file on both.

- [x] **14b. Paint list in the print PDF** — `PrintOptions.shopping` (items from `ShoppingList.of`, already limited to the
      exported sheets, in paint order) adds a "Paint" block to the instructions (swatch with dark outline, layer, brand/line/name, code,
      barcode, match words, sampled flag, up to 3 equivalents) and a "CHECK THE COLOURS" step. Rows fill page 1 while its scale stays
      >= 0.9; the rest go on "Paint, continued" pages at 1.1; list text is never under 7 pt. `PrintPlan.instructionPages` (default 1).
      `PdfText` now writes Latin-1 and WinAnsi marks (octal escapes, file stays 7-bit), folds Latin Extended-A, else "?". With no list
      the output is byte-identical (FNV-1a golden test of four inputs). Tests: `PrintShoppingTest`. Rendered with PyMuPDF; pypdf strict.
      **Not verified:** a real printer; legibility of 7 pt on paper; names in a single word wider than the page (not broken);
      the picker UI passing the list (other agent). With a 6-layer list on Letter only 2 rows fit page 1 beside the diagram.

- [x] **15. Can picker** - Colour mode gets a **Paint** section: Free / Pick for me / My cans (`PaintMode`).
      `core.paint.PaintPlan` holds the testable logic (layers per mode, `separate` with exactly the cans' colours,
      `SnappedLayer` used/unused per sheet, shopping list numbered by sheet, shared-colour notes, available colour
      count), `CanFilter` (brand/line chips) and `PaintPersistence` (cans by `canKey`, brands; unknown dropped).
      `StencilState` loads the catalogue on first need, persists through the new `KeyValueStore` (expect/actual:
      SharedPreferences, `localStorage` in try/catch), recomputes layers off the main thread under the existing
      debounce, and has the scanner hooks `addCanByBarcode(gtin)` and `learnBarcode(gtin, can)` (no scanner UI).
      UI (`PaintPanel.kt`, `CanArt.kt`): layer stepper (up to the distinct colours the allowed cans have), brand then line
      chips, inline collapsible picker: search, brand chips and a bounded lazy grid of the original spray-can top PNG
      over an individual per-can colour circle and one white rack background; colourless cans are omitted.
      Tapping a top opens a carousel (HorizontalPager, whole
      can drawn side-on with maker, colour name, code, barcode, match quality in Pick for me; neighbours peek; arrows,
      keyboard arrows, touch and mouse drag); Selected strip of small tops with remove, shared-colour
      note, Paint list with match quality and Keep/Drop for unused layers, one-line data caveat. Sheet strip wraps
      up to 48 sheets. The print PDF gets `PrintOptions.shopping` for the sheets exported (empty when not snapped).
      Verified: new `PaintPlanTest` on JVM and Wasm; the web build driven in headless Chromium (astronaut photo):
      Pick for me 6 layers from Montana Cans, My cans search "frog" and typing, carousel by arrows, keys and mouse drag, three chosen cans give exactly
      three layers, selection survives a page reload, composite PNG has exactly the three cans' colours, the print
      PDF lists the cans. **Not verified:** Android (builds and lints only; SharedPreferences untested), a very
      large selection (30+ cans) in the browser, touch input, `addCanByBarcode`/`learnBarcode` (no UI calls them).

- [x] **16. Barcode scanner** - `ScannerView` (expect/actual: camera preview, reticle, decode while active, release on
      stop or leaving the screen) and the self-contained `ScannerPanel` (Scan a can, live square view, one status line,
      Stop / Scan again, last number, "or type the barcode" with the check-digit message). Every read goes through
      `core.paint.BarcodeScan` (check digit verified, UPC-A as its EAN-13 form, UPC-E expanded) and `ScanDebouncer`
      (same number ignored for 2 s while in view); a mis-read is dropped, never reported. Web: `getUserMedia` into a
      `<video>` laid over the canvas (`scanner.js`, loaded on first scan); the browser's `BarcodeDetector` when it lists
      all four retail formats, else zxing-wasm (reader build, MIT, 411 kB wasm + 13 kB JS gzipped, in `zxing/`, cache
      first in `sw.js`). Android: CameraX + ML Kit bundled barcode model, runtime camera permission. Errors:
      `PermissionDenied`, `NoCamera`, `DecoderUnavailable`, `Unsupported` (an http page has no camera API).
      Verified: `BarcodeScanTest` on JVM and Wasm; the web glue and the real Kotlin `ScannerView` in headless Chromium
      with a fake camera (EAN-13, UPC-A, UPC-E decoded, a corrupted check digit never reported, permission denied,
      insecure origin, no device, camera tracks ended on dispose); service worker tests. **Wired:** My cans embeds `ScannerPanel` through the Paint section in `App.kt`.
      Known barcode scans add a colour-valued can without removing it on repeat; an unknown barcode can
      be identified in the existing can search/carousel and saved with `learnBarcode`. The paint
      catalogue restore/retry paths also now rebuild correctly (PR #9 feedback).
      **Not verified:** anything on an Android
      device, a real `BarcodeDetector` (headless Chromium on Linux has none: the native path ran against a stand-in), a
      real camera, a real can, iPhone Safari.

## Verification

- Phase 5: model CPU inference comparison is reproducible in
  `tools/depth/benchmark.py` and `.github/workflows/depth-model-comparison.yml`. On-device
  model loading and visual quality still require hands-on Android and PWA checks.
- `commonTest` on JVM and Wasm for everything in `core`.
- Exporters: parse-back tests (SVG/DXF/HPGL/G-code round-trip to the same polylines; print PDF page
  count, media boxes, marks and content bounds).
- Wasm build driven in headless Chromium (Playwright): load image, cycle sheets, export, screenshots.
- Android: `assembleDebug` + unit tests. No emulator in this container — on-device behaviour is
  **not** verifiable here and will be reported as such.

## Known limits (to fix in the phase named)

- Smoothing and closing remain measured in working-image pixels (longest edge 1600). Phase 6
  PNG exports intentionally use those native pixel dimensions, with no upscaling. Reprocessing
  the original full-resolution file at different pixel sizes remains unsupported because image
  picking currently downsizes to the working limit.
- On the web the pipeline runs on the only thread; the UI holds still while it works. A Web Worker
  is the fix if it proves slow on phones (phase 10).
- The web ONNX runtime is a 14.8 MB WebAssembly file (plus the 4.6 MB model), fetched only when a
  background is first removed. The service worker should cache both (phase 10).
- The release APK is still 72 MB, nearly all of it ONNX Runtime's two ARM libraries. An App Bundle
  (one ABI per device) would halve that again if it goes to Play.
- Kotlin's Wasm tooling pulls Karma from a GitHub tarball; the root build pins the npm release
  instead (unused either way).

## Open questions

1. **Islands and stroke synthesis.** In progress: Az's black-base/grey/white
   references establish that essential details must be reproducible from the supplied
   cuts, even if the painter previously reconstructed them by hand. The first stage
   adds independently overpainted cuts, exact island removal when already-placed
   later layers restore the entire detail (black to white or black to grey and
   white), and negative-stroke bridges only where later paint fully conceals them.
   Small missing grey/white paint marks can now be composed when the source
   strongly supports the colour and no later-layer stock is detached. Exposed
   or ambiguous details remain unresolved, not silently deleted. Full
   directional stroke-trajectory synthesis, physical-width checking and
   real-world validation remain.
   Brush size is adjustable (1–24 working-image pixels) in the Edges stage.
   Separate areas narrower than the chosen brush keep their original solid
   shapes. This is a pixel setting, not a calibrated physical spray width.
   The opt-in Brush strokes mode now reconstructs wide later-layer regions as
   narrow additive, flow-guided paint ribbons (with bounded deviation and a
   retained-stock island check), leaving thin details and Classic available.
   Semantic stroke grouping, context-sensitive island reconstruction and hardware
   validation remain.
   GraffitiXR originally assumed overpaint and never bridged; a *cut* sheet still
   drops any region fully enclosed by a cut. See `docs/STROKE_ISLANDS.md`.
2. **Depth model.** Depth Anything V2 Small (quantised, on demand) selected and benchmarked
   against MiDaS v2.1 Small on the same CI CPU runner; see `models/depth-anything-v2-small.md`.
   Device-specific latency and segmentation-quality evaluation remain for phase 12. The shared
   `Inference` interface permits a future replacement.

## Resolved

- **Cutting machines.** Phase 8 targets knife cutters first, lasers/routers second:
  - SVG — Cricut Design Space, Brother CanvasWorkspace (ScanNCut), Silhouette Studio (paid
    tiers), Sure Cuts A Lot, LightBurn, Inkscape.
  - DXF (R12 polylines) — Silhouette Studio Basic (free tier takes DXF, not SVG), CAD/CAM.
  - HPGL / PLT — Graphtec, Roland, USCutter, Vevor and other roll plotters.
  - PDF (vector) — Graphtec/Roland plugins, print shops.
  - G-code — GRBL lasers and routers (feed/power/passes configurable).
  - Proprietary containers (.studio3, .fcm, Cricut projects) are not written; their apps import
    the above.
  - Cutter-aware output: closed paths only, min feature size and min bridge width in mm,
    knife-offset-safe corner handling, **mat/roll tiling** (12x12, 12x24, A4, A3, custom, roll
    width) with registration marks — the vector twin of the print PDF in phase 9.
- **Pages deploy.** `github-pages-deploy.yml` already exists in `HereLiesAz/workflows` (profile:
  `build_command`, `site_dir`, `java_version`, `node_version`, `pages_branch`). No new workflow
  needed. Also reused: `android-github-release`, `multi-platform-app-release`. CI runs in this
  repository (catalog policy), reporting via the `ci-report` action.

## Review

**Verified here**

- Print PDF tests pass on JVM and on Wasm (see the last report for counts) (`:core:jvmTest`, `:core:wasmJsNodeTest`).
- Raster ops and resampling match fixtures generated by OpenCV; default tonal output matches a
  transcription of GraffitiXR's `StencilProcessor` running on OpenCV, pixel for pixel.
- Web: local build and the deployed site driven in headless Chromium: open a photo, remove the
  background, stencil, change sheets, download every cutter format whole and tiled, and the print PDF, start offline.
- Android: debug and release APKs build, lint passes, version comes from the release arguments.

**Not verified (needs hardware or a person)**

- Anything on an Android device: image picking, model loading and inference, saving files.
- Depth + lighting in a browser beyond what phase 5's own checks covered.
- Cutter files in real cutter software or on a machine; the print PDF on a real printer (the 3 mm border and the marks are assumed, not measured); the PWA install prompt on a phone.
- The central Android release run (after HereLiesAz/workflows#216 merges).

**Left open by decision or for later**

- Island bridging is partly implemented (only fully concealed negative strokes, with visible islands explicitly left unresolved). Free Colour sheets have a can-top palette browser using
  the same full-can peeking carousel as My cans, with fallback hex/RGB editing through
  `ColorParams.colors` and `StencilState.setSheetColor`. The rack and carousel exclude
  every can lacking a resolved RGB colour, even when an entry otherwise matches search.
  Previously saved local #RRGGBB approximations still count as resolved for display;
  their source is marked as user-entered, not official paint measurement.
  The top-view rack uses the user's original five-top transparent spray-can asset
  unmodified, with a separate solid circle in each can's colour layered underneath,
  and one white background beneath the entire rack. The MTN 94 carousel uses the
  other original user-provided upright transparent packshot, unmodified, with
  an independent colour shape behind its translucent shoulder and white behind.
  Node SHA-256 checks protect both PNGs and verify layer order; other can brands
  retain the existing side-on vector representation. MTN maker RGB values remain
  unavailable when no user approximation has been supplied. A live site/reference is not a redistribution license for its imagery or swatch
  database. Visual/touch checks on actual devices remain outstanding.
- Custom mat and roll sizes, overlap/margin validation, and G-code laser/router settings
  (feed, travel, power, passes, safe/cut Z) are now exposed in the Cutter files UI. Invalid
  entries and oversized tiling plans block downloads; settings flow into the exporter.
  The resulting machine files still require dry-run verification on hardware.
- Fixed tiled cutter clipping: SVG, DXF, HPGL and G-code now clip each shape's outer
  ring and holes as one region, emitting disconnected pieces as separate closed paths
  rather than retracing tile-boundary edges. Full-shape paths remain unchanged.
  Physical cutter validation is still outstanding.
- The web pipeline still runs on the page's only thread.

