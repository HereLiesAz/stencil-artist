# Performance verification

## Local pipeline timings

The shared processing state emits one line per completed, current generation when explicitly enabled. No image pixels, names, file paths, or network telemetry are emitted. Timing does not change source resolution, geometry, preview or exports.

- Browser: open the app with `?profile=1`; capture console lines prefixed `StencilPerf`. The existing browser smoke harness accepts `--profile` and stores timing records alongside screenshots in the artifact directory.
- Android: enable the log tag with `adb shell setprop log.tag.StencilPerf DEBUG`, then use `adb logcat -s StencilPerf:D '*:S'`. Disable with `adb shell setprop log.tag.StencilPerf INFO`.

Records identify working dimensions, requested lighting/separation mode, actual sheet count, depth cache/availability, relief availability, completion status and elapsed milliseconds. The stages are settle, depth acquisition, isolation, mask edits, depth edits, source adjustments, lighting, prepared adjustments, separation and finishing (including preview construction). Total includes dispatch and other overhead. A failed generation may have incomplete stage records. Depth acquisition may include download and initialization; it is not an isolated inference benchmark. Cancelled or superseded generations do not emit a completion record.

## Real-photo acceptance protocol (open)

For each representative Android phone and browser/device combination, record the exact build commit, hardware, OS/browser version, original photo dimensions, working dimensions, palette count, mask and depth settings, and light/material configuration. Use a portrait, a detailed subject with fine edges, and a landscape/non-square image. Run each scenario with the same input and settings, once cold and at least three times warm; record individual samples and warm median. Use actual photos provided or licensed for testing, not the tiny synthetic CI fixture.

1. Original-image/no-depth path (disable depth-assisted masking and lighting); measure initial import and a palette-only regeneration separately.
2. Depth-assisted masking and flat relight: distinguish cold model download/initialization, cached-depth regeneration, and draft/apply.
3. Textured and solid relief, directional and finite lights, hard and soft shadows, receiver disabled and enabled. Reject fallback runs as relief benchmarks (`relief_available=false`).
4. Orbit changes and zoom/pan: capture browser Performance traces or Android frame timing separately from full pipeline elapsed time.
5. Export PNG and print/cutter files separately; check output dimensions and cut geometry before comparing latency.

Memory is not inferred from elapsed time. On Android capture `adb shell dumpsys meminfo com.hereliesaz.stencilartist` before and after each scenario (PSS snapshots, not peak); use Android Studio Profiler for peaks. On Chromium use task/process memory or browser performance tooling; JavaScript heap alone does not include all Wasm or Skia allocations. Record measurement method, sampling interval, and whether a value is peak or snapshot.

No latency or memory target is asserted until representative measurements exist. The longest working edge is currently 1600 pixels; mesh and orbit resolution are independent. Do not reduce committed image/export resolution as an unmeasured shortcut.
