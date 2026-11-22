plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.hereliesaz.stencilartist"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hereliesaz.stencilartist"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0.0"
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
