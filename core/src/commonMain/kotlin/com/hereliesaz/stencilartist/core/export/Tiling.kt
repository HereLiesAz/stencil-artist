package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import kotlin.math.ceil
import kotlin.math.min

/**
 * A rectangle in millimetres, y down, from ([x0], [y0]) to ([x1], [y1]).
 */
data class RectMm(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    init {
        require(x1 >= x0 && y1 >= y0) { "empty or inverted rectangle: ($x0, $y0)-($x1, $y1)" }
    }

    val width: Double get() = x1 - x0
    val height: Double get() = y1 - y0
}

/**
 * The sheet of material one tile goes on: a printed page, a cutting mat or a roll. [marginMm] is kept
 * clear on every side and [overlapMm] is how far neighbouring tiles repeat each other, so pieces can be
 * lined up. The printable area is the page less its margins; it must be wider than the overlap.
 *
 * A roll ([roll]) has a width but no length: the page height is infinite. A drawing wider than the
 * roll is cut into strips of the roll's width laid SIDE BY SIDE; each strip is as long as the whole
 * drawing, so a roll always gives exactly one row.
 */
data class TileSpec(
    val pageWidthMm: Double,
    val pageHeightMm: Double,
    val marginMm: Double = 10.0,
    val overlapMm: Double = 10.0,
    val name: String = "Custom",
) {
    init {
        require(pageWidthMm > 0.0 && pageWidthMm.isFinite()) { "page width must be positive, got $pageWidthMm" }
        require(pageHeightMm > 0.0 && !pageHeightMm.isNaN()) { "page height must be positive, got $pageHeightMm" }
        require(marginMm >= 0.0 && marginMm.isFinite()) { "margin must be >= 0, got $marginMm" }
        require(overlapMm >= 0.0 && overlapMm.isFinite()) { "overlap must be >= 0, got $overlapMm" }
        require(printableWidthMm > overlapMm) { "overlap $overlapMm mm leaves nothing of a $printableWidthMm mm printable width" }
        require(printableHeightMm > overlapMm) { "overlap $overlapMm mm leaves nothing of a $printableHeightMm mm printable height" }
    }

    /** Whether the length is unlimited. */
    val isRoll: Boolean get() = pageHeightMm.isInfinite()

    /** Page width less both margins. */
    val printableWidthMm: Double get() = pageWidthMm - 2 * marginMm

    /** Page height less both margins; infinite for a roll. */
    val printableHeightMm: Double get() = pageHeightMm - 2 * marginMm

    companion object {
        /** US Letter, 8.5 x 11 in, portrait, 10 mm margins. */
        val US_LETTER = TileSpec(215.9, 279.4, name = "US Letter")

        /** A4, portrait, 10 mm margins. */
        val A4 = TileSpec(210.0, 297.0, name = "A4")

        /** A3, portrait, 10 mm margins. */
        val A3 = TileSpec(297.0, 420.0, name = "A3")

        /** A 12 x 12 in cutting mat. The margin is a quarter inch, the edge a blade should stay off. */
        val MAT_12X12 = TileSpec(304.8, 304.8, marginMm = 6.35, name = "12x12 in mat")

        /** A 12 x 24 in cutting mat, 12 in across and 24 in long. */
        val MAT_12X24 = TileSpec(304.8, 609.6, marginMm = 6.35, name = "12x24 in mat")

        /** Any page of [widthMm] by [heightMm]. */
        fun custom(widthMm: Double, heightMm: Double, marginMm: Double = 10.0, overlapMm: Double = 10.0): TileSpec =
            TileSpec(widthMm, heightMm, marginMm, overlapMm)

        /** A roll [widthMm] wide and as long as needed. */
        fun roll(widthMm: Double, marginMm: Double = 10.0, overlapMm: Double = 10.0): TileSpec =
            TileSpec(widthMm, Double.POSITIVE_INFINITY, marginMm, overlapMm, name = "Roll")
    }
}

/** One piece of the drawing: where it is in the grid (from the top-left, 0-based) and what part of the drawing it covers. */
data class Tile(val row: Int, val col: Int, val clip: RectMm)

/** The grid a drawing is cut into: [rows] by [cols] [tiles], row by row from the top-left. */
data class TilePlan(val spec: TileSpec, val widthMm: Double, val heightMm: Double, val rows: Int, val cols: Int, val tiles: List<Tile>) {
    /** The tile at ([row], [col]). */
    fun tile(row: Int, col: Int): Tile {
        require(row in 0 until rows && col in 0 until cols) { "no tile ($row, $col) in a $rows x $cols grid" }
        return tiles[row * cols + col]
    }

    /** The page height needed for [tile]: the spec's, or for a roll just enough for the strip and its margins. */
    fun pageHeightMm(tile: Tile): Double = if (spec.isRoll) tile.clip.height + 2 * spec.marginMm else spec.pageHeightMm
}

/**
 * Splits a drawing across sheets of material. Tile i along an axis starts at i * (printable - overlap)
 * and is one printable length long, cut off at the drawing's edge, so neighbours share exactly the
 * overlap and the tiles together cover the drawing. A drawing that fits the printable area is one tile.
 */
object Tiling {
    private const val EPS = 1e-9

    /** The grid for a drawing [widthMm] by [heightMm] on [spec]. */
    fun plan(widthMm: Double, heightMm: Double, spec: TileSpec): TilePlan {
        require(widthMm > 0.0 && heightMm > 0.0) { "nothing to tile: ${widthMm}x$heightMm mm" }
        val cols = count(widthMm, spec.printableWidthMm, spec.overlapMm)
        val rows = if (spec.isRoll) 1 else count(heightMm, spec.printableHeightMm, spec.overlapMm)
        val stepX = spec.printableWidthMm - spec.overlapMm
        val stepY = spec.printableHeightMm - spec.overlapMm
        val tiles = ArrayList<Tile>(rows * cols)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x0 = col * stepX
                val y0 = if (spec.isRoll) 0.0 else row * stepY
                val x1 = min(widthMm, x0 + spec.printableWidthMm)
                val y1 = if (spec.isRoll) heightMm else min(heightMm, y0 + spec.printableHeightMm)
                tiles.add(Tile(row, col, RectMm(x0, y0, x1, y1)))
            }
        }
        return TilePlan(spec, widthMm, heightMm, rows, cols, tiles)
    }

    /** The grid for [drawing] at [size] on [spec]. */
    fun plan(drawing: Drawing, size: PhysicalSize, spec: TileSpec): TilePlan = plan(size.widthMm, size.heightMm(drawing), spec)

    private fun count(total: Double, printable: Double, overlap: Double): Int =
        if (total <= printable + EPS) 1 else 1 + ceil((total - printable) / (printable - overlap) - EPS).toInt()
}
