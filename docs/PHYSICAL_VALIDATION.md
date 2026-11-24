# Physical stencil validation

**Status: protocol and digital fixture generator only. No physical result is recorded.**

## Generate the kit

Download the `physical-validation-kit` artifact from repository CI after the JVM tests pass, or from the repository root run:

```sh
STENCIL_PHYSICAL_OUTPUT="$PWD/build/physical-validation" ./gradlew :core:jvmTest --tests '*PhysicalValidationTest' --rerun-tasks
```

The generator uses the same tonal/colour separators, optional procedural brush styling, island repair, lift guidance, cut inspection, vectorizer and PDF/SVG exporters as the app. The controlled artwork depicts two goggle-like forms, enclosed dark details, highlights, narrow stock and fine openings. It is original synthetic artwork, not a substitute for actual supplied artwork or a photograph.

Each tonal/colour and classic/brush combination includes source and finished preview PNGs, actual print PDF and cutter SVGs, exact raster masks and diagnostic images at **160 mm** and **320 mm** artwork width. The source is 320 × 240 working pixels, so one pixel spans 0.5 mm or 1 mm respectively. The line/stock ladder has nominal source widths of 1, 2, 3, 4 and 6 pixels; measure the exported and cut geometry rather than assuming separation, stroke treatment or default vector smoothing preserved these widths. The two-pixel nominal bridge spans 1 mm or 2 mm respectively. The requested inspection minimum is 2 mm, a screening setting, not a universal safe material width.

The CSV records digital hazard counts and plan information; island repair counts are repeated on each sheet and must not be added across rows. Inspection PNG colours are diagnostic only, not cutter inputs. The SVG uses production default simplification; compare with the raster mask to distinguish vectorization losses from physical cutting losses.

## Execute

1. Record build commit, material/type/thickness, blade/cutter settings, printer, paper, paint, surface, and planned minimum stock width. Use scrap first. Follow the cutter/material/paint manufacturer's safety and ventilation guidance.
2. Print at 100%/actual size, no fit-to-page. Verify artwork dimensions with a ruler; check the two-scale ratio. For the larger kit, align overlapping pages with the actual registration marks; do not cut reference or instruction pages. Measure horizontal and vertical registration offset at multiple joins before cutting.
3. Cut each sheet, record time, lost islands, torn bridges, smallest surviving stock width, smallest successfully opened detail, and any manual correction. Photograph before and after removing waste. Never classify an unsupported island as automatically repaired just because its warning is printed.
4. Register the layers to fixed marks on the substrate, spray in listed order, and record alignment error at both goggle shapes and the fine-detail ladder. Let each coat dry per its product instructions. Compare with the generated preview; record concealed bridge visibility, reconstruction mismatch, overspray, and lost details separately.
5. Repeat for the alternate classic/brush treatment and both scales with the same material and setup. Test gradients on scrap with and without the suggested controlled edge lift. Record measured effects, not just a subjective pass.
6. Repeat the app workflow using one real tonal artwork and one real colour artwork, with permission to use them. Record source file hash, palette, working size, chosen physical width, and settings. Keep the source, app preview, exported sheets and photographed result together.

## Results (blank until measured)

Record one row per sheet or trial. Declare tolerances before cutting; proposed controlled-trial limits are print dimensions within 1% and registration within 1 mm, subject to the actual material, project scale and operator's requirements. Unsupported stock, unintended paint, lost important details and unsafe material behavior must be documented, not averaged away.

| Build / artwork / style | Width / material | Scale error | Join / layer offset | Smallest surviving stock / cut | Lost islands / tears | Visible repair / overspray | Evidence / result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pending | | | | | | | |

A digital pass does not certify hardware, registration, stencil strength, or spray quality. Use measured failures to decide whether to change bridge width, trajectory synthesis, detail retention or print layout. Keep those changes separate from the evidence.
