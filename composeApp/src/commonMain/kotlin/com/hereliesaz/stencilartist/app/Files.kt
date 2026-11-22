package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable

/** A finished file, held in memory, ready to be handed to the user. */
class ExportFile(val name: String, val mimeType: String, val bytes: ByteArray)

/**
 * Hands a file to the user the way this platform does it: on Android the system's "save as"
 * chooser, in a browser a download.
 */
interface FileSaver {
    /**
     * Offers [file] to the user. [onResult] gets true once it has been written (or, in a browser,
     * once the download has been started, which is all a page can know) and false if the user
     * backed out or the write failed.
     */
    fun save(file: ExportFile, onResult: (Boolean) -> Unit)
}

/** This platform's [FileSaver]. */
@Composable
expect fun rememberFileSaver(): FileSaver

/**
 * Straight-alpha ARGB [pixels], row-major, as a PNG file with its transparency intact. Done by the
 * platform's own encoder: both have one, and neither is worth rewriting.
 */
expect fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray
