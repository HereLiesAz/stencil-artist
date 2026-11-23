package com.hereliesaz.stencilartist.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberKeyValueStore(): KeyValueStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { PreferencesStore(context) }
}

/** Keeps the strings in the app's private SharedPreferences file. */
private class PreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("stencil-artist", Context.MODE_PRIVATE)

    override fun get(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: Exception) {
        null
    }

    override fun put(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (_: Exception) {
        }
    }
}
