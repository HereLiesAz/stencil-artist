import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Private fonts are opt-in; public CI and releases never see their binaries.
val privateBrandFonts = providers.gradleProperty("privateBrandFonts").map { it == "true" }.orElse(false)
val privateBrandDirectory = rootProject.layout.projectDirectory.dir("private-branding")
if (privateBrandFonts.get()) {
    listOf("font/prodes_stencil.ttf", "font/cavier.ttf").forEach { relativePath ->
        require(privateBrandDirectory.file(relativePath).asFile.isFile) {
            "Missing private-branding/$relativePath; see README.md for local font setup."
        }
    }
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
        commonMain {
            kotlin.srcDir(if (privateBrandFonts.get()) "src/privateBranding/kotlin" else "src/publicBranding/kotlin")
        }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
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

// Compose customDirectory replaces the normal resource directory, so private builds
// stage the original resources as well as exactly the two permitted font inputs.
val stagePrivateBrandResources by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    from(privateBrandDirectory) {
        include("font/prodes_stencil.ttf", "font/cavier.ttf")
    }
    into(layout.buildDirectory.dir("generated/privateBrandResources"))
}

// Shared Compose resources include the original paint photographs; private fonts are opt-in.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.hereliesaz.stencilartist.app.generated.resources"
    if (privateBrandFonts.get()) {
        customDirectory(
            sourceSetName = "commonMain",
            directoryProvider = stagePrivateBrandResources.map { layout.buildDirectory.dir("generated/privateBrandResources").get() },
        )
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

// Generate the PWA and Apple launchers from the same artwork Android uses.
val launcherArtwork = rootProject.layout.projectDirectory.file("androidApp/src/main/res/drawable-nodpi/stencil_brand.jpg")
val brandWebIcons = layout.buildDirectory.dir("generated/brandWebIcons")
val generateBrandWebIcons by tasks.registering {
    inputs.file(launcherArtwork)
    outputs.dir(brandWebIcons)
    doLast {
        val source = requireNotNull(ImageIO.read(launcherArtwork.asFile))
        require(source.width == source.height) { "Launcher artwork must be square" }
        val icons = brandWebIcons.get().asFile.resolve("icons").apply { mkdirs() }
        listOf(
            Triple("icon-192.png", 192, false),
            Triple("icon-512.png", 512, false),
            Triple("icon-maskable-512.png", 512, true),
            Triple("apple-touch-icon.png", 180, false),
        ).forEach { (name, size, maskable) ->
            val bitmap = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val graphics = bitmap.createGraphics()
            try {
                graphics.color = Color(0x416770)
                graphics.fillRect(0, 0, size, size)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                val edge = if (maskable) size * 3 / 4 else size
                val offset = (size - edge) / 2
                graphics.drawImage(source, offset, offset, edge, edge, null)
            } finally {
                graphics.dispose()
            }
            check(ImageIO.write(bitmap, "png", icons.resolve(name))) { "PNG writer unavailable" }
        }
    }
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    from(generateBrandWebIcons)
    from(copyOnnxRuntimeWeb) { into("ort") }
    from(copyZxingWasm) { into("zxing") }
    from(rootProject.layout.projectDirectory.dir("models")) { into("models") }
}
