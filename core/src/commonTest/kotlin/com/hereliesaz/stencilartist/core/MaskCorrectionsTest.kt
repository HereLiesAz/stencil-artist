package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskCorrectionsTest {
    @Test
    fun sparseDragRemovesContinuousRowAndClipsAtBounds() {
        val source = RgbaImage(7, 3, IntArray(21) { 0xFF123456.toInt() })
        val stroke = MaskStroke(listOf(MaskPoint(-2f, 1.5f), MaskPoint(9f, 1.5f)), .5f, false)
        val result = MaskCorrections.apply(source, source, listOf(stroke))
        assertContentEquals(BooleanArray(21) { it in 7..13 }, result.excluded)
        assertContentEquals(IntArray(21) { if (it in 7..13) 0x00123456 else 0xFF123456.toInt() }, result.image.pixels)
        assertTrue(source.pixels.all { it == 0xFF123456.toInt() })
    }

    @Test
    fun restoreUsesSourceAlphaAndUndoLeavesAutomaticMask() {
        val source = RgbaImage(3, 1, intArrayOf(0x80123456.toInt(), 0xFFAA3300.toInt(), 0x00112233))
        val automatic = RgbaImage(3, 1, intArrayOf(0x00123456, 0x40AA3300, 0x00112233))
        val remove = MaskStroke(listOf(MaskPoint(.5f, .5f)), .5f, false)
        val restore = MaskStroke(listOf(MaskPoint(.5f, .5f), MaskPoint(2.5f, .5f)), .5f, true)
        val result = MaskCorrections.apply(source, automatic, listOf(remove, restore))
        assertContentEquals(source.pixels, result.image.pixels)
        assertTrue(result.excluded.none { it })
        val undone = MaskCorrections.apply(source, automatic, listOf(remove))
        assertContentEquals(automatic.pixels, undone.image.pixels)
        assertContentEquals(booleanArrayOf(true, false, false), undone.excluded)
    }

    @Test
    fun explicitBackgroundHoleCannotBeRepaintedByAnySheet() {
        val sheets = listOf(Sheet(0xFF445566.toInt()), Sheet(0xFFEE8822.toInt()))
        val nested = Separation(3, 3, sheets, IntArray(9) { 1 })
        val independent = Separation.fromMasks(3, 3, sheets, listOf(BooleanArray(9) { true }, BooleanArray(9) { it == 4 || it == 8 }))
        val excluded = BooleanArray(9) { it == 4 }
        for (s in listOf(nested, independent)) {
            val cut = s.excluding(excluded)
            assertEquals(0, cut.render()[4])
            for (i in sheets.indices) {
                assertEquals(false, cut.mask(i)[4])
                assertTrue((0 until 9).filter { it != 4 }.all { cut.mask(i)[it] == s.mask(i)[it] })
            }
            assertEquals(0xFFEE8822.toInt(), cut.render()[8])
            assertEquals(0xFFEE8822.toInt(), s.render()[4])
        }
    }

    @Test
    fun overlayPreservesOriginalAlphaAndHighlightsRemovedAreas() {
        val source = RgbaImage(3, 1, intArrayOf(0xFF000000.toInt(), 0x80123456.toInt(), 0x00112233))
        val masked = RgbaImage(3, 1, intArrayOf(0, 0x80123456.toInt(), 0x00112233))
        assertContentEquals(intArrayOf(0xFFA6101F.toInt(), 0x80123456.toInt(), 0x00112233),
            MaskCorrections.overlay(source, masked).pixels)
    }

    @Test
    fun latePaintChoicePreservesIndependentCutsAndPaintOrder() {
        val original = Separation.fromMasks(3, 1, listOf(Sheet(0xFFFF0000.toInt()), Sheet(0xFF00FF00.toInt())),
            listOf(booleanArrayOf(true, false, true), booleanArrayOf(false, true, true)))
        val changed = original.withSheetColor(0, 0xFF123456.toInt())
        assertEquals(listOf(0xFF123456.toInt(), 0xFF00FF00.toInt()), changed.sheets.map { it.color })
        assertContentEquals(intArrayOf(0xFF123456.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt()), changed.render())
        assertContentEquals(original.index, changed.index)
        for (i in original.sheets.indices) {
            assertContentEquals(original.mask(i), changed.mask(i))
            assertContentEquals(original.mask(i, Coverage.EXCLUSIVE), changed.mask(i, Coverage.EXCLUSIVE))
        }
        assertEquals(0xFFFF0000.toInt(), original.render()[0])
    }

    @Test
    fun earlyPaletteChangesAssignmentButLateRecolourDoesNot() {
        val red = 0xFFFF0000.toInt(); val darkRed = 0xFFAA0000.toInt(); val green = 0xFF00FF00.toInt()
        // Both separator dimensions must be at least two pixels. Repeat the same known regions.
        val image = RgbaImage(6, 2, intArrayOf(red, red, darkRed, darkRed, green, green,
            red, red, darkRed, darkRed, green, green))
        val first = ColorSeparator.separate(image, ColorParams(sheets = 2, colors = listOf(red, green),
            smoothing = 1, closing = 1))!!.separation
        val second = ColorSeparator.separate(image, ColorParams(sheets = 2, colors = listOf(red, darkRed),
            smoothing = 1, closing = 1))!!.separation
        // The two red regions share a cut assignment when the second paint is green.
        // Making dark red a palette centre separates them instead.
        assertEquals(red, first.render()[0])
        assertEquals(red, first.render()[2])
        assertEquals(red, second.render()[0])
        assertEquals(darkRed, second.render()[2])
        val slot = first.sheets.indexOfFirst { it.color == red }
        val late = first.withSheetColor(slot, darkRed)
        assertEquals(darkRed, late.render()[0])
        assertEquals(darkRed, late.render()[2])
        assertContentEquals(first.index, late.index)
        for (i in first.sheets.indices) assertContentEquals(first.mask(i), late.mask(i))
    }
}
