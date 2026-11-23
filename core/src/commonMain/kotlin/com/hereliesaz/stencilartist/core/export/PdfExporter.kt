package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.SheetOutline
import kotlin.math.max
import kotlin.math.min

/**
 * Vector PDFs of a [Drawing], written by hand: PDF 1.4, uncompressed streams, the standard Helvetica
 * font and nothing else, so it is the same bytes on every target. All geometry is true physical size
 * (1 point is 1/72 inch); the page is never scaled. Print at 100% / actual size.
 *
 * [single] puts the artwork on one page sized to it. [tiled] is the vector twin of the print PDF: one
 * PDF per sheet, an assembly map first and then one page per tile.
 */
object PdfExporter {
    private const val PT_PER_MM = 72.0 / PhysicalSize.MM_PER_INCH
    private const val MAX_PAGES = 4096
    private const val INK = "0.1 0.1 0.1"

    /**
     * One page exactly the size of the artwork, every sheet in [sheets] (default all) filled in its own
     * colour with the even-odd rule (`f*`), in the order given, so later sheets paint over earlier ones.
     * A sheet colour with alpha is drawn with that opacity.
     */
    fun single(drawing: Drawing, size: PhysicalSize, sheets: List<Int> = drawing.sheets.indices.toList()): ByteArray {
        require(sheets.isNotEmpty()) { "no sheets to write" }
        for (s in sheets) require(s in drawing.sheets.indices) { "no sheet $s in a ${drawing.sheets.size}-sheet drawing" }
        val placement = Placement(drawing, size)
        val doc = PdfDoc()
        val content = StringBuilder()
        for (s in sheets) {
            val outline = drawing.sheets[s]
            content.append("q\n")
            doc.alphaState(outline.color)?.let { content.append("/$it gs\n") }
            content.append(rgb(outline.color)).append(" rg\n")
            for (shape in outline.shapes) content.append(path(shape.rings.map { r -> r.points }, placement)).append("f*\n")
            content.append("Q\n")
        }
        doc.addPage(size.widthMm * PT_PER_MM, placement.heightMm * PT_PER_MM, content.toString())
        return doc.finish("Stencil Artist - vector stencil")
    }

    /**
     * The tiled print PDF of sheet [sheetIndex] on [spec]. Page 1 is an assembly map: the whole sheet
     * drawn small with the tile grid and each tile's row and column, plus the physical size and tile
     * count as text. Then one page per tile, row by row from the top-left, showing that tile's part of
     * the sheet at true scale, clipped with a PDF clip path so no polygon-clipping artefacts appear. Each
     * tile page has registration crosshairs at the corners of the area inset by half the overlap, which
     * is the middle of the band neighbouring tiles share, so the crosshairs of adjacent pages land on the
     * same spot when overlapped; dashed lines on that inset on every side that has a neighbour; and a
     * margin label such as "Sheet 2 of 5 - Row 1 Col 3".
     *
     * The cut region is filled in the sheet's colour AND outlined in a dark line. A sheet can be
     * near-white (a highlight, say), and a pale fill on white paper is invisible: the outline is what
     * the person cutting follows.
     */
    fun tiled(drawing: Drawing, size: PhysicalSize, sheetIndex: Int, spec: TileSpec): ByteArray {
        require(sheetIndex in drawing.sheets.indices) { "no sheet $sheetIndex in a ${drawing.sheets.size}-sheet drawing" }
        val plan = Tiling.plan(drawing, size, spec)
        require(plan.tiles.size < MAX_PAGES) { "needs ${plan.tiles.size + 1} pages, more than the $MAX_PAGES allowed" }
        val outline = drawing.sheets[sheetIndex]
        val placement = Placement(drawing, size)
        val label = "Sheet ${sheetIndex + 1} of ${drawing.sheets.size}"
        val doc = PdfDoc()
        val shapes = outline.shapes.map { PlacedPath(it.rings.map { r -> r.points }, placement) }

        // Assembly map.
        val aw = if (spec.isRoll) max(spec.pageWidthMm, 210.0) else spec.pageWidthMm
        val ah = if (spec.isRoll) max(aw * 1.4142, 297.0) else spec.pageHeightMm
        doc.addPage(aw * PT_PER_MM, ah * PT_PER_MM, assembly(plan, shapes, outline, placement, label, aw, ah, doc))

        for (tile in plan.tiles) {
            val ph = plan.pageHeightMm(tile)
            doc.addPage(
                spec.pageWidthMm * PT_PER_MM, ph * PT_PER_MM,
                tilePage(plan, tile, shapes, outline, placement, label, ph, doc),
            )
        }
        return doc.finish("Stencil Artist - $label tiled print")
    }

    /** A shape's rings in drawing-space points (y up from the drawing's bottom), with their extent in mm (y down). */
    private class PlacedPath(rings: List<List<Point>>, placement: Placement) {
        val data: String = path(rings, placement)
        val minX: Double
        val maxX: Double
        val minY: Double
        val maxY: Double

        init {
            var x0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (p in rings[0]) {
                x0 = min(x0, placement.x(p.x)); x1 = max(x1, placement.x(p.x))
                y0 = min(y0, placement.y(p.y)); y1 = max(y1, placement.y(p.y))
            }
            minX = x0; maxX = x1; minY = y0; maxY = y1
        }
    }

    /** The path operators of [rings] (outer then holes), closed, in points with y up from the drawing's bottom edge. */
    private fun path(rings: List<List<Point>>, placement: Placement): String {
        val sb = StringBuilder()
        for (ring in rings) {
            for ((i, p) in ring.withIndex()) {
                sb.append(Decimal.format(placement.x(p.x) * PT_PER_MM, 3)).append(' ')
                    .append(Decimal.format(placement.yUp(p.y) * PT_PER_MM, 3)).append(if (i == 0) " m\n" else " l\n")
            }
            sb.append("h\n")
        }
        return sb.toString()
    }

    private fun rgb(argb: Int): String =
        listOf(argb shr 16 and 255, argb shr 8 and 255, argb and 255).joinToString(" ") { Decimal.format(it / 255.0, 4) }

    private fun pt(mm: Double): String = Decimal.format(mm * PT_PER_MM, 3)

    /** Fill (with the sheet's alpha) then a separate dark outline, for one placed path under the current transform. */
    private fun fillAndOutline(sb: StringBuilder, p: PlacedPath, color: Int, doc: PdfDoc, lineWidth: Double) {
        sb.append("q\n")
        doc.alphaState(color)?.let { sb.append("/$it gs\n") }
        sb.append(rgb(color)).append(" rg\n").append(p.data).append("f*\nQ\n")
        sb.append("q\n$INK RG\n${Decimal.format(lineWidth, 4)} w\n1 j\n").append(p.data).append("S\nQ\n")
    }

    private fun text(sb: StringBuilder, sizePt: Double, xPt: Double, yPt: Double, s: String) {
        sb.append("BT\n/F1 ${Decimal.format(sizePt, 2)} Tf\n${Decimal.format(xPt, 3)} ${Decimal.format(yPt, 3)} Td\n(")
        for (c in s) {
            if (c.code !in 32..126) sb.append('?') else {
                if (c == '(' || c == ')' || c == '\\') sb.append('\\')
                sb.append(c)
            }
        }
        sb.append(") Tj\nET\n")
    }

    private fun tilePage(
        plan: TilePlan, tile: Tile, shapes: List<PlacedPath>, outline: SheetOutline,
        placement: Placement, label: String, pageHeightMm: Double, doc: PdfDoc,
    ): String {
        val spec = plan.spec
        val m = spec.marginMm
        val clip = tile.clip
        val pageH = pageHeightMm * PT_PER_MM
        val sb = StringBuilder()

        // The tile's part of the sheet, clipped by a real clip path.
        sb.append("q\n")
        sb.append("${pt(m)} ${Decimal.format(pageH - (m + clip.height) * PT_PER_MM, 3)} ${pt(clip.width)} ${pt(clip.height)} re W n\n")
        val tx = (m - clip.x0) * PT_PER_MM
        val ty = pageH - m * PT_PER_MM + clip.y0 * PT_PER_MM - placement.heightMm * PT_PER_MM
        sb.append("1 0 0 1 ${Decimal.format(tx, 3)} ${Decimal.format(ty, 3)} cm\n")
        for (s in shapes) {
            if (s.maxX < clip.x0 || s.minX > clip.x1 || s.maxY < clip.y0 || s.minY > clip.y1) continue
            fillAndOutline(sb, s, outline.color, doc, 0.7)
        }
        sb.append("Q\n")

        // Dashed lines on the middle of each overlap band that has a neighbour.
        val inset = spec.overlapMm / 2
        val left = m + inset
        val right = m + clip.width - inset
        val top = m + inset
        val bottom = m + clip.height - inset
        fun y(downMm: Double) = Decimal.format(pageH - downMm * PT_PER_MM, 3)
        sb.append("q\n0.25 w\n[3 2] 0 d\n$INK RG\n")
        if (tile.col > 0) sb.append("${pt(left)} ${y(m)} m ${pt(left)} ${y(m + clip.height)} l S\n")
        if (tile.col < plan.cols - 1) sb.append("${pt(right)} ${y(m)} m ${pt(right)} ${y(m + clip.height)} l S\n")
        if (tile.row > 0) sb.append("${pt(m)} ${y(top)} m ${pt(m + clip.width)} ${y(top)} l S\n")
        if (tile.row < plan.rows - 1) sb.append("${pt(m)} ${y(bottom)} m ${pt(m + clip.width)} ${y(bottom)} l S\n")
        sb.append("Q\n")

        // Registration crosshairs, 4 mm each way, at the inset corners.
        sb.append("q\n0.5 w\n$INK RG\n")
        for (cx in listOf(left, right)) for (cy in listOf(top, bottom)) {
            sb.append("${pt(cx - 4)} ${y(cy)} m ${pt(cx + 4)} ${y(cy)} l S\n")
            sb.append("${pt(cx)} ${y(cy - 4)} m ${pt(cx)} ${y(cy + 4)} l S\n")
        }
        sb.append("Q\n")

        sb.append("0 g\n")
        val labelY = min(pageH - 10.0, pageH - m * PT_PER_MM + 4.0)
        text(sb, 9.0, m * PT_PER_MM, labelY, "$label - Row ${tile.row + 1} Col ${tile.col + 1}")
        text(
            sb, 7.0, m * PT_PER_MM, max(4.0, m * PT_PER_MM - 12.0),
            "Print at 100% - do not scale to fit. Rows ${plan.rows}, cols ${plan.cols}.",
        )
        return sb.toString()
    }

    private fun assembly(
        plan: TilePlan, shapes: List<PlacedPath>, outline: SheetOutline, placement: Placement,
        label: String, pageWidthMm: Double, pageHeightMm: Double, doc: PdfDoc,
    ): String {
        val sb = StringBuilder()
        val spec = plan.spec
        val pageH = pageHeightMm * PT_PER_MM
        val pageMargin = 15.0 * PT_PER_MM
        val textBlock = 62.0
        sb.append("0 g\n")
        text(sb, 14.0, pageMargin, pageH - pageMargin, "$label - assembly map")
        val inW = Decimal.format(plan.widthMm / PhysicalSize.MM_PER_INCH, 2)
        val inH = Decimal.format(plan.heightMm / PhysicalSize.MM_PER_INCH, 2)
        text(sb, 10.0, pageMargin, pageH - pageMargin - 16.0,
            "Finished size: ${Decimal.format(plan.widthMm, 1)} x ${Decimal.format(plan.heightMm, 1)} mm ($inW x $inH in)")
        val pages = plan.tiles.size
        text(sb, 10.0, pageMargin, pageH - pageMargin - 29.0,
            "Tiles: ${plan.cols} across x ${plan.rows} down = $pages page${if (pages == 1) "" else "s"} after this map")
        val pageDesc = if (spec.isRoll) "roll ${Decimal.format(spec.pageWidthMm, 1)} mm wide"
        else "${spec.name} ${Decimal.format(spec.pageWidthMm, 1)} x ${Decimal.format(spec.pageHeightMm, 1)} mm"
        text(sb, 10.0, pageMargin, pageH - pageMargin - 42.0,
            "Page: $pageDesc, margin ${Decimal.format(spec.marginMm, 1)} mm, overlap ${Decimal.format(spec.overlapMm, 1)} mm")
        text(sb, 8.0, pageMargin, pageH - pageMargin - 54.0,
            "Print at 100% (actual size). Pages go left to right, top to bottom; line up the crosshairs.")

        val boxLeft = pageMargin
        val boxTop = pageH - pageMargin - textBlock
        val boxW = pageWidthMm * PT_PER_MM - 2 * pageMargin
        val boxH = boxTop - pageMargin
        val k = min(boxW / plan.widthMm, boxH / plan.heightMm) // points per mm of the finished stencil
        val c = k / PT_PER_MM // scale of the drawing-space paths
        sb.append("q\n1 0 0 1 ${Decimal.format(boxLeft, 3)} ${Decimal.format(boxTop - plan.heightMm * k, 3)} cm\n")
        sb.append("${Decimal.format(c, 6)} 0 0 ${Decimal.format(c, 6)} 0 0 cm\n")
        for (s in shapes) fillAndOutline(sb, s, outline.color, doc, 0.5 / c)
        sb.append("Q\n")

        sb.append("q\n0.5 w\n0.45 G\n")
        sb.append("${Decimal.format(boxLeft, 3)} ${Decimal.format(boxTop - plan.heightMm * k, 3)} ${Decimal.format(plan.widthMm * k, 3)} ${Decimal.format(plan.heightMm * k, 3)} re S\n")
        sb.append("[2 2] 0 d\n")
        for (t in plan.tiles) {
            val r = t.clip
            sb.append("${Decimal.format(boxLeft + r.x0 * k, 3)} ${Decimal.format(boxTop - r.y1 * k, 3)} ${Decimal.format(r.width * k, 3)} ${Decimal.format(r.height * k, 3)} re S\n")
        }
        sb.append("Q\n0.2 g\n")
        for (t in plan.tiles) {
            val r = t.clip
            val fs = (min(r.width, r.height) * k / 5).coerceIn(5.0, 12.0)
            text(sb, fs, boxLeft + r.x0 * k + 3.0, boxTop - r.y0 * k - fs - 2.0, "R${t.row + 1} C${t.col + 1}")
        }
        return sb.toString()
    }

    /** Collects objects, then writes them with a correct cross-reference table. All text is ASCII, so a string index is a byte offset. */
    private class PdfDoc {
        private val bodies = ArrayList<String>()
        private val pages = ArrayList<Int>()
        private val alphas = LinkedHashMap<Int, Pair<String, Int>>()
        private val catalog = reserve()
        private val pageTree = reserve()
        private val font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        private val resources = reserve()
        private val info = reserve()

        private fun reserve(): Int {
            bodies.add("")
            return bodies.size
        }

        private fun add(body: String): Int {
            bodies.add(body)
            return bodies.size
        }

        /** The name of the graphics state for [argb]'s opacity, or null when it is opaque. */
        fun alphaState(argb: Int): String? {
            val a = argb ushr 24
            if (a == 255) return null
            return alphas.getOrPut(a) {
                val v = Decimal.format(a / 255.0, 4)
                "GA$a" to add("<< /Type /ExtGState /ca $v /CA $v >>")
            }.first
        }

        fun addPage(widthPt: Double, heightPt: Double, content: String) {
            val contentObj = add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            val box = "[0 0 ${Decimal.format(widthPt, 3)} ${Decimal.format(heightPt, 3)}]"
            pages.add(add("<< /Type /Page /Parent $pageTree 0 R /MediaBox $box /Contents $contentObj 0 R /Resources $resources 0 R >>"))
        }

        fun finish(title: String): ByteArray {
            bodies[catalog - 1] = "<< /Type /Catalog /Pages $pageTree 0 R >>"
            bodies[pageTree - 1] = "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>"
            val gs = if (alphas.isEmpty()) "" else " /ExtGState << ${alphas.values.joinToString(" ") { "/${it.first} ${it.second} 0 R" }} >>"
            bodies[resources - 1] = "<< /ProcSet [/PDF /Text] /Font << /F1 $font 0 R >>$gs >>"
            bodies[info - 1] = "<< /Title (${title.filter { it.code in 32..126 && it != '(' && it != ')' && it != '\\' }}) /Producer (Stencil Artist) >>"

            val sb = StringBuilder("%PDF-1.4\n")
            val offsets = IntArray(bodies.size)
            for ((i, body) in bodies.withIndex()) {
                offsets[i] = sb.length
                sb.append(i + 1).append(" 0 obj\n").append(body).append("\nendobj\n")
            }
            val xref = sb.length
            sb.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
            for (o in offsets) sb.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
            sb.append("trailer\n<< /Size ${bodies.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
            val text = sb.toString()
            return ByteArray(text.length) { text[it].code.toByte() }
        }
    }
}
