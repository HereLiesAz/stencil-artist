package com.hereliesaz.stencilartist.app

import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.export.DxfExporter
import com.hereliesaz.stencilartist.core.export.GcodeExporter
import com.hereliesaz.stencilartist.core.export.GcodeOptions
import com.hereliesaz.stencilartist.core.export.HpglExporter
import com.hereliesaz.stencilartist.core.export.PhysicalSize
import com.hereliesaz.stencilartist.core.export.SvgExporter
import com.hereliesaz.stencilartist.core.export.SvgOptions
import com.hereliesaz.stencilartist.core.export.Tile
import com.hereliesaz.stencilartist.core.export.TileSpec
import com.hereliesaz.stencilartist.core.export.Tiling
import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.SheetOutline
import com.hereliesaz.stencilartist.core.vector.Vectorizer

/**
 * The vector formats cutting machines and their software take. Which to use is the machine's
 * business, not the app's: craft cutters import SVG or DXF, roll plotters take HPGL, lasers and
 * routers take G-code. Printing on paper is the separate print PDF, [PrintPdf].
 */
enum class CutterFormat(val label: String, val extension: String, val mimeType: String) {
    SVG("SVG", "svg", "image/svg+xml"),
    DXF("DXF", "dxf", "application/dxf"),
    HPGL("HPGL", "plt", "application/vnd.hp-hpgl"),
    GCODE("G-code", "gcode", "text/plain"),
}

/**
 * Cutter files of the current stencil: the same sheets the preview shows, traced into outlines and
 * written at a real physical size.
 *
 * One file when the request is one file's worth (the whole piece, in a format that holds it). A ZIP
 * when it is not: the piece split across mats or pages, or several sheets in a format that is cut
 * one sheet at a time.
 */
object CutterExports {
    /** The store-only ZIP writer keeps everything in memory; see [RasterExports] for the same bound. */
    private const val MAX_ZIP_PAYLOAD_BYTES = 32L * 1024 * 1024

    /**
     * @param sheet the one sheet to write, or null for every sheet.
     * @param widthMm the finished piece's physical width; its height follows the image.
     * @param fit the mat or page to split the piece across, or null to write it whole.
     * @param cutLines SVG only: hairline outlines with no fill, as cutter software expects, instead
     *   of filled shapes in the sheets' colours.
     * @param gcodeOptions feed, travel, power, passes and laser/router parameters, for G-code only.
     *   Other formats do not use them; defaults keep prior G-code exports unchanged.
     */
    fun make(
        separation: Separation,
        format: CutterFormat,
        widthMm: Double,
        sheet: Int? = null,
        fit: TileSpec? = null,
        cutLines: Boolean = true,
        gcodeOptions: GcodeOptions = GcodeOptions(),
    ): ExportFile {
        require(separation.sheets.isNotEmpty()) { "A cutter file needs at least one sheet" }
        require(sheet == null || sheet in separation.sheets.indices) { "There is no sheet ${(sheet ?: 0) + 1}" }
        val drawing = outline(separation, sheet)
        val size = PhysicalSize(widthMm)
        val wanted = if (sheet != null) listOf(sheet) else separation.sheets.indices.toList()
        val digits = maxOf(2, separation.sheets.size.toString().length)
        fun sheetName(i: Int) = "sheet-" + (i + 1).toString().padStart(digits, '0')

        fun text(one: Int?, tile: Tile?): ByteArray = when (format) {
            CutterFormat.SVG -> SvgExporter.export(drawing, size, one, tile, SvgOptions(cutLines = cutLines))
            CutterFormat.DXF -> DxfExporter.export(drawing, size, one, tile)
            CutterFormat.HPGL -> HpglExporter.export(drawing, size, one, tile)
            CutterFormat.GCODE -> GcodeExporter.export(drawing, size, one, tile, gcodeOptions)
        }.encodeToByteArray()

        val files = ArrayList<ExportFile>()
        var payload = 0L
        fun add(name: String, bytes: ByteArray) {
            payload += bytes.size
            require(payload <= MAX_ZIP_PAYLOAD_BYTES) {
                "This export exceeds the 32 MiB in-memory limit. Export one sheet at a time."
            }
            files += ExportFile("$name.${format.extension}", format.mimeType, bytes)
        }

        if (fit == null) {
            // SVG and DXF keep sheets apart as groups and layers; HPGL and G-code run them in turn.
            add(if (sheet == null) "stencil" else "stencil-${sheetName(sheet)}", text(sheet, null))
        } else {
            // One file per sheet per mat: that is what gets loaded into the cutter each time.
            val plan = Tiling.plan(drawing, size, fit)
            for (i in wanted) for (tile in plan.tiles) {
                val place = if (plan.tiles.size == 1) "" else "-r${tile.row + 1}c${tile.col + 1}"
                add("stencil-${sheetName(i)}$place", text(i, tile))
            }
        }
        return files.singleOrNull() ?: ExportFile("stencil-${format.extension}.zip", "application/zip", Zip.store(files))
    }

    /**
     * The stencil's outlines. When only one sheet is wanted only that one is traced, but it keeps
     * its place in the list, so its layer, pen and file name still carry its real number.
     */
    private fun outline(separation: Separation, sheet: Int?): Drawing {
        if (sheet == null) return Vectorizer.vectorize(separation)
        return Drawing(
            separation.width.toDouble(),
            separation.height.toDouble(),
            separation.sheets.indices.map { i ->
                if (i == sheet) Vectorizer.vectorizeSheet(separation, i) else SheetOutline(separation.sheets[i].color, emptyList())
            },
        )
    }
}
