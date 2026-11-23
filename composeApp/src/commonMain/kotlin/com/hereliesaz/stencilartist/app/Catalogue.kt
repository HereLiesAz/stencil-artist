package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import com.hereliesaz.stencilartist.core.paint.PaintCatalogue

/**
 * Reads the bundled data files that are not models, by logical file name, the way [ModelLoader]
 * reads models: the same `models/` folder at the repository root is packaged into both builds
 * (assets on Android, `models/` next to the page on the web), so a file added there reaches both
 * with no code in the Wasm bundle.
 */
interface CatalogueLoader {
    /** The bytes of [fileName]. Throws if the file is not bundled or cannot be read. */
    suspend fun readBytes(fileName: String): ByteArray
}

/** This platform's [CatalogueLoader]: the APK's assets on Android, an HTTP fetch of `models/<file>` in the browser. */
@Composable
expect fun rememberCatalogueLoader(): CatalogueLoader

/** Reads and parses the bundled paint catalogue ([PaintCatalogue.FILE_NAME]). */
suspend fun CatalogueLoader.loadPaintCatalogue(): PaintCatalogue =
    PaintCatalogue.parse(readBytes(PaintCatalogue.FILE_NAME).decodeToString())
