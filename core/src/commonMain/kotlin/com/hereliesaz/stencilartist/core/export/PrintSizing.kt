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

    /** Whether a piece [widthMm] wide and [aspect] times as high ends in a sliver column or row. */
    fun hasSliver(widthMm: Double, aspect: Double, paper: Paper): Boolean =
        isSliver(widthMm, PrintPdf.printableWidthMm(paper), PrintPdf.stepWidthMm(paper)) ||
            isSliver(widthMm * aspect, PrintPdf.printableHeightMm(paper), PrintPdf.stepHeightMm(paper))

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
    fun snapToWholePages(widthMm: Double, aspect: Double, paper: Paper): Double {
        if (!hasSliver(widthMm, aspect, paper)) return widthMm
        val candidates = ArrayList<Double>()
        val across = sheetsWide(widthMm, paper)
        val down = sheetsHigh(widthMm, aspect, paper)
        for (n in listOf(floor(across), floor(across) + 1)) if (n >= 1.0) candidates += widthMmForSheetsWide(n, paper)
        for (n in listOf(floor(down), floor(down) + 1)) if (n >= 1.0) candidates += widthMmForSheetsHigh(n, aspect, paper)
        val clean = candidates.filter { !hasSliver(it, aspect, paper) }
        val pool = clean.ifEmpty { candidates }
        return pool.minByOrNull { abs(it - widthMm) } ?: widthMm
    }
}
