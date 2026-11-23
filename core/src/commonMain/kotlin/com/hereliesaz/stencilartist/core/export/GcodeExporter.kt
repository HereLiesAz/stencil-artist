package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point

/**
 * How the machine cuts. Feeds are in mm per minute and [power] is the `S` value (laser power or spindle
 * speed, in whatever range the controller is set to). [passes] repeats each outline. In laser mode
 * [safeZMm] and [cutZMm] are unused; in router mode ([laserMode] false) the tool plunges to [cutZMm]
 * (negative, below the surface) and lifts to [safeZMm], and with several passes each goes deeper in equal
 * steps until the last reaches [cutZMm].
 */
data class GcodeOptions(
    val feedMmPerMin: Double = 1200.0,
    val travelMmPerMin: Double = 6000.0,
    val power: Double = 1000.0,
    val passes: Int = 1,
    val laserMode: Boolean = true,
    val safeZMm: Double = 5.0,
    val cutZMm: Double = -1.0,
) {
    init {
        require(feedMmPerMin > 0.0 && feedMmPerMin.isFinite()) { "feed must be positive, got $feedMmPerMin" }
        require(travelMmPerMin > 0.0 && travelMmPerMin.isFinite()) { "travel speed must be positive, got $travelMmPerMin" }
        require(power >= 0.0 && power.isFinite()) { "power must be >= 0, got $power" }
        require(passes >= 1) { "passes must be at least 1, got $passes" }
        require(safeZMm.isFinite() && cutZMm.isFinite() && cutZMm < safeZMm) { "cut depth $cutZMm must be below the safe height $safeZMm" }
    }
}

/**
 * Outline-only G-code for GRBL lasers and routers, in millimetres with y up (`G21 G90`), one comment per
 * sheet naming it. Laser mode uses dynamic power (`M4 S0`): every travel is a `G0` carrying `S0`, so the
 * beam is off, and every cut is a `G1` with `F` and `S`. Router mode spins up with `M3`, lifts to the
 * safe height for every travel, plunges at the cutting feed, and cuts at the cut depth. Each ring is cut
 * `passes` times from its start, which it returns to. When several sheets are written the program
 * pauses with `M0` between them so the material or tool can be changed; it ends with `M2`.
 */
object GcodeExporter {
    /** The G-code for [drawing] at [size]; [sheet] (null writes all, with `M0` between) and [tile] as for [SvgExporter.export]. */
    fun export(
        drawing: Drawing,
        size: PhysicalSize,
        sheet: Int? = null,
        tile: Tile? = null,
        options: GcodeOptions = GcodeOptions(),
    ): String {
        val frame = Frame(drawing, size, tile)
        val sheets = Frame.sheets(drawing, sheet)
        val sb = StringBuilder()
        fun n(v: Double) = Decimal.format(v, 3)
        fun line(s: String) = sb.append(s).append('\n')
        for ((k, i) in sheets.withIndex()) {
            if (k > 0) {
                line("M0 ; pause: change material for sheet ${i + 1} of ${drawing.sheets.size}")
            }
            line("; Stencil Artist - sheet ${i + 1} of ${drawing.sheets.size} (colour ${Decimal.rgbHex(drawing.sheets[i].color)})")
            line("G21 G90")
            if (options.laserMode) line("M4 S0") else {
                line("G0 Z${n(options.safeZMm)}")
                line("M3 S${n(options.power)}")
            }
            for (rings in frame.shapes(drawing.sheets[i])) {
                for (ring in rings) cutRing(sb, ring.map { Point(it.x, frame.up(it.y)) }, options)
            }
            if (options.laserMode) line("M5") else {
                line("G0 Z${n(options.safeZMm)}")
                line("M5")
            }
        }
        line("M2")
        return sb.toString()
    }

    private fun cutRing(sb: StringBuilder, ring: List<Point>, o: GcodeOptions) {
        fun n(v: Double) = Decimal.format(v, 3)
        val start = ring.first()
        if (o.laserMode) {
            sb.append("G0 X${n(start.x)} Y${n(start.y)} F${n(o.travelMmPerMin)} S0\n")
            for (pass in 1..o.passes) {
                for ((j, p) in (ring.drop(1) + start).withIndex()) {
                    sb.append("G1 X${n(p.x)} Y${n(p.y)}")
                    if (j == 0) sb.append(" F${n(o.feedMmPerMin)} S${n(o.power)}")
                    sb.append('\n')
                }
            }
        } else {
            sb.append("G0 Z${n(o.safeZMm)}\n")
            sb.append("G0 X${n(start.x)} Y${n(start.y)} F${n(o.travelMmPerMin)}\n")
            for (pass in 1..o.passes) {
                val z = o.cutZMm * pass / o.passes
                sb.append("G1 Z${n(z)} F${n(o.feedMmPerMin)}\n")
                for (p in ring.drop(1) + start) sb.append("G1 X${n(p.x)} Y${n(p.y)}\n")
            }
            sb.append("G0 Z${n(o.safeZMm)}\n")
        }
    }
}
