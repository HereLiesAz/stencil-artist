pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "stencil-artist"

// Pure-Kotlin stencil pipeline: no platform dependencies, shared by every target.
include(":core")
