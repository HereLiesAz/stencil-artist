package com.hereliesaz.stencilartist.core.export

/**
 * [inches] writes coordinates in inches instead of millimetres. DXF R12 has no units variable
 * (`$INSUNITS` arrived in R13), so the file cannot say which it is: the importing software must be told,
 * or assume. Millimetres are the default.
 */
data class DxfOptions(val inches: Boolean = false)

/**
 * Writes a [Drawing] as ASCII DXF release 12 (`AC1009`), the oldest dialect and the one the widest range
 * of cutter software reads. One layer per sheet, named `SHEET_N` (N from 1, the sheet's place in the
 * whole drawing), coloured from a small AutoCAD Color Index table that cycles when there are more
 * sheets than entries. Each ring is a closed `POLYLINE` (flag 70 = 1) of `VERTEX` records ended by
 * `SEQEND`: R12 has no `LWPOLYLINE`. Holes are rings like any other, on the same layer, since R12 has no
 * hatch. The y axis points up, as DXF does, so y is flipped from the drawing.
 *
 * Section order is HEADER, TABLES (LTYPE, LAYER), ENTITIES, then EOF. The sheet's colour is not written;
 * a DXF layer has only an index colour.
 */
object DxfExporter {
    private val ACI = intArrayOf(1, 3, 5, 2, 6, 4, 30, 8)

    /** The ACI colour of sheet index [sheet]. */
    fun aci(sheet: Int): Int = ACI[sheet % ACI.size]

    /** The layer name of sheet index [sheet]. */
    fun layerName(sheet: Int): String = "SHEET_${sheet + 1}"

    /** The DXF for [drawing] at [size]; [sheet] and [tile] as for [SvgExporter.export]. */
    fun export(
        drawing: com.hereliesaz.stencilartist.core.vector.Drawing,
        size: PhysicalSize,
        sheet: Int? = null,
        tile: Tile? = null,
        options: DxfOptions = DxfOptions(),
    ): String {
        val frame = Frame(drawing, size, tile)
        val unit = if (options.inches) 1.0 / PhysicalSize.MM_PER_INCH else 1.0
        val decimals = if (options.inches) 6 else 4
        val sheets = Frame.sheets(drawing, sheet)
        val sb = StringBuilder()
        fun pair(code: Int, value: String) {
            sb.append(code.toString().padStart(3)).append('\n').append(value).append('\n')
        }

        pair(0, "SECTION"); pair(2, "HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(0, "ENDSEC")

        pair(0, "SECTION"); pair(2, "TABLES")
        pair(0, "TABLE"); pair(2, "LTYPE"); pair(70, "1")
        pair(0, "LTYPE"); pair(2, "CONTINUOUS"); pair(70, "0"); pair(3, "Solid line"); pair(72, "65"); pair(73, "0"); pair(40, "0.0")
        pair(0, "ENDTAB")
        pair(0, "TABLE"); pair(2, "LAYER"); pair(70, (sheets.size + 1).toString())
        pair(0, "LAYER"); pair(2, "0"); pair(70, "0"); pair(62, "7"); pair(6, "CONTINUOUS")
        for (i in sheets) {
            pair(0, "LAYER"); pair(2, layerName(i)); pair(70, "0"); pair(62, aci(i).toString()); pair(6, "CONTINUOUS")
        }
        pair(0, "ENDTAB")
        pair(0, "ENDSEC")

        pair(0, "SECTION"); pair(2, "ENTITIES")
        for (i in sheets) {
            val layer = layerName(i)
            for (rings in frame.shapes(drawing.sheets[i])) {
                for (ring in rings) {
                    pair(0, "POLYLINE"); pair(8, layer); pair(66, "1")
                    pair(10, "0.0"); pair(20, "0.0"); pair(30, "0.0"); pair(70, "1")
                    for (p in ring) {
                        pair(0, "VERTEX"); pair(8, layer)
                        pair(10, Decimal.format(p.x * unit, decimals, pointAlways = true))
                        pair(20, Decimal.format(frame.up(p.y) * unit, decimals, pointAlways = true))
                        pair(30, "0.0")
                    }
                    pair(0, "SEQEND"); pair(8, layer)
                }
            }
        }
        pair(0, "ENDSEC")
        pair(0, "EOF")
        return sb.toString()
    }
}
