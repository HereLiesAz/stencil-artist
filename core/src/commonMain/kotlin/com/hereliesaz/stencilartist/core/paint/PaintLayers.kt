package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.Oklab
import com.hereliesaz.stencilartist.core.Separation
import kotlin.math.sqrt

/** Colour distance between paints, measured in [Oklab] like the colour separation itself. */
object PaintColour {
    /**
     * The straight-line distance between [a] and [b] in Oklab (alpha ignored). 0 is the same
     * colour; about 0.02 is the smallest difference a person sees side by side.
     */
    fun distance(a: Int, b: Int): Double = sqrt(squaredDistance(a, b))

    /** [distance] squared, which orders the same and needs no square root. */
    fun squaredDistance(a: Int, b: Int): Double {
        val la = DoubleArray(3)
        val lb = DoubleArray(3)
        Oklab.fromRgb((a shr 16) and 0xFF, (a shr 8) and 0xFF, a and 0xFF, la)
        Oklab.fromRgb((b shr 16) and 0xFF, (b shr 8) and 0xFF, b and 0xFF, lb)
        return square(la[0] - lb[0]) + square(la[1] - lb[1]) + square(la[2] - lb[2])
    }

    /**
     * The can among [among] whose colour is nearest [rgb] in Oklab, or null if none of them has a
     * colour value. Cans without one are skipped. When two are equally near (the same colour from
     * two makers, say) the one with the smaller [Can.canKey] wins, so the answer does not depend on
     * the order of [among].
     */
    fun nearest(rgb: Int, among: List<Can>): Can? {
        val target = DoubleArray(3)
        Oklab.fromRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, target)
        val lab = DoubleArray(3)
        var best: Can? = null
        var bestDistance = Double.MAX_VALUE
        for (can in among) {
            val c = can.rgb ?: continue
            Oklab.fromRgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab)
            val d = square(lab[0] - target[0]) + square(lab[1] - target[1]) + square(lab[2] - target[2])
            if (d < bestDistance || (d == bestDistance && best != null && can.canKey < best.canKey)) {
                best = can
                bestDistance = d
            }
        }
        return best
    }

    internal fun square(x: Double): Double = x * x
}

/**
 * One layer of a stencil in paint terms: a sheet colour and the cans that supply it.
 *
 * Many cans share one colour value (1,689 distinct values in the bundled 3,479 cans), and a colour
 * separation needs distinct colours, so a layer holds ALL the cans of its colour and the shopping
 * list can offer any of them.
 *
 * @property color the sheet's colour, opaque ARGB: the colour of every can in [cans].
 * @property cans the cans of exactly [color], the chosen one first, then its equivalents.
 * @property target the colour this layer stands in for (a colour of the image); [color] when the
 *   user picked the can directly.
 */
data class PaintLayer(val color: Int, val cans: List<Can>, val target: Int = color) {
    init {
        require(cans.isNotEmpty()) { "a layer needs at least one can" }
        require(cans.all { it.rgb == color }) { "every can of a layer must be exactly that layer's colour" }
    }

    /** The can the layer was made for. */
    val primary: Can get() = cans[0]

    /** The other cans of the same colour. */
    val equivalents: List<Can> get() = cans.subList(1, cans.size)

    /** How far [color] is from [target] in Oklab; 0 for a can the user picked. */
    val distance: Double get() = PaintColour.distance(target, color)
}

/**
 * The cans a user chose for a stencil.
 *
 * A colour separation takes its colours as [ColorParams.colors]: exactly as many as sheets, all
 * different in RGB, each pixel going to the nearest. Chosen cans do not fit that as they are: two
 * makers' cans can be the same colour value, and a can with no colour value has nothing to give. So
 * [distinctColours] collapses the choice into one [PaintLayer] per distinct colour.
 */
data class Selection(val cans: List<Can>) {

    /** The chosen cans that have no colour value. They cannot be a layer and are left out of [distinctColours]. */
    val colourless: List<Can> get() = cans.filter { it.rgb == null }

    /**
     * One layer per distinct colour among the chosen cans, in the order each colour first appears.
     * A layer's cans are the chosen cans of that colour, in the order chosen (a can chosen twice
     * counts once), so the first is the user's pick and the rest are its equivalents. The layers'
     * [PaintLayer.target] is their own colour.
     */
    fun distinctColours(): List<PaintLayer> {
        val groups = LinkedHashMap<Int, MutableList<Can>>()
        for (can in cans) {
            val rgb = can.rgb ?: continue
            val group = groups.getOrPut(rgb) { ArrayList(1) }
            if (can !in group) group.add(can)
        }
        return groups.map { (rgb, group) -> PaintLayer(rgb, group) }
    }

    /** The sheet colours of [distinctColours], for [ColorParams.colors]. */
    fun colors(): List<Int> = distinctColours().map { it.color }

    /**
     * [base] with its colours set to this selection's and its sheet count to their number, which is
     * the pair [ColorParams] insists on. The number of sheets is the number of DISTINCT colours, not
     * of cans.
     */
    fun colorParams(base: ColorParams = ColorParams()): ColorParams {
        val colors = colors()
        require(colors.isNotEmpty()) { "choose at least one can that has a colour value" }
        require(colors.size <= ColorParams.MAX_SHEETS) {
            "a stencil has at most ${ColorParams.MAX_SHEETS} colours, the selection has ${colors.size}"
        }
        return base.copy(sheets = colors.size, colors = colors)
    }
}

/**
 * The sheets of [separation] that no pixel shows: indices into its `sheets`, ascending.
 *
 * Separating with given colours gives every colour a sheet, even one that no pixel is nearest to.
 * Such a layer paints nothing; the user can leave that can on the shelf. (A sheet's own cut is
 * `covers(sheet, pixel)`, which also takes in later sheets; "unused" means no pixel's finished
 * colour is the sheet's, which is what makes its can unneeded.)
 */
fun unusedLayers(separation: Separation): List<Int> {
    val used = BooleanArray(separation.sheets.size)
    for (s in separation.index) if (s >= 0) used[s] = true
    return used.indices.filter { !used[it] }
}

/**
 * The [layers] whose colour is the colour of a sheet of [separation] that no pixel shows: the paints
 * the image does not need.
 */
fun unusedLayers(separation: Separation, layers: List<PaintLayer>): List<PaintLayer> {
    val idle = unusedLayers(separation).map { separation.sheets[it].color and 0xFFFFFF }.toSet()
    return layers.filter { (it.color and 0xFFFFFF) in idle }
}
