# Stencil Artist

Turn any image into stencil layers to make stencils of ANY size.

A Compose Multiplatform app: Android, and an installable web app. The stencil tool began in
[GraffitiXR](https://github.com/HereLiesAz/GraffitiXR) and was carried over without OpenCV, so the
same Kotlin makes the same stencil on every target.

Status and roadmap: [`tasks/todo.md`](tasks/todo.md).

## What it does today

- Opens an image and separates it into sheets by tone.
- Any number of sheets, each with its own colour (a grey ramp by default).
- Smoothing, edge closing, tonal bias, and a forced dark or light base.
- Shows the finished piece, or any one sheet alone.

Not yet: background removal, depth and lighting, export. See the roadmap.

## Layout

| Module | What it is |
|---|---|
| `core` | The stencil pipeline. Pure Kotlin (`jvm`, `wasmJs`), no platform dependencies. |
| `composeApp` | The app: UI and state, shared by Android and the web. Produces the web build. |
| `androidApp` | The Android wrapper: an Activity and a manifest. |
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
