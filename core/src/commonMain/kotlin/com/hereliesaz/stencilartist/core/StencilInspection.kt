package com.hereliesaz.stencilartist.core

/**
 * Read-only inspection of one committed cut sheet, in full-image coordinates.
 * The coloured raster is a diagnostic, never a printable sheet or separator input.
 * [narrowStockPixels] counts candidate pixels, not distinct bridges or measured material strength.
 */
data class CutInspection(
    val image: RgbaImage,
    val islands: Int,
    val fineCuts: Int,
    val narrowStockPixels: Int,
)

/** Inspect actual paint openings, including independent masks created by island repair. */
object StencilInspection {
    const val STOCK = 0xFFF2F2F2.toInt()
    const val CUT = 0xFF252525.toInt()
    const val EDGE = 0xFF009DB4.toInt()
    const val NARROW = 0xFFE98A17.toInt()
    const val ISLAND = 0xFFD62748.toInt()
    const val FINE = 0xFFF5DF24.toInt()

    /**
     * Reuse the full-sheet print-tip detector (four-connected unsupported stock and physically
     * small openings). Highlight cut boundaries on the opening side. Flag retained-stock runs
     * bounded by openings at both ends if their horizontal or vertical width is below the
     * requested physical minimum. This is a conservative axis-aligned screening heuristic,
     * not a complete bridge/diagonal thickness or material-strength analysis.
     *
     * All geometry comes from [Separation.mask], not finished-colour indices. No masks change.
     * Only one sheet is analysed at a time; an excessive detail count fails explicitly.
     */
    fun inspect(
        separation: Separation,
        sheet: Int,
        physicalWidthMm: Double,
        minimumStockMm: Double,
    ): CutInspection {
        val w = separation.width
        val h = separation.height
        require(w > 0 && h > 0)
        require(physicalWidthMm.isFinite() && physicalWidthMm > 0.0)
        require(minimumStockMm.isFinite() && minimumStockMm > 0.0)
        val mask = separation.mask(sheet)
        val mmPerPixel = physicalWidthMm / w
        val tips = StencilPrintTips.detect(mask, w, h, physicalWidthMm, maxTips = 20_000)
        val pixels = IntArray(mask.size) { i ->
            if (!mask[i]) STOCK else {
                val x = i % w
                val y = i / w
                if ((x > 0 && !mask[i - 1]) || (x < w - 1 && !mask[i + 1]) ||
                    (y > 0 && !mask[i - w]) || (y < h - 1 && !mask[i + w])) EDGE else CUT
            }
        }
        var narrow = 0
        // A stock span touching the sheet edge is not bounded by opposing openings.
        // Count the union of both directional tests rather than counting overlap twice.
        fun markSpans(length: Int, lines: Int, index: (Int, Int) -> Int) {
            for (line in 0 until lines) {
                var pos = 0
                while (pos < length) {
                    if (mask[index(line, pos)]) {
                        pos++
                        continue
                    }
                    val first = pos
                    while (pos < length && !mask[index(line, pos)]) pos++
                    if (first > 0 && pos < length && (pos - first) * mmPerPixel < minimumStockMm) {
                        for (p in first until pos) {
                            val i = index(line, p)
                            if (pixels[i] != NARROW) {
                                pixels[i] = NARROW
                                narrow++
                            }
                        }
                    }
                }
            }
        }
        markSpans(w, h) { y, x -> y * w + x }
        markSpans(h, w) { x, y -> y * w + x }

        // Mark actual component anchors. The cross extends only in the diagnostic image
        // to make small details visible at fit-to-screen zoom. It is never a cut or print note.
        val radius = (maxOf(w, h) / 100).coerceIn(1, 20)
        val halfThickness = radius / 6
        fun mark(tip: PrintTip, color: Int) {
            for (d in -radius..radius) for (offset in -halfThickness..halfThickness) {
                val x = tip.x + d
                val y = tip.y + d
                val row = tip.y + offset
                val column = tip.x + offset
                if (x in 0 until w && row in 0 until h) pixels[row * w + x] = color
                if (y in 0 until h && column in 0 until w) pixels[y * w + column] = color
            }
        }
        val islands = tips.count { it.kind == PrintTipKind.ISLAND }
        val fine = tips.count { it.kind == PrintTipKind.FINE_CUT }
        for (tip in tips) if (tip.kind == PrintTipKind.FINE_CUT) mark(tip, FINE)
        for (tip in tips) if (tip.kind == PrintTipKind.ISLAND) mark(tip, ISLAND)
        return CutInspection(RgbaImage(w, h, pixels), islands, fine, narrow)
    }
}
