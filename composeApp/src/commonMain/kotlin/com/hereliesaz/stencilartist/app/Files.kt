package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import com.hereliesaz.stencilartist.core.Separation

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

/**
 * Raster outputs of the current separation, not the source photograph or an intermediate layer.
 * Uses exactly the preview's [Separation.render] contract and the platform PNG encoder, so
 * alpha, sheet colours, paint order and geometry are identical on Android and the web.
 */
object RasterExports {
    private const val PNG_MIME = "image/png"
    // The store-only ZIP writer holds both source images and a final archive in memory. Keep
    // archive exports bounded on mobile; individual PNGs have no sheet-count or ZIP limit.
    private const val MAX_ZIP_PAYLOAD_BYTES = 32L * 1024 * 1024

    /** Finished piece if [sheet] is null, otherwise this sheet's overpaint stencil only. */
    fun png(separation: Separation, sheet: Int? = null): ExportFile {
        require(separation.width > 0 && separation.height > 0 && separation.sheets.isNotEmpty()) {
            "A PNG needs a non-empty stencil"
        }
        val name = if (sheet == null) {
            "stencil-composite.png"
        } else {
            require(sheet in separation.sheets.indices) { "There is no sheet ${sheet + 1}" }
            val digits = maxOf(3, separation.sheets.size.toString().length)
            "stencil-sheet-${(sheet + 1).toString().padStart(digits, '0')}.png"
        }
        return ExportFile(
            name,
            PNG_MIME,
            encodePng(separation.render(sheet), separation.width, separation.height),
        )
    }

    /**
     * One download for every sheet, in paint order, and the assembled preview. The ZIP contains
     * the original-size, transparent PNGs, never a white matte or an enlarged approximation.
     * Rejects an overlarge bundle with an actionable message; individual sheet export remains
     * available, even when the sheet count is higher than this device can archive safely.
     */
    fun allPngsZip(separation: Separation): ExportFile {
        require(separation.sheets.isNotEmpty()) { "A bundle needs at least one sheet" }
        val files = ArrayList<ExportFile>(separation.sheets.size + 1)
        var payload = 0L
        fun add(file: ExportFile) {
            payload += file.bytes.size.toLong()
            require(payload <= MAX_ZIP_PAYLOAD_BYTES) {
                "PNG bundle exceeds the 32 MiB in-memory limit. Download sheets individually."
            }
            files += file
        }
        for (sheet in separation.sheets.indices) add(png(separation, sheet))
        add(png(separation))
        return ExportFile("stencil-pngs.zip", "application/zip", Zip.store(files))
    }
}
