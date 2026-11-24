plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // JVM: what Android consumes, and where the tests run fastest.
    jvm()
    // Wasm: what the PWA consumes. The library is the same for a browser; Node is only where its
    // tests run, which needs no browser test harness.
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
