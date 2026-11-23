package com.hereliesaz.stencilartist.core.export

import kotlin.math.abs
import kotlin.math.floor

/**
 * How big a print is, counted in pages as well as in millimetres. Pages are the oriented [Paper] less
 * the printer's fixed border, advancing by [PrintPdf.OVERLAP_MM] less than their printable size, so
 * "N sheets wide" means a piece exactly N pages wide with the last page full. Everything here is pure
 * arithmetic; decimals are allowed (2.5 sheets wide ends in a half page).
 */
object PrintSizing {
    /** A last column or row narrower than this fraction of a page is a sliver. */
    const val SLIVER_FRACTION = 0.15

    /** The finished width, in mm, of a piece [sheets] pages wide on [paper]. */
    fun widthMmForSheetsWide(sheets: Double, paper: Paper): Double {
        require(sheets > 0.0 && sheets.isFinite()) { "sheets wide must be positive, got $sheets" }
        return sheets * PrintPdf.stepWidthMm(paper) + PrintPdf.OVERLAP_MM
    }

    /** How many pages wide a piece [widthMm] wide is, as a decimal. */
    fun sheetsWide(widthMm: Double, paper: Paper): Double = (widthMm - PrintPdf.OVERLAP_MM) / PrintPdf.stepWidthMm(paper)

    /**
     * The finished width, in mm, of a piece [sheets] pages high on [paper] whose height is [aspect]
     * (height divided by width) times its width.
     */
    fun widthMmForSheetsHigh(sheets: Double, aspect: Double, paper: Paper): Double {
        require(sheets > 0.0 && sheets.isFinite()) { "sheets high must be positive, got $sheets" }
        require(aspect > 0.0 && aspect.isFinite()) { "aspect must be positive, got $aspect" }
        return (sheets * PrintPdf.stepHeightMm(paper) + PrintPdf.OVERLAP_MM) / aspect
    }

    /** How many pages high a piece [widthMm] wide is, as a decimal, for a piece [aspect] times as high as wide. */
    fun sheetsHigh(widthMm: Double, aspect: Double, paper: Paper): Double =
        (widthMm * aspect - PrintPdf.OVERLAP_MM) / PrintPdf.stepHeightMm(paper)

    /**
     * Whether a piece [widthMm] wide and [aspect] times as high ends in a sliver column or row. When
     * mounting ([mount] given) the columns and rows are boards in the orientation [BoardGrid.of] picks, and a
     * partial page at a board's edge is not a sliver: pages within a board may end wherever the board does.
     */
    fun hasSliver(widthMm: Double, aspect: Double, paper: Paper, mount: MountOptions? = null): Boolean {
        if (mount != null) {
            val grid = BoardGrid.of(widthMm, widthMm * aspect, mount)
            return BoardGrid.endsInSliver(widthMm, grid.cutWidthMm) || BoardGrid.endsInSliver(widthMm * aspect, grid.cutHeightMm)
        }
        return hasPageSliver(widthMm, aspect, paper)
    }

    private fun hasPageSliver(widthMm: Double, aspect: Double, paper: Paper): Boolean =
        isSliver(widthMm, PrintPdf.printableWidthMm(paper), PrintPdf.stepWidthMm(paper)) ||
            isSliver(widthMm * aspect, PrintPdf.printableHeightMm(paper), PrintPdf.stepHeightMm(paper))

    /** The finished width, in mm, of a piece [boards] boards wide, the boards lying portrait or [landscape]. */
    fun widthMmForBoardsWide(boards: Double, mount: MountOptions, landscape: Boolean): Double {
        require(boards > 0.0 && boards.isFinite()) { "boards wide must be positive, got $boards" }
        return boards * mount.cutSize(landscape).first
    }

    /** How many boards wide a piece [widthMm] wide is, as a decimal, with boards lying portrait or [landscape]. */
    fun boardsWide(widthMm: Double, mount: MountOptions, landscape: Boolean): Double = widthMm / mount.cutSize(landscape).first

    /** The finished width, in mm, of a piece [boards] boards high whose height is [aspect] times its width. */
    fun widthMmForBoardsHigh(boards: Double, aspect: Double, mount: MountOptions, landscape: Boolean): Double {
        require(boards > 0.0 && boards.isFinite()) { "boards high must be positive, got $boards" }
        require(aspect > 0.0 && aspect.isFinite()) { "aspect must be positive, got $aspect" }
        return boards * mount.cutSize(landscape).second / aspect
    }

    /** How many boards high a piece [widthMm] wide and [aspect] times as high is, as a decimal. */
    fun boardsHigh(widthMm: Double, aspect: Double, mount: MountOptions, landscape: Boolean): Double =
        widthMm * aspect / mount.cutSize(landscape).second

    private fun isSliver(length: Double, printable: Double, step: Double): Boolean {
        val count = Tiling.count(length, printable, PrintPdf.OVERLAP_MM)
        if (count < 2) return false
        return length - (count - 1) * step < SLIVER_FRACTION * printable
    }

    /**
     * [widthMm] nudged so the last column and row are not slivers: unchanged if neither is, otherwise the
     * nearest width that is a whole number of pages across or down (shrinking or growing, whichever is
     * closer) and leaves no sliver in the other direction either. If no such width exists the nearest
     * whole-page width is returned.
     */
    fun snapToWholePages(widthMm: Double, aspect: Double, paper: Paper, mount: MountOptions? = null): Double {
        if (mount != null) return snapToWholeBoards(widthMm, aspect, mount)
        if (!hasSliver(widthMm, aspect, paper)) return widthMm
        val candidates = ArrayList<Double>()
        val across = sheetsWide(widthMm, paper)
        val down = sheetsHigh(widthMm, aspect, paper)
        for (n in listOf(floor(across), floor(across) + 1)) if (n >= 1.0) candidates += widthMmForSheetsWide(n, paper)
        for (n in listOf(floor(down), floor(down) + 1)) if (n >= 1.0) candidates += widthMmForSheetsHigh(n, aspect, paper)
        val clean = candidates.filter { !hasPageSliver(it, aspect, paper) }
        val pool = clean.ifEmpty { candidates }
        return pool.minByOrNull { abs(it - widthMm) } ?: widthMm
    }

    /**
     * When mounting: [widthMm] nudged so the last board column and row are not slivers (under 15% of a board's cut
     * area): unchanged if neither is, otherwise the nearest width that is a whole number of boards across or down
     * in the orientation chosen at [widthMm], preferring one that leaves no sliver. Pages inside a board are never snapped.
     */
    fun snapToWholeBoards(widthMm: Double, aspect: Double, mount: MountOptions): Double {
        if (!hasSliver(widthMm, aspect, Paper.US_LETTER, mount)) return widthMm
        val landscape = BoardGrid.of(widthMm, widthMm * aspect, mount).landscape
        val candidates = ArrayList<Double>()
        val across = boardsWide(widthMm, mount, landscape)
        val down = boardsHigh(widthMm, aspect, mount, landscape)
        for (n in listOf(floor(across), floor(across) + 1)) if (n >= 1.0) candidates += widthMmForBoardsWide(n, mount, landscape)
        for (n in listOf(floor(down), floor(down) + 1)) if (n >= 1.0) candidates += widthMmForBoardsHigh(n, aspect, mount, landscape)
        val clean = candidates.filter { !hasSliver(it, aspect, Paper.US_LETTER, mount) }
        return (clean.ifEmpty { candidates }).minByOrNull { abs(it - widthMm) } ?: widthMm
    }
}
