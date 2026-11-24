package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StencilInspectionTest {
    private val paint = Sheet(0xFF111111.toInt())

    @Test
    fun closedRingReportsOneIslandAndDoesNotChangeCut() {
        val cut = BooleanArray(81) { i ->
            val x = i % 9; val y = i / 9
            x in 2..6 && y in 2..6 && (x == 2 || x == 6 || y == 2 || y == 6)
        }
        val stencil = Separation.fromMasks(9, 9, listOf(paint), listOf(cut))
        val before = stencil.render(0)
        val result = StencilInspection.inspect(stencil, 0, 90.0, 1.5)
        assertEquals(1, result.islands)
        assertEquals(0, result.fineCuts)
        assertEquals(StencilInspection.ISLAND, result.image.pixels[4 * 9 + 4])
        assertEquals(StencilInspection.EDGE, result.image.pixels[2 * 9 + 2])
        assertContentEquals(cut, stencil.mask(0))
        assertContentEquals(before, stencil.render(0))
    }

    @Test
    fun narrowSupportedStockIsFlaggedAtSmallSizeButNotLargeSize() {
        val cut = BooleanArray(35) { i -> i % 7 == 2 || i % 7 == 4 }
        val stencil = Separation.fromMasks(7, 5, listOf(paint), listOf(cut))
        val small = StencilInspection.inspect(stencil, 0, 7.0, 1.5)
        assertEquals(0, small.islands)
        assertEquals(2, small.fineCuts)
        assertEquals(5, small.narrowStockPixels)
        val large = StencilInspection.inspect(stencil, 0, 70.0, 1.5)
        assertEquals(0, large.fineCuts)
        assertEquals(0, large.narrowStockPixels)
        assertEquals(StencilInspection.STOCK, large.image.pixels[3])
    }

    @Test
    fun crossingStockRunsAreCountedOnlyOnce() {
        val cut = BooleanArray(9) { it in setOf(1, 3, 5, 7) }
        val stencil = Separation.fromMasks(3, 3, listOf(paint), listOf(cut))
        assertEquals(1, StencilInspection.inspect(stencil, 0, 3.0, 1.5).narrowStockPixels)
    }

    @Test
    fun independentSheetsAreInspectedRatherThanReconstructedFromFinishedColour() {
        val first = BooleanArray(25)
        val second = BooleanArray(25) { true }
        first[12] = true
        val stencil = Separation.fromMasks(5, 5, listOf(paint, Sheet(0xFFEEEEEE.toInt())), listOf(first, second))
        assertTrue(stencil.index.all { it == 1 })
        val early = StencilInspection.inspect(stencil, 0, 5.0, 1.5)
        val late = StencilInspection.inspect(stencil, 1, 5.0, 1.5)
        assertEquals(1, early.fineCuts)
        assertEquals(StencilInspection.FINE, early.image.pixels[12])
        assertEquals(0, late.fineCuts)
        assertTrue(late.image.pixels.all { it == StencilInspection.CUT })
    }

    @Test
    fun invalidPhysicalDimensionsAndSheetFailExplicitly() {
        val stencil = Separation.fromMasks(1, 1, listOf(paint), listOf(booleanArrayOf(false)))
        assertFailsWith<IllegalArgumentException> { StencilInspection.inspect(stencil, 0, Double.NaN, 1.5) }
        assertFailsWith<IllegalArgumentException> { StencilInspection.inspect(stencil, 0, 10.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { StencilInspection.inspect(stencil, 1, 10.0, 1.5) }
    }
}
