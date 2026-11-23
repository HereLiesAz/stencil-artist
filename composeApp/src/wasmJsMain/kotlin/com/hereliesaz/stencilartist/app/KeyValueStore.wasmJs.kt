@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop

/** `localStorage.getItem`, or null: reading throws when storage is blocked and returns null when the key is empty. */
private fun storageGet(key: String): String? =
    js("(() => { try { return window.localStorage.getItem(key); } catch (e) { return null; } })()")

/** `localStorage.setItem`; a blocked or full store throws, which is swallowed. */
private fun storagePut(key: String, value: String): Unit =
    js("{ try { window.localStorage.setItem(key, value); } catch (e) { } }")

@Composable
actual fun rememberKeyValueStore(): KeyValueStore = remember { WebStore }

/** Keeps the strings in the page's `localStorage`, per origin. */
private object WebStore : KeyValueStore {
    override fun get(key: String): String? = try {
        storageGet("stencil-artist:$key")
    } catch (_: Throwable) {
        null
    }

    override fun put(key: String, value: String) {
        try {
            storagePut("stencil-artist:$key", value)
        } catch (_: Throwable) {
        }
    }
}
