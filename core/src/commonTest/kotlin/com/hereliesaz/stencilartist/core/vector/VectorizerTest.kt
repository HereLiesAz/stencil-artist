package com.hereliesaz.stencilartist.core.vector

import com.hereliesaz.stencilartist.core.Coverage
import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.Sheet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorizerTest {
    private val exact = VectorOptions(tolerance = 0.0, minArea = 0.0, smoothing = 0)

    /** Colours that are not greys, one of them translucent. */
    private fun colors(n: Int) = List(n) { i ->
        val alpha = if (i == 1) 0x80 else 0xFF
        (alpha shl 24) or ((40 + i * 29) shl 16) or ((210 - i * 23) shl 8) or (15 + i * 31)
    }

    /**
     * A 60x40 image: a frame of NONE, then n nested rectangles, sheet i showing in the band between
     * rectangle i and i+1, and a hole punched in the deepest band so a hole survives.
     */
    private fun nested(n: Int): Separation {
        val w = 60
        val h = 40
        val index = IntArray(w * h) { p ->
            val x = p % w
            val y = p / w
            val ring = minOf(x, y, w - 1 - x, h - 1 - y)
            if (ring < 2) Separation.NONE else minOf((ring - 2) / 2, n - 1)
        }
        return Separation(w, h, colors(n).map { Sheet(it) }, index)
    }

    private fun area(o: SheetOutline) = o.shapes.sumOf { it.area }

    @Test
    fun nSheetsInGiveNOutlinesOutWithTheSameColoursInTheSameOrder() {
        for (n in listOf(1, 3, 7)) {
            val s = nested(n)
            val d = Vectorizer.vectorize(s)
            assertEquals(n, d.sheets.size)
            assertEquals(s.sheets.map { it.color }, d.sheets.map { it.color })
            assertEquals(60.0, d.width)
            assertEquals(40.0, d.height)
        }
    }

    @Test
    fun underOverpaintEachSheetCoversAtLeastAsMuchAsTheNext() {
        for (n in listOf(3, 7)) {
            val s = nested(n)
            for (options in listOf(exact, VectorOptions())) {
                val areas = Vectorizer.vectorize(s, Coverage.OVERPAINT, options).sheets.map { area(it) }
                for (i in 0 until n - 1) assertTrue(areas[i] >= areas[i + 1], "n=$n sheet $i: ${areas[i]} < ${areas[i + 1]}")
            }
        }
    }

    @Test
    fun exactOverpaintAreasMatchThePixelCounts() {
        val s = nested(3)
        val d = Vectorizer.vectorize(s, Coverage.OVERPAINT, exact)
        for (i in 0 until 3) assertEquals(s.mask(i).count { it }.toDouble(), area(d.sheets[i]))
    }

    @Test
    fun exclusiveCoverageGivesDisjointBands() {
        val s = nested(3)
        val d = Vectorizer.vectorize(s, Coverage.EXCLUSIVE, exact)
        val painted = s.index.count { it != Separation.NONE }.toDouble()
        assertEquals(painted, d.sheets.sumOf { area(it) })
        // The deepest sheet is a solid block; the others are frames with holes.
        assertTrue(d.sheets[0].shapes.single().holes.isNotEmpty())
        assertTrue(d.sheets[2].shapes.single().holes.isEmpty())
    }

    @Test
    fun theSingleSheetEntryPointMatchesTheWholeDrawing() {
        val s = nested(7)
        val whole = Vectorizer.vectorize(s)
        for (i in 0 until 7) assertEquals(whole.sheets[i], Vectorizer.vectorizeSheet(s, i))
        assertFailsWith<IllegalArgumentException> { Vectorizer.vectorizeSheet(s, 7) }
    }

    @Test
    fun unsimplifiedOutlinesRasteriseBackToTheSheetMask() {
        val s = nested(3)
        val d = Vectorizer.vectorize(s, Coverage.OVERPAINT, exact)
        for (i in 0 until 3) assertContentEquals(s.mask(i), rasterize(d.sheets[i].shapes, s.width, s.height))
    }

    @Test
    fun specksBelowMinAreaAreGoneAndDefaultsGiveCurves() {
        val w = 30
        val h = 20
        val index = IntArray(w * h) { Separation.NONE }
        for (y in 5..14) for (x in 5..20) index[y * w + x] = 0
        index[2 * w + 2] = 0 // a one-pixel speck
        val s = Separation(w, h, listOf(Sheet(0xFF336699.toInt())), index)
        assertEquals(2, Vectorizer.vectorize(s, options = exact).sheets[0].shapes.size)
        val d = Vectorizer.vectorize(s)
        assertEquals(1, d.sheets[0].shapes.size)
        assertEquals(8, d.sheets[0].shapes[0].outer.points.size, "a rectangle with its four corners cut")
    }

    @Test
    fun anEmptySheetGivesAnOutlineWithNoShapes() {
        val s = Separation(4, 4, listOf(Sheet(0xFF123456.toInt())), IntArray(16) { Separation.NONE })
        assertTrue(Vectorizer.vectorize(s).sheets[0].shapes.isEmpty())
    }

    @Test
    fun optionsAreValidated() {
        assertFailsWith<IllegalArgumentException> { VectorOptions(tolerance = -1.0) }
        assertFailsWith<IllegalArgumentException> { VectorOptions(minArea = -1.0) }
        assertFailsWith<IllegalArgumentException> { VectorOptions(smoothing = 99) }
    }
}
