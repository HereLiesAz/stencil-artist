package com.hereliesaz.stencilartist.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberCatalogueLoader(): CatalogueLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { AssetCatalogueLoader(context) }
}

/** Reads from the APK's assets, where `models/` is packaged (see androidApp/build.gradle.kts). */
private class AssetCatalogueLoader(private val context: Context) : CatalogueLoader {
    override suspend fun readBytes(fileName: String): ByteArray = withContext(Dispatchers.IO) {
        context.assets.open(fileName).use { it.readBytes() }
    }
}
