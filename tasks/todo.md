# Stencil Artist — plan

Source: GraffitiXR `StencilTones.kt`, `StencilProcessor.kt`, `SubjectIsolator.kt`, `StencilTonesTest.kt`
(copied; GraffitiXR is not modified).

Status: **awaiting approval**. Nothing below is built yet.

## Decisions (answered)

- GraffitiXR keeps its stencil tool untouched; this is a copy.
- v1 = port + adjustable sheets + per-sheet PNG + tiled true-scale PDF + CNC vector export +
  depth map with adjustable lighting.
- Subject isolation: u2netp ONNX on both targets.
- PWA hosted on GitHub Pages.

## Architecture

~~~
stencil-artist/
  core/         KMP library, commonMain only, no platform deps
                tone/     StencilTones (N sheets), k-means thresholds, dark/light call
                raster/   median blur, morphological close, flood fill   (replaces OpenCV)
                light/    depth -> normals -> relit luminance
                vector/   contour trace, simplify, islands
                export/   PNG planes, PDF writer + tiler, SVG, DXF, HPGL, G-code
  composeApp/   Compose Multiplatform UI: androidMain + wasmJsMain
                ml/       expect/actual OnnxSession (onnxruntime-android / onnxruntime-web)
                io/       expect/actual image pick, decode, save/share
  tasks/        todo.md, lessons.md
~~~

- Package `com.hereliesaz.stencilartist`.
- **No OpenCV.** Median, close and flood fill are rewritten in pure Kotlin so one implementation
  serves Android and Wasm. The only platform code is ONNX inference and file I/O.
- Pipeline is byte/int-array in, byte/int-array out; the UI owns bitmaps.

## Phases

Each phase is a commit series on `ccr-14f6f879-ovc7dn`, one draft PR, verified before the next.

- [ ] **0. Scaffold** — Gradle KMP project (android + wasmJs), version catalog, README rewrite,
      `.github/workflow-request.yml` filled in, `./gradlew build` green.
- [ ] **1. Core port** — `StencilTones` + pure-Kotlin raster ops; `StencilTonesTest` ported to
      `commonTest`; new tests for median/close/flood fill against hand-computed fixtures.
- [ ] **2. Adjustable sheets** — K = 2..4 (generalised `thresholds`/`ranks`/`render`), sliders for
      smoothing (median kernel), edge closing, tonal bias, forced dark/light base. K = 3 with
      defaults must reproduce GraffitiXR's output exactly (regression test).
- [ ] **3. App shell** — pick image, stacked preview, sheet cycler, controls. Monochrome dark UI.
- [ ] **4. Subject isolation** — u2netp via `OnnxSession`; lazy load; falls back to whole frame.
- [ ] **5. Depth + lighting** — Depth Anything V2 Small (Apache-2.0) ONNX -> depth map -> normals
      -> Lambert + ambient relight. Controls: light azimuth, elevation, intensity, ambient, depth
      strength. Relit luminance feeds the stencil. Model downloaded on first use, not bundled.
- [ ] **6. Raster export** — per-sheet and stacked transparent PNG.
- [ ] **7. Vectorise** — contour tracing with holes, Douglas-Peucker simplify, min-feature filter.
- [ ] **8. Cutter export** — SVG, DXF (R12 polylines), HPGL/PLT, vector PDF, G-code. Physical size
      in mm/in drives all of them; tiled per mat/roll. See Resolved > Cutting machines.
- [ ] **9. Tiled PDF** — physical size -> Letter/A4/custom pages, overlap, registration marks,
      row/column labels, assembly map page. Vector, from phase 7 paths.
- [ ] **10. PWA** — manifest, icons, service worker (app shell + model cache), offline, installable.
- [ ] **11. Release wiring** — bind existing catalog workflows: `github-pages-deploy`,
      `android-github-release`; local CI with `ci-report`.
- [ ] **12. Review** — results section below, docs pass.

## Verification

- `commonTest` on JVM and Wasm for everything in `core`.
- Exporters: parse-back tests (SVG/DXF/HPGL/G-code round-trip to the same polylines; PDF page
  count and media boxes).
- Wasm build driven in headless Chromium (Playwright): load image, cycle sheets, export, screenshots.
- Android: `assembleDebug` + unit tests. No emulator in this container — on-device behaviour is
  **not** verifiable here and will be reported as such.

## Open questions

1. **Islands.** Open — waiting on Az's approach. (GraffitiXR assumes overpaint and never bridges;
   a *cut* sheet still drops any region fully enclosed by a cut.)
2. **Depth model.** Depth Anything V2 Small (quantised, on demand) approved. MiDaS small under
   consideration as a comparison; `OnnxSession` keeps the model swappable. Benchmark both in
   phase 5 before committing.

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
    width) with registration marks — the vector twin of the tiled PDF in phase 9.
- **Pages deploy.** `github-pages-deploy.yml` already exists in `HereLiesAz/workflows` (profile:
  `build_command`, `site_dir`, `java_version`, `node_version`, `pages_branch`). No new workflow
  needed. Also reused: `android-github-release`, `multi-platform-app-release`. CI runs in this
  repository (catalog policy), reporting via the `ci-report` action.

## Review

_(filled in at the end)_
