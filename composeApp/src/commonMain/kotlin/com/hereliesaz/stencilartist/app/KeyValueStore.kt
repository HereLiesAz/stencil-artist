package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable

/**
 * A few small strings that outlive the app: the chosen cans, the allowed brands, learned barcodes.
 * It never throws: storage can be blocked, full or empty (a private browser window, say), and the
 * app must work without it, so a failed read gives null and a failed write is lost quietly.
 */
interface KeyValueStore {
    /** The text stored under [key], or null if there is none or storage cannot be read. */
    fun get(key: String): String?

    /** Stores [value] under [key]; does nothing if storage cannot be written. */
    fun put(key: String, value: String)
}

/** A store that lives only as long as the process: the fallback, and what tests use. */
class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
}

/** This platform's store: SharedPreferences on Android, `localStorage` in the browser. */
@Composable
expect fun rememberKeyValueStore(): KeyValueStore
