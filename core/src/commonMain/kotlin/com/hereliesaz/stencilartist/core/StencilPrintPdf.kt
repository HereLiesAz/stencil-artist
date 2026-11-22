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
 * The original cut mask is never changed. Problem-specific advice is printed only on surviving
 * uncut stock. When a tip cannot fit, a separate reference page shows its nearby cut geometry
 * alongside the advice, with no cut lines crossed and nothing discarded. Islands are diagnosed
 * once on the full sheet; a tile seam must not create a spurious island.
 *
 * Print at 100% / actual size. PDF viewers' "fit to page" option changes the cutting scale.
 */
object StencilPrintPdf {
    private const val PT_PER_MM = 72.0 / 25.4
    private const val LEFT = 24.0
    private const val RIGHT = 24.0
    private const val TOP = 55.0
    private const val BOTTOM = 46.0
    // Text is never printed over a cut. Every label reserves an extra no-cut buffer.
    private const val TIP_WIDTH = 146.0
    private const val TIP_HEIGHT = 49.0
    private const val TIP_CLEARANCE = 5.0

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

    private data class Note(
        val key: String,
        val sheet: Int,
        val lines: List<String>,
        val x: Double,
        val y: Double,
    )
    private data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun overlaps(other: Rect): Boolean = x < other.x + other.width + 4 &&
            x + width + 4 > other.x && y < other.y + other.height + 4 &&
            y + height + 4 > other.y
    }

    /**
     * Summed-area table of cut openings. A note may occupy a rectangle only when the entire
     * rectangle and its safety clearance contain zero cut pixels. No line or pointer is drawn.
     */
    private class UncutSpace(mask: BooleanArray, val width: Int, val height: Int) {
        private val stride = width + 1
        private val sums = IntArray((width + 1) * (height + 1))

        init {
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) {
                    if (mask[y * width + x]) row++
                    sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
                }
            }
        }

        fun clear(x: Double, y: Double, w: Double, h: Double, totalW: Double, totalH: Double): Boolean {
            val x0 = kotlin.math.floor((x - TIP_CLEARANCE) * width / totalW).toInt()
            val x1 = kotlin.math.ceil((x + w + TIP_CLEARANCE) * width / totalW).toInt()
            val y0 = kotlin.math.floor((y - TIP_CLEARANCE) * height / totalH).toInt()
            val y1 = kotlin.math.ceil((y + h + TIP_CLEARANCE) * height / totalH).toInt()
            if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) return false
            val cutCount = sums[y1 * stride + x1] - sums[y0 * stride + x1] -
                sums[y1 * stride + x0] + sums[y0 * stride + x0]
            return cutCount == 0
        }
    }

    fun make(separation: Separation, options: StencilPrintOptions = StencilPrintOptions()): ByteArray {
        require(separation.width > 0 && separation.height > 0 && separation.sheets.isNotEmpty())
        require(options.widthMm > 0.0 && options.widthMm.isFinite())
        require(options.overlapMm >= 0.0 && options.overlapMm.isFinite())

        val pageW = options.paper.widthPt
        val pageH = options.paper.heightPt
        val printableW = pageW - LEFT - RIGHT
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
        val templatePages = columns.toLong() * rows * separation.sheets.size
        require(templatePages in 1..4096) { "Printout needs too many pages" }

        val tiles = ArrayList<Tile>(templatePages.toInt())
        val tips = ArrayList<List<PrintTip>>(separation.sheets.size)
        val masks = ArrayList<BooleanArray>(separation.sheets.size)
        val clearSpaces = ArrayList<UncutSpace>(separation.sheets.size)
        for (s in separation.sheets.indices) {
            val mask = separation.mask(s)
            masks += mask
            clearSpaces += UncutSpace(mask, separation.width, separation.height)
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

        // First place every tip on cut-free stock, including overlapping neighbor tiles. The
        // remaining tips are shown beside their original geometry on additional reference pages.
        val required = linkedMapOf<String, Note>()
        val placed = mutableSetOf<String>()
        val templateContents = tiles.mapIndexed { index, tile ->
            pageContent(tile, tips[tile.sheet], clearSpaces[tile.sheet], options, pageW, pageH,
                totalW, totalH, separation.width, separation.height, separation.sheets.size,
                index + 1, tiles.size, required, placed)
        }
        val referenceGroups = required.values.filter { it.key !in placed }.chunked(4)
        val pageCount = tiles.size + referenceGroups.size
        require(pageCount <= 4096) { "Printout needs too many reference pages" }

        // 1: catalog; 2: page tree; 3,4: fonts; then images; then page and content pairs.
        val imageStart = 5
        val pageStart = imageStart + separation.sheets.size
        val size = pageStart + pageCount * 2
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
            val content = templateContents[i].replace(
                "Page " + (i + 1) + " / " + tiles.size,
                "Page " + (i + 1) + " / " + pageCount,
            )
            objects[streamId] = "<< /Length " + content.length + " >>\nstream\n" + content + "endstream"
        }
        for ((groupIndex, notes) in referenceGroups.withIndex()) {
            val index = tiles.size + groupIndex
            val pageId = pageStart + index * 2
            val streamId = pageId + 1
            children.append(pageId).append(" 0 R ")
            val images = notes.map { it.sheet }.distinct().joinToString(" ") {
                "/Im" + (it + 1) + " " + (imageStart + it) + " 0 R"
            }
            objects[pageId] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " +
                p(pageW) + " " + p(pageH) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>" +
                " /XObject << " + images + " >> >> /Contents " + streamId + " 0 R >>"
            val content = referenceContent(notes, pageW, pageH, totalW, totalH, index + 1, pageCount)
            objects[streamId] = "<< /Length " + content.length + " >>\nstream\n" + content + "endstream"
        }
        objects[2] = "<< /Type /Pages /Kids [" + children + "] /Count " + pageCount + " >>"

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

    /**
     * Place contextual notes INSIDE the uncut white regions of the physical stencil, not in
     * margins or in cut openings. A note that cannot fit moves to a companion reference page.
     */
    private fun pageContent(
        tile: Tile,
        tips: List<PrintTip>,
        uncut: UncutSpace,
        options: StencilPrintOptions,
        pageW: Double,
        pageH: Double,
        totalW: Double,
        totalH: Double,
        sourceWidth: Int,
        sourceHeight: Int,
        sheetCount: Int,
        number: Int,
        totalPages: Int,
        required: MutableMap<String, Note>,
        placed: MutableSet<String>,
    ): String {
        val topY = pageH - TOP
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

        text(
            "SHEET " + (tile.sheet + 1) + " / " + sheetCount +
                "     TILE R" + (tile.row + 1) + "C" + (tile.col + 1) +
                " OF " + tile.rows + "x" + tile.columns,
            LEFT, pageH - 29, 10.0, bold = true,
        )
        text("PRINT AT 100% - ACTUAL SIZE", pageW - 218.0, pageH - 29, 8.0)

        // Draw the original cut geometry at true size; notes are not part of this image.
        builder.append("q ").append(p(LEFT)).append(' ').append(p(topY - tile.height))
            .append(' ').append(p(tile.width)).append(' ').append(p(tile.height))
            .append(" re W n ").append(p(totalW)).append(" 0 0 ").append(p(totalH))
            .append(' ').append(p(LEFT - tile.x)).append(' ').append(p(topY + tile.y - totalH))
            .append(" cm /Im").append(tile.sheet + 1).append(" Do Q\n")

        // Crop ticks live OUTSIDE the actual stencil; they cannot be confused with cut lines.
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

        val relevant = tips.filter {
            val x = (it.x + 0.5) * totalW / sourceWidth
            val y = (it.y + 0.5) * totalH / sourceHeight
            x >= tile.x && x < tile.x + tile.width && y >= tile.y && y < tile.y + tile.height
        }
        val notes = ArrayList<Note>()
        for (tip in relevant) {
            val x = (tip.x + 0.5) * totalW / sourceWidth
            val y = (tip.y + 0.5) * totalH / sourceHeight
            val position = ((x / PT_PER_MM).roundToInt()).toString() + "," +
                ((y / PT_PER_MM).roundToInt()).toString() + "mm"
            val lines = when (tip.kind) {
                PrintTipKind.ISLAND -> listOf("ISLAND", "At " + position,
                    "Add small bridges", "before cutting.")
                PrintTipKind.FINE_CUT -> listOf("FINE DETAIL", "At " + position,
                    "Cut this part last.", "Lift waste slowly.")
            }
            val key = "S" + tile.sheet + ":" + tip.kind + ":" + tip.x + ":" + tip.y
            notes += Note(key, tile.sheet, lines, x, y)
        }
        if (tile.columns > 1 || tile.rows > 1) {
            notes += Note(
                "S" + tile.sheet + ":JOIN:" + tile.row + ":" + tile.col,
                tile.sheet,
                listOf("TILE JOIN", "Align crop ticks.", "Overlap " + p(options.overlapMm) + " mm.",
                    "Tape from behind."),
                tile.x + tile.width / 2,
                tile.y + tile.height / 2,
            )
        }

        val used = ArrayList<Rect>()
        for (note in notes) {
            required[note.key] = note
            if (note.key in placed) continue
            // Helvetica glyph widths never exceed one em. Using that upper bound prevents
            // even an unusually long heading from escaping its pixel-verified clear rectangle.
            if (note.lines.withIndex().any { (index, line) ->
                line.length * (if (index == 0) 7.4 else 7.0) > TIP_WIDTH - 4.0
            }) continue
            // A sparse search favors the closest safe material while avoiding previous labels.
            var best: Rect? = null
            var bestDist = Double.POSITIVE_INFINITY
            var y = 0.0
            while (y + TIP_HEIGHT <= tile.height) {
                var x = 0.0
                while (x + TIP_WIDTH <= tile.width) {
                    val globalX = tile.x + x
                    val globalY = tile.y + y
                    val rect = Rect(x, y, TIP_WIDTH, TIP_HEIGHT)
                    if (used.none { it.overlaps(rect) } &&
                        uncut.clear(globalX, globalY, TIP_WIDTH, TIP_HEIGHT, totalW, totalH)
                    ) {
                        val cx = globalX + TIP_WIDTH / 2
                        val cy = globalY + TIP_HEIGHT / 2
                        val dx = cx - note.x
                        val dy = cy - note.y
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist) {
                            bestDist = dist
                            best = rect
                        }
                    }
                    x += 12.0
                }
                y += 12.0
            }
            val rect = best ?: continue
            used += rect
            placed += note.key
            // Muted text, no boxes, leaders or arrows: the only marks in the stencil are words
            // entirely enclosed by surviving stock. Every glyph lies inside the checked rectangle.
            builder.append("q 0.42 g\n")
            for ((index, label) in note.lines.withIndex()) {
                val fontSize = if (index == 0) 7.4 else 7.0
                text(label, LEFT + rect.x + 2.0, topY - rect.y - 11.0 - index * 11.0,
                    fontSize, bold = index == 0)
            }
            builder.append("Q\n")
        }

        text("Page " + number + " / " + totalPages, LEFT, 23.0, 8.0)
        text("Width: " + p(options.widthMm) + " mm | " + options.paper.name +
            " | Keep scaling OFF", pageW - 247.0, 23.0, 7.0)
        return builder.toString()
    }

    /**
     * The companion page is non-cutting reference material. Each original-size local cut detail
     * occupies the left of a row; its associated tip is immediately to its right on clear paper.
     * The cutting templates themselves stay untouched, and no information is lost.
     */
    private fun referenceContent(
        notes: List<Note>,
        pageW: Double,
        pageH: Double,
        totalW: Double,
        totalH: Double,
        number: Int,
        totalPages: Int,
    ): String {
        val builder = StringBuilder()
        fun text(label: String, x: Double, y: Double, size: Double = 9.0, bold: Boolean = false) {
            builder.append("BT /").append(if (bold) "F2" else "F1").append(' ')
                .append(p(size)).append(" Tf ").append(p(x)).append(' ').append(p(y))
                .append(" Td (").append(escape(label)).append(") Tj ET\n")
        }
        text("CONTEXTUAL CUTTING NOTES", LEFT, pageH - 29, 12.0, bold = true)
        text("REFERENCE ONLY - DO NOT CUT FROM THIS PAGE", LEFT, pageH - 48, 9.0, bold = true)
        text("Details shown at actual scale. Coordinates are mm from the stencil's top-left.",
            LEFT, pageH - 64, 8.0)

        for ((row, note) in notes.withIndex()) {
            val refX = LEFT + 8.0
            val refTop = pageH - 104.0 - row * 153.0
            val cropW = minOf(120.0, totalW)
            val cropH = minOf(105.0, totalH)
            val cropX = (note.x - cropW / 2).coerceIn(0.0, totalW - cropW)
            val cropY = (note.y - cropH / 2).coerceIn(0.0, totalH - cropH)
            // The excerpt and the notes are separate, so no glyph can touch the cut geometry.
            builder.append("q ").append(p(refX)).append(' ').append(p(refTop - cropH))
                .append(' ').append(p(cropW)).append(' ').append(p(cropH))
                .append(" re W n ").append(p(totalW)).append(" 0 0 ").append(p(totalH))
                .append(' ').append(p(refX - cropX)).append(' ').append(p(refTop + cropY - totalH))
                .append(" cm /Im").append(note.sheet + 1).append(" Do Q\n")

            val labelX = LEFT + 148.0
            text("SHEET " + (note.sheet + 1) + " - LOCAL DETAIL", labelX, refTop - 11.0,
                8.5, bold = true)
            for ((i, line) in note.lines.withIndex()) {
                text(line, labelX, refTop - 30.0 - i * 17.0,
                    if (i == 0) 9.5 else 9.0, bold = i == 0)
            }
            text("NOT A CUTTING TEMPLATE", labelX, refTop - 105.0, 7.5)
        }
        text("Page " + number + " / " + totalPages, LEFT, 23.0, 8.0)
        return builder.toString()
    }

    private fun escape(value: String) = value.replace("\\", "\\\\")
        .replace("(", "\\(").replace(")", "\\)")

    private fun p(value: Double): String = (value * 1000.0).roundToInt().div(1000.0).toString()
}
