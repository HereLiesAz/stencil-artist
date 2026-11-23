package com.hereliesaz.stencilartist.core.export

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A sheet of board the printed pages are glued on and cut through with the paper. [widthMm] x [heightMm]
 * is how it lies as given; [oriented] turns it to portrait or landscape. A board is at least
 * [MIN_SIDE_MM] (18 in) on both sides: anything smaller is too small to be worth mounting on.
 */
data class Board(val name: String, val widthMm: Double, val heightMm: Double) {
    init {
        require(widthMm.isFinite() && heightMm.isFinite() && widthMm > 0.0 && heightMm > 0.0) {
            "board sizes must be positive, got $widthMm x $heightMm mm"
        }
        require(widthMm >= MIN_SIDE_MM - 1e-6 && heightMm >= MIN_SIDE_MM - 1e-6) {
            "A board must be at least 18 in (${Decimal.format(MIN_SIDE_MM, 1)} mm) on both sides; this one is " +
                "${Decimal.format(widthMm / PhysicalSize.MM_PER_INCH, 1)} x ${Decimal.format(heightMm / PhysicalSize.MM_PER_INCH, 1)} in."
        }
    }

    /** This board with its longer side across ([landscape]) or down (portrait). */
    fun oriented(landscape: Boolean): Board {
        val short = minOf(widthMm, heightMm)
        val long = maxOf(widthMm, heightMm)
        return if (landscape) copy(widthMm = long, heightMm = short) else copy(widthMm = short, heightMm = long)
    }

    companion object {
        /** The smallest side a board may have: 18 in. */
        const val MIN_SIDE_MM = 18 * PhysicalSize.MM_PER_INCH

        /** Poster board, 22 x 28 in: the only preset. Use heavyweight poster board or 4-ply/6-ply railroad board; thin poster board tears. */
        val POSTER_22X28 = Board("Poster board 22 x 28 in", 22 * PhysicalSize.MM_PER_INCH, 28 * PhysicalSize.MM_PER_INCH)

        /** Any board of [widthMm] by [heightMm]; at least 18 in each way. */
        fun custom(widthMm: Double, heightMm: Double): Board = Board("Custom board", widthMm, heightMm)
    }
}

/**
 * Mounting on [board]: every board keeps [borderMm] of uncut board around the stencil on all four sides, so
 * its cut area is the board less the border each side. The border is at least [MIN_BORDER_MM] (1 in); the
 * default is [DEFAULT_BORDER_MM] (2 in, which makes 22 x 28 in poster board an 18 x 24 in cut area).
 */
data class MountOptions(val board: Board, val borderMm: Double = DEFAULT_BORDER_MM) {
    init {
        require(borderMm.isFinite() && borderMm >= MIN_BORDER_MM - 1e-6) {
            "The border must be at least 1 in (${Decimal.format(MIN_BORDER_MM, 1)} mm); " +
                "${Decimal.format(borderMm / PhysicalSize.MM_PER_INCH, 2)} in leaves too little uncut board."
        }
        require(minOf(board.widthMm, board.heightMm) - 2 * borderMm >= PrintPdf.MIN_PRINTABLE_MM) {
            "A ${Decimal.format(borderMm / PhysicalSize.MM_PER_INCH, 2)} in border leaves a cut area under " +
                "${PrintPdf.MIN_PRINTABLE_MM.toInt()} mm on this board; use a smaller border or a bigger board."
        }
    }

    /** The cut area's width and height in portrait ([landscape] false) or landscape, in mm. */
    fun cutSize(landscape: Boolean): Pair<Double, Double> {
        val b = board.oriented(landscape)
        return (b.widthMm - 2 * borderMm) to (b.heightMm - 2 * borderMm)
    }

    companion object {
        /** The least border that may be left: 1 in. */
        const val MIN_BORDER_MM = 1 * PhysicalSize.MM_PER_INCH

        /** The default border: 2 in. */
        const val DEFAULT_BORDER_MM = 2 * PhysicalSize.MM_PER_INCH
    }
}

/** One board's place in the piece: its 1-based [number] (row by row from the top-left) and its full cut area in finished-piece mm. */
data class BoardCell(val number: Int, val col: Int, val row: Int, val cut: RectMm)

/**
 * The boards a piece needs, in the orientation (portrait or [landscape]) that needs fewer, portrait on a tie.
 * Board cut areas butt exactly with no overlap; each board is sprayed on its own, so a cell is always the full
 * cut area even where the piece ends inside it.
 */
class BoardGrid(val landscape: Boolean, val cols: Int, val rows: Int, val cutWidthMm: Double, val cutHeightMm: Double) {
    /** How many boards one sheet needs. */
    val count: Int get() = cols * rows

    /** The boards, row by row from the top-left. */
    val cells: List<BoardCell> = List(cols * rows) { i ->
        val r = i / cols
        val c = i % cols
        BoardCell(i + 1, c, r, RectMm(c * cutWidthMm, r * cutHeightMm, (c + 1) * cutWidthMm, (r + 1) * cutHeightMm))
    }

    companion object {
        private const val EPS = 1e-9

        /** How many cells of [cell] mm cover [total] mm. */
        fun count(total: Double, cell: Double): Int = maxOf(1, ceil(total / cell - EPS).toInt())

        /** The grid for a piece [widthMm] x [heightMm] on [mount]. */
        fun of(widthMm: Double, heightMm: Double, mount: MountOptions): BoardGrid {
            require(widthMm > 0.0 && heightMm > 0.0) { "nothing to mount: ${widthMm}x$heightMm mm" }
            val (pw, ph) = mount.cutSize(false)
            val (lw, lh) = mount.cutSize(true)
            val pc = count(widthMm, pw)
            val pr = count(heightMm, ph)
            val lc = count(widthMm, lw)
            val lr = count(heightMm, lh)
            val landscape = lc.toLong() * lr < pc.toLong() * pr
            return if (landscape) BoardGrid(true, lc, lr, lw, lh) else BoardGrid(false, pc, pr, pw, ph)
        }

        /** Whether [length] ends in a sliver: a last board narrower than [PrintSizing.SLIVER_FRACTION] of a cut area of [cell]. */
        fun endsInSliver(length: Double, cell: Double): Boolean {
            val n = count(length, cell)
            if (n < 2) return false
            return length - (n - 1) * cell < PrintSizing.SLIVER_FRACTION * cell
        }

    }
}
