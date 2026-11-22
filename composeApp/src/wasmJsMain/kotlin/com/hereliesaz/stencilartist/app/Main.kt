@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.ExperimentalWasmJsInterop

/** Takes down the placeholder index.html shows while the app is still downloading. */
private fun removeLoadingPlaceholder(): Unit = js("{ const e = document.getElementById('loading'); if (e) e.remove(); }")

/** The web entry point. Everything on screen is [App], shared with the Android build. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { App() }
    removeLoadingPlaceholder()
}
