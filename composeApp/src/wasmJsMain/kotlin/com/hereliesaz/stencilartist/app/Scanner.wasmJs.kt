@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Loads scanner.js (once, with a script tag, like ONNX Runtime Web: it is not part of the Kotlin
 * bundle, and a session that never scans should not fetch it) and resolves to `StencilScanner`.
 */
private fun loadGlue(): Promise<JsAny> = js(
    """(async () => {
        if (!globalThis.StencilScanner) {
            await new Promise((resolve, reject) => {
                const script = document.createElement('script');
                script.src = new URL('scanner.js', document.baseURI).href;
                script.onload = resolve;
                script.onerror = () => reject(new Error('scanner.js failed to load'));
                document.head.appendChild(script);
            });
        }
        return globalThis.StencilScanner;
    })()"""
)

/** Starts a scan; returns the session at once (the camera starts behind it). See scanner.js. */
private fun startSession(glue: JsAny, onCode: (String, Boolean) -> Unit, onError: (String, String) -> Unit): JsAny =
    js("glue.start(onCode, onError)")

private fun stopSession(session: JsAny): Unit = js("{ session.stop(); }")

private fun placeSession(
    session: JsAny, left: Double, top: Double, width: Double, height: Double,
    clipTop: Double, clipRight: Double, clipBottom: Double, clipLeft: Double,
): Unit = js("{ session.place(left, top, width, height, clipTop, clipRight, clipBottom, clipLeft); }")

/** Where the view is in the window, in CSS pixels, and how much of each side a scrolled-away ancestor hides. */
private class Placement(
    val left: Double, val top: Double, val width: Double, val height: Double,
    val clipTop: Double, val clipRight: Double, val clipBottom: Double, val clipLeft: Double,
)

/** The running session, once it exists, and the last place the view was laid out (kept for a session that starts after layout). */
private class SessionHolder {
    var session: JsAny? = null
    var placement: Placement? = null

    fun place(p: Placement) {
        placement = p
        session?.let { apply(it, p) }
    }

    fun attach(s: JsAny) {
        session = s
        placement?.let { apply(s, it) }
    }

    private fun apply(s: JsAny, p: Placement) =
        placeSession(s, p.left, p.top, p.width, p.height, p.clipTop, p.clipRight, p.clipBottom, p.clipLeft)
}

@Composable
actual fun ScannerView(active: Boolean, onGtin: (String) -> Unit, onError: (ScannerError) -> Unit, modifier: Modifier) {
    val latestGtin = rememberUpdatedState(onGtin)
    val latestError = rememberUpdatedState(onError)
    val sink = remember { ScanSink { latestGtin.value(it) } }
    val holder = remember { SessionHolder() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    DisposableEffect(active) {
        var disposed = false
        if (active) {
            sink.reset()
            scope.launch {
                try {
                    val glue = loadGlue().await<JsAny>()
                    if (disposed) return@launch
                    val session = startSession(
                        glue,
                        onCode = { text, upcE -> if (!disposed) sink.offer(text, upcE) },
                        onError = { kind, message -> if (!disposed) latestError.value(errorFor(kind, message)) },
                    )
                    if (disposed) stopSession(session) else holder.attach(session)
                } catch (e: Throwable) {
                    if (!disposed) latestError.value(ScannerError.DecoderUnavailable(e.message ?: "scanner.js failed to load"))
                }
            }
        }
        onDispose {
            disposed = true
            holder.session?.let(::stopSession)
            holder.session = null
        }
    }

    Box(modifier.onGloballyPositioned { coordinates -> holder.place(placementOf(coordinates, density)) })
}

/** The scanner's own error kinds (see scanner.js) as the app's [ScannerError]. */
private fun errorFor(kind: String, message: String): ScannerError = when (kind) {
    "permission" -> ScannerError.PermissionDenied
    "nocamera" -> ScannerError.NoCamera
    "decoder" -> ScannerError.DecoderUnavailable(message)
    else -> ScannerError.Unsupported(message.ifBlank { "This browser cannot scan with the camera. Type the barcode instead." })
}

/**
 * Compose lays out in physical pixels; the page is in CSS pixels, [density] of them to a pixel. The
 * clip is how far the visible part (what the ancestors' clipping leaves) is inside the full box, so a
 * view scrolled partly out of its panel is cut the same way.
 */
private fun placementOf(coordinates: LayoutCoordinates, density: Float): Placement {
    val d = density.toDouble()
    val position = coordinates.positionInWindow()
    val width = coordinates.size.width / d
    val height = coordinates.size.height / d
    val left = position.x / d
    val top = position.y / d
    val visible = coordinates.boundsInWindow()
    return Placement(
        left, top, width, height,
        clipTop = (visible.top / d - top).coerceAtLeast(0.0),
        clipRight = (left + width - visible.right / d).coerceAtLeast(0.0),
        clipBottom = (top + height - visible.bottom / d).coerceAtLeast(0.0),
        clipLeft = (visible.left / d - left).coerceAtLeast(0.0),
    )
}
