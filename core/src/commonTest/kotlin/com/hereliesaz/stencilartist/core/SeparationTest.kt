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
    fun aSheetThatIsNotThereIsRefused() {
        val s = three(listOf(black, grey, white))
        assertFailsWith<IllegalArgumentException> { s.render(3) }
        assertFailsWith<IllegalArgumentException> { s.mask(-1) }
    }
}
