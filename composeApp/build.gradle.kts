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
        }
        wasmJsMain.dependencies {
            // Not imported by the Kotlin code, so webpack leaves it out of the bundle. It is here so
            // the package is installed and `copyOnnxRuntimeWeb` below has files to copy.
            implementation(npm("onnxruntime-web", libs.versions.onnxruntime.get()))
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

tasks.named<ProcessResources>("wasmJsProcessResources") {
    from(copyOnnxRuntimeWeb) { into("ort") }
    from(rootProject.layout.projectDirectory.dir("models")) { into("models") }
}
