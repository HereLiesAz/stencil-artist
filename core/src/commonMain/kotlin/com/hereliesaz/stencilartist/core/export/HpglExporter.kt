package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import kotlin.math.floor

/**
 * Writes a [Drawing] as HP-GL for roll plotters and vinyl cutters. The plotter unit is 0.025 mm, 40 to
 * the millimetre, written as integers; y points up. The file is `IN;`, then for each sheet `SP<pen>;`
 * (pens cycle 1 to 8 down the sheets) and each ring as `PU x,y;PD x,y,...;` ending back on its start so
 * the path is closed, and finally `PU0,0;SP0;`. Long rings are split into several `PD` commands of at
 * most [PAIRS_PER_PD] points, which a plotter treats as one continuous stroke.
 */
object HpglExporter {
    /** Plotter units per millimetre. */
    const val UNITS_PER_MM = 40.0

    /** Most coordinate pairs in one PD command. */
    const val PAIRS_PER_PD = 64

    /** The pen of sheet index [sheet]: 1 to 8, cycling. */
    fun pen(sheet: Int): Int = sheet % 8 + 1

    /** The HP-GL for [drawing] at [size]; [sheet] and [tile] as for [SvgExporter.export]. */
    fun export(drawing: Drawing, size: PhysicalSize, sheet: Int? = null, tile: Tile? = null): String {
        val frame = Frame(drawing, size, tile)
        val sb = StringBuilder("IN;\n")
        fun units(mm: Double): String = floor(mm * UNITS_PER_MM + 0.5).toLong().toString()
        for (i in Frame.sheets(drawing, sheet)) {
            sb.append("SP${pen(i)};\n")
            for (rings in frame.shapes(drawing.sheets[i])) {
                for (ring in rings) {
                    val coords = ArrayList<String>(ring.size + 1)
                    for (p in ring + ring.first()) coords.add(units(p.x) + "," + units(frame.up(p.y)))
                    sb.append("PU").append(coords[0]).append(";\n")
                    var k = 1
                    while (k < coords.size) {
                        val end = minOf(coords.size, k + PAIRS_PER_PD)
                        sb.append("PD").append(coords.subList(k, end).joinToString(",")).append(";\n")
                        k = end
                    }
                }
            }
        }
        sb.append("PU0,0;SP0;\n")
        return sb.toString()
    }
}
