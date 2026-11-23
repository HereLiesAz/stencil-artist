plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidLibrary {
        namespace = "com.hereliesaz.stencilartist.app"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    wasmJs {
        outputModuleName = "stencil-artist"
        browser {
            commonWebpackConfig {
                outputFileName = "stencil-artist.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.onnxruntime.android)
            // The barcode scanner: CameraX shows and feeds the camera, ML Kit's bundled model (not the
            // Play services one, which downloads its model) reads barcodes on the device, offline.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.mlkit.barcode.scanning)
        }
        wasmJsMain.dependencies {
            // Not imported by the Kotlin code, so webpack leaves it out of the bundle. It is here so
            // the package is installed and `copyOnnxRuntimeWeb` below has files to copy.
            implementation(npm("onnxruntime-web", libs.versions.onnxruntime.get()))
            // Likewise for the barcode reader's fallback decoder: see `copyZxingWasm`.
            implementation(npm("zxing-wasm", libs.versions.zxingWasm.get()))
        }
    }
}

// --- Models and the web ONNX runtime, as static files of the web build -----------------------------

// ONNX Runtime Web is loaded with a script tag the first time a model is needed (see
// Inference.wasmJs.kt), not bundled: its WebAssembly binary is larger than the whole app, and a
// session that never removes a background should not download it. Only the plain-WASM build is
// copied; it is what runs on any host, GitHub Pages included.
val copyOnnxRuntimeWeb by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("kotlinWasmNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("wasm/node_modules/onnxruntime-web/dist")) {
        include("ort.wasm.min.js", "ort-wasm-simd-threaded.mjs", "ort-wasm-simd-threaded.wasm")
    }
    into(layout.buildDirectory.dir("webStatic/ort"))
}

// zxing-wasm (MIT) reads barcodes where the browser has no BarcodeDetector (iPhone Safari, Firefox,
// desktop Chrome on Windows and Linux). Like ONNX Runtime Web it is loaded with a script tag the first
// time a can is scanned (scanner.js), not bundled. Only the reader build is copied, as plain files:
// its classic-script JavaScript (13 kB gzipped), its WebAssembly (411 kB gzipped) and its licence.
val copyZxingWasm by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("kotlinWasmNpmInstall"))
    val zxing = rootProject.layout.buildDirectory.dir("wasm/node_modules/zxing-wasm")
    from(zxing.map { it.file("dist/iife/reader/index.js") }) { rename { "zxing_reader.js" } }
    from(zxing.map { it.file("dist/reader/zxing_reader.wasm") })
    from(zxing.map { it.file("LICENSE") })
    into(layout.buildDirectory.dir("webStatic/zxing"))
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    from(copyOnnxRuntimeWeb) { into("ort") }
    from(copyZxingWasm) { into("zxing") }
    from(rootProject.layout.projectDirectory.dir("models")) { into("models") }
}
