import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The app's version. A release build is told it on the command line (-PversionName, -PversionCode:
// the central release workflow computes both). Any other build reads version.properties, the
// repository's canonical version, so a local build is never "0.0.0.0".
val versionFile = Properties().apply {
    rootProject.file("version.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun versionPart(name: String): Int = versionFile.getProperty(name)?.trim()?.toIntOrNull() ?: 0
val appVersionName: String = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
    ?: listOf("versionMajor", "versionMinor", "versionPatch", "versionBuild").joinToString(".") { versionPart(it).toString() }
// Without an explicit code: major, minor and patch packed two digits each, then the build number,
// so a higher version always has a higher code. Never below 1, which Android requires.
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: maxOf(1, ((versionPart("versionMajor") * 100 + versionPart("versionMinor")) * 100 + versionPart("versionPatch")) * 1000 + versionPart("versionBuild"))

android {
    namespace = "com.hereliesaz.stencilartist"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hereliesaz.stencilartist"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            // ONNX Runtime ships a native library per CPU type, tens of megabytes each. Phones are
            // ARM; the x86 builds only serve emulators, which the debug build still covers.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    // The models live once, at the repository root, and are packaged from there. Stored
    // uncompressed: ONNX weights do not compress, and reading an asset is faster without inflating.
    sourceSets["main"].assets.directories.add(rootProject.file("models").path)
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
