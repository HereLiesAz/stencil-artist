pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // AzNavRail's update module is published in its own Maven tree, not Maven Central.
        // Exclusive routing avoids querying unrelated repositories (which can return HTTP 429
        // before Gradle reaches the actual publisher). Its transitive dependencies stay normal.
        exclusiveContent {
            forRepository {
                maven("https://raw.githubusercontent.com/HereLiesAz/AzNavRail/update-maven/")
            }
            filter { includeModule("com.github.HereLiesAz.AzNavRail", "aznavrail-updates") }
        }
    }
}

rootProject.name = "stencil-artist"

// Pure-Kotlin stencil pipeline: no platform dependencies, shared by every target.
include(":core")

// The app itself, UI and all, shared by Android and the web. The web build is produced here.
include(":composeApp")

// The Android wrapper: an Activity, a manifest, and nothing else. (AGP 9 keeps the application
// plugin out of multiplatform modules.)
include(":androidApp")
