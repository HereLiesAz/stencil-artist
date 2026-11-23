@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/** Fetches [url] and resolves to its body as a Uint8Array; rejects on a failed request or a non-2xx status. */
private fun fetchBytes(url: String): Promise<JsAny> = js(
    """(async () => {
        const response = await fetch(url);
        if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
        return new Uint8Array(await response.arrayBuffer());
    })()"""
)

private fun lengthOf(array: JsAny): Int = js("array.length")
private fun byteAt(array: JsAny, index: Int): Int = js("array[index]")

@Composable
actual fun rememberCatalogueLoader(): CatalogueLoader = remember { WebCatalogueLoader }

/**
 * Fetches `models/<file>`, relative to the page (so the app works from a sub-path such as GitHub
 * Pages), the same place the ONNX models are served from. The service worker caches it.
 */
private object WebCatalogueLoader : CatalogueLoader {
    override suspend fun readBytes(fileName: String): ByteArray {
        val array: JsAny = fetchBytes("models/$fileName").await()
        return ByteArray(lengthOf(array)) { byteAt(array, it).toByte() }
    }
}
