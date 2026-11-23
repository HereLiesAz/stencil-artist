package com.hereliesaz.stencilartist.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrintSizingTest {
    private val letter = Paper.US_LETTER.oriented(false)

    // Letter is 215.9 x 279.4: printable 209.9 x 273.4 after the 3 mm border; step 208.9 x 272.4 with the 1 mm overlap.

    @Test
    fun sheetsWideAndHighConvertToMillimetresForAPaper() {
        assertEquals(209.9, PrintSizing.widthMmForSheetsWide(1.0, letter), 1e-9)
        assertEquals(2 * 208.9 + 1.0, PrintSizing.widthMmForSheetsWide(2.0, letter), 1e-9)
        assertEquals(208.9 * 2.5 + 1.0, PrintSizing.widthMmForSheetsWide(2.5, letter), 1e-9)
        // Two sheets high of a piece twice as high as wide: height 2 * 272.4 + 1 = 545.8, width half of that.
        assertEquals(272.9, PrintSizing.widthMmForSheetsHigh(2.0, 2.0, letter), 1e-9)
        val landscape = Paper.US_LETTER.oriented(true)
        assertEquals(3 * (279.4 - 7.0) + 1.0, PrintSizing.widthMmForSheetsWide(3.0, landscape), 1e-9)
    }

    @Test
    fun theInverseGivesBackTheSheetCount() {
        for (n in listOf(0.5, 1.0, 2.0, 3.25, 7.9)) {
            assertEquals(n, PrintSizing.sheetsWide(PrintSizing.widthMmForSheetsWide(n, letter), letter), 1e-9)
            assertEquals(n, PrintSizing.sheetsHigh(PrintSizing.widthMmForSheetsHigh(n, 1.3, letter), 1.3, letter), 1e-9)
        }
    }

    @Test
    fun aWholeNumberOfSheetsMakesExactlyThatManyFullPages() {
        val w = PrintSizing.widthMmForSheetsWide(3.0, letter)
        val plan = Tiling.plan(w, 100.0, PrintPdf.spec(letter))
        assertEquals(3, plan.cols)
        assertEquals(plan.spec.printableWidthMm, plan.tile(0, 2).clip.width, 1e-9, "the last page is full")
    }

    @Test
    fun aSliverIsALastColumnOrRowNarrowerThanFifteenPercentOfAPage() {
        // 15% of 209.9 is 31.5 mm. Two pages and 10 mm more leaves a 10 + 1... last column wider than 1 mm: sliver.
        val sliver = PrintSizing.widthMmForSheetsWide(2.0, letter) + 20.0
        assertTrue(PrintSizing.hasSliver(sliver, 0.1, letter))
        val fine = PrintSizing.widthMmForSheetsWide(2.0, letter) + 120.0
        assertFalse(PrintSizing.hasSliver(fine, 0.1, letter))
        assertFalse(PrintSizing.hasSliver(20.0, 0.1, letter), "a lone small page is not a sliver")
        // Rows count too: a tall piece whose last row is a sliver.
        val tallWidth = 100.0
        val rowsSliver = (PrintSizing.widthMmForSheetsHigh(2.0, 4.0, letter)) // exactly 2 whole rows, one column
        assertFalse(PrintSizing.hasSliver(rowsSliver, 4.0, letter))
        assertTrue(PrintSizing.hasSliver(rowsSliver + 4.0, 4.0, letter))
        assertTrue(tallWidth > 0)
    }

    @Test
    fun snapShrinksOrGrowsToTheNearestWholePageWidth() {
        val two = PrintSizing.widthMmForSheetsWide(2.0, letter)
        val three = PrintSizing.widthMmForSheetsWide(3.0, letter)
        // A hair over two pages: a sliver column, snaps down to exactly two.
        assertEquals(two, PrintSizing.snapToWholePages(two + 5.0, 0.2, letter), 1e-9)
        // Nearly three pages with a sliver left over is impossible (that is a wide last page); just over two and
        // a half pages is no sliver and stays as it is.
        val half = PrintSizing.widthMmForSheetsWide(2.5, letter)
        assertEquals(half, PrintSizing.snapToWholePages(half, 0.2, letter), 1e-9)
        // A sliver closer to the next whole width than to the last grows to it: a 3-page piece shy of the limit.
        val nearThree = three - 1.0
        assertFalse(PrintSizing.hasSliver(nearThree, 0.2, letter))
        // The result never has a sliver, whatever the start.
        for (w in listOf(210.5, 230.0, 420.0, 430.0, 610.0, 640.0)) {
            for (aspect in listOf(0.2, 0.75, 1.0, 1.4)) {
                assertFalse(PrintSizing.hasSliver(PrintSizing.snapToWholePages(w, aspect, letter), aspect, letter), "w=$w aspect=$aspect")
            }
        }
    }

    @Test
    fun snapFixesARowSliverToo() {
        val aspect = 1.5
        val w = PrintSizing.widthMmForSheetsHigh(2.0, aspect, letter) + 2.0 // two rows and a 3 mm third
        assertTrue(PrintSizing.hasSliver(w, aspect, letter))
        val snapped = PrintSizing.snapToWholePages(w, aspect, letter)
        assertFalse(PrintSizing.hasSliver(snapped, aspect, letter))
        assertTrue(kotlin.math.abs(snapped - w) < 0.5 * PrintPdf.stepWidthMm(letter))
    }

    @Test
    fun badInputsAreRefused() {
        assertFailsWith<IllegalArgumentException> { PrintSizing.widthMmForSheetsWide(0.0, letter) }
        assertFailsWith<IllegalArgumentException> { PrintSizing.widthMmForSheetsHigh(1.0, 0.0, letter) }
    }
}
