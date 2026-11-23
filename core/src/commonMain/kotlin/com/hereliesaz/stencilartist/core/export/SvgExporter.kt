package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point

/**
 * What the SVG draws. With [cutLines] the sheets are stroke-only hairlines of [strokeWidthMm] and no
 * fill, which is what cutters and lasers want. The stroke is NOT the sheet's colour: a white or pale
 * sheet would draw an invisible line on a white page. Cutter software only uses stroke colour to tell
 * layers apart, so each sheet gets a dark colour from [STROKES], cycled by sheet index, and the
 * sheets stay distinguishable whatever their paint colours are.
 */
data class SvgOptions(val cutLines: Boolean = false, val strokeWidthMm: Double = 0.1) {
    init {
        require(strokeWidthMm > 0.0 && strokeWidthMm.isFinite()) { "stroke width must be positive, got $strokeWidthMm" }
    }

    companion object {
        /** Dark, mutually distinct cut-line colours (all clearly visible on white), cycled by sheet index. */
        val STROKES: List<String> = listOf(
            "#000000", "#b00020", "#0b5fa5", "#1b7a2f", "#7a1fa2", "#b35900", "#00796b", "#5d4037",
        )

        /** The cut-line colour of sheet index [sheet]. */
        fun strokeFor(sheet: Int): String = STROKES[sheet % STROKES.size]
    }
}

/**
 * Writes a [Drawing] as SVG at true size. The `width` and `height` are in mm and the `viewBox` is the
 * same numbers, so one user unit is one millimetre and cutter software imports the file at physical
 * size. Each sheet is a `<g id="sheet-N">` (N from 1, the sheet's place in the whole drawing even when
 * only one is written), filled `evenodd`, one `<path>` per shape with its outer ring and holes as
 * subpaths. Coordinates are written to a thousandth of a millimetre.
 */
object SvgExporter {
    /**
     * The SVG for [drawing] at [size]. [sheet] writes one sheet, or all in paint order when null;
     * [tile] writes only that part of the drawing, sized to the tile.
     */
    fun export(
        drawing: Drawing,
        size: PhysicalSize,
        sheet: Int? = null,
        tile: Tile? = null,
        options: SvgOptions = SvgOptions(),
    ): String {
        val frame = Frame(drawing, size, tile)
        val w = Decimal.format(frame.widthMm, 3)
        val h = Decimal.format(frame.heightMm, 3)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${w}mm\" height=\"${h}mm\" viewBox=\"0 0 $w $h\">\n")
        for (i in Frame.sheets(drawing, sheet)) {
            val outline = drawing.sheets[i]
            val hex = Decimal.rgbHex(outline.color)
            val alpha = outline.color ushr 24
            if (options.cutLines) {
                sb.append("<g id=\"sheet-${i + 1}\" fill=\"none\" stroke=\"${SvgOptions.strokeFor(i)}\" stroke-width=\"${Decimal.format(options.strokeWidthMm, 3)}\" stroke-linejoin=\"round\">\n")
            } else {
                val opacity = if (alpha < 255) " fill-opacity=\"${Decimal.format(alpha / 255.0, 3)}\"" else ""
                sb.append("<g id=\"sheet-${i + 1}\" fill=\"$hex\"$opacity fill-rule=\"evenodd\">\n")
            }
            for (rings in frame.shapes(outline)) {
                sb.append("<path d=\"")
                for ((r, ring) in rings.withIndex()) {
                    if (r > 0) sb.append(' ')
                    appendRing(sb, ring)
                }
                sb.append("\"/>\n")
            }
            sb.append("</g>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun appendRing(sb: StringBuilder, ring: List<Point>) {
        for ((i, p) in ring.withIndex()) {
            sb.append(if (i == 0) "M " else " L ")
            sb.append(Decimal.format(p.x, 3)).append(' ').append(Decimal.format(p.y, 3))
        }
        sb.append(" Z")
    }
}
