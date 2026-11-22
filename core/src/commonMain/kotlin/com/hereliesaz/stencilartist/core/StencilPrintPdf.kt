package com.hereliesaz.stencilartist.core

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Paper dimensions in PDF points (1/72 inch). */
enum class PrintPaper(val widthPt: Double, val heightPt: Double) {
    LETTER(612.0, 792.0),
    A4(595.276, 841.89),
}

/** Actual stencil width, not the width of the source image on screen. */
data class StencilPrintOptions(
    val widthMm: Double = 203.2,
    val paper: PrintPaper = PrintPaper.LETTER,
    val overlapMm: Double = 10.0,
)

/**
 * Tiled black-and-white PDF for cutting a stencil at actual size.
 *
 * The stencil itself remains unannotated. Problem-specific advice is drawn in a separate margin
 * aligned with the affected feature's vertical position on the physical tile. Islands and fine
 * cuts are diagnosed once on the complete sheet, not on clipped tiles; a tile seam must never
 * create a spurious island. Each sheet's one-bit image is reused across its PDF pages.
 *
 * Print at 100% / actual size. PDF viewers' "fit to page" option changes the cutting scale.
 */
object StencilPrintPdf {
    private const val PT_PER_MM = 72.0 / 25.4
    private const val LEFT = 24.0
    private const val RIGHT = 24.0
    private const val TOP = 55.0
    private const val BOTTOM = 46.0
    private const val GAP = 12.0
    private const val NOTES_WIDTH = 138.0
    private const val NOTE_HEIGHT = 64.0

    private data class Tile(
        val sheet: Int,
        val col: Int,
        val row: Int,
        val columns: Int,
        val rows: Int,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    private data class Note(val kind: String, val advice: String, val anchor: Double?)

    fun make(separation: Separation, options: StencilPrintOptions = StencilPrintOptions()): ByteArray {
        require(separation.width > 0 && separation.height > 0 && separation.sheets.isNotEmpty())
        require(options.widthMm > 0.0 && options.widthMm.isFinite())
        require(options.overlapMm >= 0.0 && options.overlapMm.isFinite())

        val pageW = options.paper.widthPt
        val pageH = options.paper.heightPt
        val printableW = pageW - LEFT - RIGHT - GAP - NOTES_WIDTH
        val printableH = pageH - TOP - BOTTOM
        val overlap = options.overlapMm * PT_PER_MM
        require(overlap < minOf(printableW, printableH)) { "Page overlap exceeds printable area" }
        val totalW = options.widthMm * PT_PER_MM
        val totalH = totalW * separation.height / separation.width
        val stepX = printableW - overlap
        val stepY = printableH - overlap

        fun tilesFor(length: Double, printable: Double, step: Double): Int =
            if (length <= printable) 1 else ceil((length - printable) / step).toInt() + 1

        val columns = tilesFor(totalW, printableW, stepX)
        val rows = tilesFor(totalH, printableH, stepY)
        val pagesCount = columns.toLong() * rows * separation.sheets.size
        require(pagesCount in 1..4096) { "Printout needs too many pages" }

        val tiles = ArrayList<Tile>(pagesCount.toInt())
        val tips = ArrayList<List<PrintTip>>(separation.sheets.size)
        val masks = ArrayList<BooleanArray>(separation.sheets.size)
        for (s in separation.sheets.indices) {
            val mask = separation.mask(s)
            masks += mask
            tips += StencilPrintTips.detect(mask, separation.width, separation.height, options.widthMm)
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    val x = col * stepX
                    val y = row * stepY
                    tiles += Tile(s, col, row, columns, rows, x, y,
                        minOf(printableW, totalW - x), minOf(printableH, totalH - y))
                }
            }
        }

        // 1: catalog; 2: page tree; 3,4: fonts; then images; then page and content pairs.
        val imageStart = 5
        val pageStart = imageStart + separation.sheets.size
        val size = pageStart + tiles.size * 2
        val objects = Array(size) { "" }
        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>"
        objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects[4] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"

        for (s in masks.indices) {
            val hex = maskAsHex(masks[s], separation.width, separation.height)
            val stream = hex + ">"
            objects[imageStart + s] = "<< /Type /XObject /Subtype /Image /Width " +
                separation.width + " /Height " + separation.height +
                " /ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /ASCIIHexDecode /Length " +
                stream.length + " >>\nstream\n" + stream + "\nendstream"
        }

        val children = StringBuilder()
        for ((i, tile) in tiles.withIndex()) {
            val pageId = pageStart + i * 2
            val streamId = pageId + 1
            children.append(pageId).append(" 0 R ")
            val name = "/Im" + (tile.sheet + 1)
            objects[pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " +
                p(pageW) + " " + p(pageH) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>" +
                " /XObject << " + name + " " + (imageStart + tile.sheet) +
                " 0 R >> >> /Contents " + streamId + " 0 R >>"
            val content = pageContent(tile, tips[tile.sheet], options, pageW, pageH, totalW,
                totalH, printableW, printableH, separation.width, separation.height, separation.sheets.size, i + 1, tiles.size)
            objects[streamId] = "<< /Length " + content.length + " >>\nstream\n" + content + "endstream"
        }
        objects[2] = "<< /Type /Pages /Kids [" + children + "] /Count " + tiles.size + " >>"

        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(size)
        for (id in 1 until size) {
            offsets[id] = pdf.length
            pdf.append(id).append(" 0 obj\n").append(objects[id]).append("\nendobj\n")
        }
        val xref = pdf.length
        pdf.append("xref\n0 ").append(size).append("\n0000000000 65535 f \n")
        for (id in 1 until size) {
            pdf.append(offsets[id].toString().padStart(10, '0')).append(" 00000 n \n")
        }
        pdf.append("trailer\n<< /Size ").append(size).append(" /Root 1 0 R >>\nstartxref\n")
            .append(xref).append("\n%%EOF\n")
        return pdf.toString().encodeToByteArray()
    }

    /** White uncut stock is 1; openings to cut are black (0). Rows are padded to 8 bits. */
    private fun maskAsHex(mask: BooleanArray, width: Int, height: Int): String {
        val stride = (width + 7) / 8
        val bytes = ByteArray(stride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (mask[row + x]) {
                    val offset = y * stride + x / 8
                    bytes[offset] = (bytes[offset].toInt() and (1 shl (7 - x % 8)).inv()).toByte()
                }
            }
        }
        val digits = "0123456789ABCDEF"
        return buildString(bytes.size * 2) {
            for (value in bytes) {
                val byte = value.toInt() and 255
                append(digits[byte ushr 4])
                append(digits[byte and 15])
            }
        }
    }

    private fun pageContent(
        tile: Tile,
        tips: List<PrintTip>,
        options: StencilPrintOptions,
        pageW: Double,
        pageH: Double,
        totalW: Double,
        totalH: Double,
        printableW: Double,
        printableH: Double,
        number: Int,
        totalPages: Int,
    ): String {
        val topY = pageH - TOP
        val noteX = LEFT + printableW + GAP
        val imageName = "/Im" + (tile.sheet + 1)
        val sheetWidth = totalW
        val topImageY = topY + tile.y
        val builder = StringBuilder()

        fun line(x1: Double, y1: Double, x2: Double, y2: Double) {
            builder.append(p(x1)).append(' ').append(p(y1)).append(" m ")
                .append(p(x2)).append(' ').append(p(y2)).append(" l S\n")
        }
        fun text(label: String, x: Double, y: Double, points: Double = 9.0, bold: Boolean = false) {
            builder.append("BT /").append(if (bold) "F2" else "F1").append(' ')
                .append(p(points)).append(" Tf ").append(p(x)).append(' ').append(p(y))
                .append(" Td (").append(escape(label)).append(") Tj ET\n")
        }

        val title = "SHEET " + (tile.sheet + 1) + " / " + sheetCount +
            "     TILE R" + (tile.row + 1) + "C" + (tile.col + 1) +
            " OF " + tile.rows + "x" + tile.columns
        text(title, LEFT, pageH - 29, 10.0, bold = true)
        text("PRINT AT 100% - ACTUAL SIZE", pageW - 218.0, pageH - 29, 8.0)

        // Clip the complete sheet's single-bit image to the tile. No text is ever drawn here.
        builder.append("q ").append(p(LEFT)).append(' ').append(p(topY - tile.height))
            .append(' ').append(p(tile.width)).append(' ').append(p(tile.height))
            .append(" re W n ").append(p(sheetWidth)).append(" 0 0 ").append(p(totalH))
            .append(' ').append(p(LEFT - tile.x)).append(' ').append(p(topImageY - totalH))
            .append(" cm ").append(imageName).append(" Do Q\n")

        // Corner crop ticks stop outside the stencil, not through its cut geometry.
        val right = LEFT + tile.width
        val bottom = topY - tile.height
        for (x in listOf(LEFT, right)) {
            line(x, topY + 3, x, topY + 9)
            line(x, bottom - 3, x, bottom - 9)
        }
        for (y in listOf(bottom, topY)) {
            line(LEFT - 3, y, LEFT - 9, y)
            line(right + 3, y, right + 9, y)
        }
        text("FIELD NOTES", noteX, topY + 1, 9.0, bold = true)

        val onTile = tips.filter {
            val x = (it.x + 0.5) * totalW / sourceWidth
            val y = (it.y + 0.5) * totalH / sourceHeight
            x >= tile.x && x < tile.x + tile.width && y >= tile.y && y < tile.y + tile.height
        }.distinctBy { it.kind }
        val notes = ArrayList<Note>(3)
        for (tip in onTile) {
            val y = topY + tile.y - (tip.y + 0.5) * totalH / sourceHeight
            when (tip.kind) {
                PrintTipKind.ISLAND -> notes += Note("ISLAND",
                    "This enclosed piece will fall out. Add a bridge to the outer material before cutting.", y)
                PrintTipKind.FINE_CUT -> notes += Note("FINE DETAIL",
                    "Cut this small opening last with a sharp blade. Lift it carefully.", y)
            }
        }
        if (tile.columns > 1 || tile.rows > 1) {
            notes += Note("TILE JOIN",
                "Align neighboring crop ticks. Overlap by " + options.overlapMm.roundToInt() +
                    " mm, trim one side and tape behind.", null)
        }

        // Prefer feature-aligned labels, but keep every label within the notes gutter.
        val sorted = notes.sortedByDescending { it.anchor ?: BOTTOM + NOTE_HEIGHT }
        var nextTop = topY - 15.0
        for (note in sorted) {
            val targetTop = if (note.anchor == null) BOTTOM + NOTE_HEIGHT + 6.0
                else (note.anchor + 14).coerceIn(BOTTOM + NOTE_HEIGHT, topY - 15.0)
            val top = minOf(nextTop, targetTop)
            if (top - NOTE_HEIGHT < BOTTOM) continue
            builder.append("0.7 w\n")
            line(noteX, top - 2, noteX + NOTES_WIDTH - 5, top - 2)
            text(note.kind, noteX, top - 15, 8.0, bold = true)
            for ((i, item) in wrap(note.advice, 30).take(4).withIndex()) {
                text(item, noteX, top - 28 - 10.0 * i, 7.4)
            }
            if (note.anchor != null) {
                val tickY = note.anchor.coerceIn(BOTTOM + 5, topY - 5)
                line(LEFT + printableW + 1, tickY, LEFT + printableW + 7, tickY)
            }
            nextTop = top - NOTE_HEIGHT - 8.0
        }
        text("Page " + number + " / " + totalPages, LEFT, 23.0, 8.0)
        text("Width: " + p(options.widthMm) + " mm | " + options.paper.name +
            " | Keep scaling OFF", noteX, 23.0, 7.0)
        return builder.toString()
    }

    private fun wrap(value: String, limit: Int): List<String> {
        val lines = ArrayList<String>()
        var current = ""
        for (word in value.split(' ')) {
            if (current.isNotEmpty() && current.length + word.length + 1 > limit) {
                lines += current
                current = word
            } else {
                current = if (current.isEmpty()) word else current + " " + word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun escape(value: String) = value.replace("\\", "\\\\")
        .replace("(", "\\(").replace(")", "\\)")

    private fun p(value: Double): String = (value * 1000.0).roundToInt().div(1000.0).toString()
}
