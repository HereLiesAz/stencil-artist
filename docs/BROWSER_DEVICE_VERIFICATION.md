# Browser and device verification

## Automated browser baseline

Run from the repository root after building the distribution:

```sh
./gradlew :composeApp:wasmJsBrowserDistribution
python3 -m pip install playwright==1.57.0 Pillow==12.0.0
python3 -m playwright install --with-deps chromium
python3 tools/tests/browser-smoke.py --distribution composeApp/build/dist --artifacts build/browser-smoke
```

Use `--url https://hereliesaz.com/stencil-artist/` instead of `--distribution` to check a published build. Use `--chromium /path/to/chromium` for a locally installed browser. The baseline uses a transparent synthetic image so no model download is needed. A missing semantic control, failed file chooser, incomplete loading acknowledgement, incorrect export, or uncaught page exception fails the test. Screenshots and downloaded PNGs are written to the artifact directory. A narrow browser viewport with touch enabled is not a physical Android device.

## Photograph-to-stencil browser acceptance

CI builds the actual Compose Wasm distribution in a separate job from core tests, then serves it on loopback. No deployed site is required. Run the same check locally:

```sh
./gradlew :composeApp:wasmJsBrowserDistribution
python3 -m pip install playwright==1.57.0 Pillow==12.0.0 scikit-image==0.25.2 pypdf==6.4.0
python3 -m playwright install --with-deps chromium
python3 tools/tests/artist-workflow-diagnostic.py --distribution composeApp/build/dist --artifacts build/artist-workflow
```

The opaque photograph must produce an isolated multicolour stencil, and the real download controls must produce a PNG archive, StencilPack with independently recomposited sheets, PDF and whole-piece SVG/DXF/HPGL/G-code. Captures are digital evidence only, not proof of physical cutting or painting. The job uploads screenshots and exports on failure as well as success.

## Manual acceptance matrix (not yet verified)

Record commit, browser/OS/device, viewport, input image, result, and screenshot or downloaded file for each row. Use an actual photograph as well as transparent artwork; compare matching regions of the original and prepared preview. Do not mark a row passed on compilation alone.

| Area | Exercise and expected result |
| --- | --- |
| Image and subject | Import an opaque photo; use automatic isolation with and without depth assistance, then remove and restore missed regions. Draft changes do not reach committed sheets until Apply; discard and undo work. |
| Palette | Choose non-grey paints before separation; change layer count and retain existing slots. Assign different colours after separation; cut geometry and ordering remain unchanged. |
| Flat lighting | Load depth, change light direction, reverse depth, test matte mix; transparency and framing remain fixed. |
| Relief | Edit depth with brush and global controls; apply and discard; compare textured and solid surfaces. Place directional, point and spot lights, reverse light direction, inspect soft and hard occlusion and opt-in backdrop. Orbit never changes the frontal stencil. |
| Adjust and edges | Adjust source and stencil image, including curves; compare classic and brushstroke cuts. Inspect narrow stock and islands on individual sheets. |
| Loading | Restart a process and check a new tip; verify the current tip remains through completion. During work, background controls are blocked; after success or failure, Continue or tapping anywhere dismisses. |
| Navigation and accessibility | Wide and narrow browser, keyboard, physical Android touch. Check all ten stages, comparison alignment, pinch/drag bounds and keyboard focus. |
| Exports | Save transparent composite and sheet PNG, ZIP, StencilPack, true-scale PDF and SVG/DXF/HPGL/G-code. Verify dimensions, paint order, transparency and matching cut boundaries with independent readers. |
| Offline and failure | Fresh and cached launch, missing depth model, failed image import, empty subject, denied file access; original-image workflow remains usable. |

Performance and real cutting/painting require separate measured evidence. Do not substitute headless checks for those results.

### Browser loading-modal regression

CI run 45400821464 showed the Subject screen after Continue in the screenshot, while the accessibility snapshot still contained only the dismissed Compose dialog. This matches JetBrains [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623). The Wasm loading notice now uses a native HTML top-layer dialog; Android retains the Compose modal. Native `showModal()` makes background content inert, Escape is suppressed, and the state owner remains responsible for rejecting premature dismissal. The browser smoke test requires the real Continue button, an inaccessible background during acknowledgement, dialog detachment and resumed stage navigation. No coordinate-only workaround or skipped export assertions.
