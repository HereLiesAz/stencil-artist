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
- [ ] **8. CNC export** — SVG, DXF (R12 polylines), HPGL/PLT, G-code (outline; feed/power/passes
      configurable). Physical size in mm/in drives all of them.
- [ ] **9. Tiled PDF** — physical size -> Letter/A4/custom pages, overlap, registration marks,
      row/column labels, assembly map page. Vector, from phase 7 paths.
- [ ] **10. PWA** — manifest, icons, service worker (app shell + model cache), offline, installable.
- [ ] **11. Release wiring** — Pages deploy + Android release via `HereLiesAz/workflows` catalog.
- [ ] **12. Review** — results section below, docs pass.

## Verification

- `commonTest` on JVM and Wasm for everything in `core`.
- Exporters: parse-back tests (SVG/DXF/HPGL/G-code round-trip to the same polylines; PDF page
  count and media boxes).
- Wasm build driven in headless Chromium (Playwright): load image, cycle sheets, export, screenshots.
- Android: `assembleDebug` + unit tests. No emulator in this container — on-device behaviour is
  **not** verifiable here and will be reported as such.

## Open questions

1. **Islands.** GraffitiXR assumes overpaint, so it never bridges. A *cut* sheet still drops any
   region fully enclosed by a cut. Proposal: v1 detects and highlights islands and offers
   auto-bridges (width in mm) as an opt-in; off by default.
2. **CNC formats.** Proposed set is SVG, DXF, HPGL, G-code. Add or drop?
3. **Depth model size.** DA-V2 Small is ~25 MB quantised / ~99 MB fp32. Proposal: quantised,
   downloaded on demand, cached.
4. **Pages workflow.** The catalog has no Pages deploy. Proposal: submit a generalised
   `static-pages-deploy` to `HereLiesAz/workflows` (separate PR there) in phase 11.

## Review

_(filled in at the end)_
