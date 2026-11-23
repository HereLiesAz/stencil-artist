package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeparationTest {

    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val none = Separation.NONE

    /** One pixel outside the subject, then one showing each of three sheets. */
    private fun three(colors: List<Int>) = Separation(4, 1, colors.map { Sheet(it) }, intArrayOf(none, 0, 1, 2))

    @Test
    fun theFinishedPieceShowsEveryPixelInItsOwnSheetsColourAndLeavesCutOutsTransparent() {
        assertContentEquals(intArrayOf(0, black, grey, white), three(listOf(black, grey, white)).render())
        assertContentEquals(intArrayOf(0, white, grey, black), three(listOf(white, grey, black)).render())
    }

    @Test
    fun aDarkImageGoesBlackBaseGreyMidtoneWhiteSecondary() {
        val s = three(listOf(black, grey, white))
        assertContentEquals(intArrayOf(0, black, black, black), s.render(0), "base: the whole subject")
        assertContentEquals(intArrayOf(0, 0, grey, grey), s.render(1), "midtone: grey and lighter")
        assertContentEquals(intArrayOf(0, 0, 0, white), s.render(2), "secondary: the lightest")
    }

    @Test
    fun aLightImageGoesWhiteBaseGreyMidtoneBlackSecondary() {
        val s = three(listOf(white, grey, black))
        assertContentEquals(intArrayOf(0, white, white, white), s.render(0), "base: the whole subject")
        assertContentEquals(intArrayOf(0, 0, grey, grey), s.render(1), "midtone: grey and darker")
        assertContentEquals(intArrayOf(0, 0, 0, black), s.render(2), "secondary: the darkest")
    }

    @Test
    fun overpaintedSheetsNestAndExclusiveSheetsPartitionTheSubject() {
        val index = intArrayOf(none, 0, 1, 2, 3, 4, 2, 0)
        val s = Separation(8, 1, List(5) { Sheet(black) }, index)
        for (sheet in 1 until 5) {
            val outer = s.mask(sheet - 1)
            val inner = s.mask(sheet)
            for (i in index.indices) assertTrue(!inner[i] || outer[i], "sheet $sheet escapes sheet ${sheet - 1} at $i")
        }
        val counted = IntArray(index.size)
        for (sheet in 0 until 5) s.mask(sheet, Coverage.EXCLUSIVE).forEachIndexed { i, on -> if (on) counted[i]++ }
        assertContentEquals(IntArray(index.size) { if (index[it] == none) 0 else 1 }, counted)
    }

    @Test
    fun sheetsMayBeAnyColourAndAnyNumber() {
        val colors = List(40) { (0xFF shl 24) or (it * 6 shl 16) or (255 - it * 6 shl 8) or (it * 37 and 0xFF) }
        val index = IntArray(40) { it }
        val s = Separation(40, 1, colors.map { Sheet(it) }, index)
        assertContentEquals(colors.toIntArray(), s.render())
        assertEquals(colors[17], s.render(17)[39])
        assertEquals(0, s.render(17)[16])
    }

    @Test
    fun rasterExportPlanesPreserveStraightAlphaAndOnlyShowCutPixels() {
        val base = 0x80102030.toInt()
        val middle = 0x40305070
        val top = 0xFFDCBA98.toInt()
        // The fixture is specified in paint order with a hole at each end.
        val s = Separation(7, 1, listOf(Sheet(base), Sheet(middle), Sheet(top)),
            intArrayOf(none, 0, 1, 2, 1, 0, none))
        assertContentEquals(
            intArrayOf(0, base, base, base, base, base, 0),
            s.render(0), "First PNG must preserve the complete base cut and its supplied alpha")
        assertContentEquals(
            intArrayOf(0, 0, middle, middle, middle, 0, 0),
            s.render(1), "Second PNG overlays only its cut; uncut stock is transparent")
        assertContentEquals(
            intArrayOf(0, 0, 0, top, 0, 0, 0),
            s.render(2), "Final PNG contains only the smallest, latest cut")
        assertContentEquals(
            intArrayOf(0, base, middle, top, middle, base, 0),
            s.render(), "Composite PNG must match the finished preview, not flatten to white")
    }

    @Test
    fun rasterExportsNeverAssumeThreeSheetsOrAWhiteBackground() {
        val palette = listOf(0x80FF1122.toInt(), 0xFF00EE33.toInt(),
            0x200000FF, 0xFFFFCC00.toInt(), 0xFF102030.toInt())
        val s = Separation(3, 2, palette.map(::Sheet),
            intArrayOf(none, 0, 4, 2, 3, none))
        assertContentEquals(intArrayOf(0, palette[0], palette[4], palette[2],
            palette[3], 0), s.render())
        assertContentEquals(intArrayOf(0, 0, palette[4], 0, 0, 0),
            s.render(4))
        assertContentEquals(intArrayOf(0, palette[0], palette[0], palette[0],
            palette[0], 0), s.render(0))
        // All exported planes retain the same dimensions and pixel layout as the source.
        assertEquals(6, s.render(4).size)
        assertEquals(6, s.render().size)
    }

    @Test
    fun aSheetThatIsNotThereIsRefused() {
        val s = three(listOf(black, grey, white))
        assertFailsWith<IllegalArgumentException> { s.render(3) }
        assertFailsWith<IllegalArgumentException> { s.mask(-1) }
    }
}
