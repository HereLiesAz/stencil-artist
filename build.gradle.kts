import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// Kotlin's Wasm tooling installs Karma from a tarball of JetBrains' GitHub fork, even for projects
// that never run a browser test. This build does not use Karma; taking the upstream release from
// the npm registry keeps tooling setup to one package source.
plugins.withType<WasmNodeJsRootPlugin> {
    the<WasmNodeJsRootExtension>().versions.karma.version = "6.4.4"
}
