package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrokeStylerTest {
    private val black = Sheet(0xFF000000.toInt())
    private val gray = Sheet(0xFF808080.toInt())
    private val white = Sheet(0xFFFFFFFF.toInt())

    private fun image(w: Int, h: Int): RgbaImage = RgbaImage(
        w, h, IntArray(w * h) { i ->
            val x = i % w
            val brightness = (45 + x * 2).coerceAtMost(245)
            (0xFF shl 24) or (brightness shl 16) or (brightness shl 8) or brightness
        },
    )

    @Test
    fun broadUpperToneBecomesFineConnectedNegativeBrushstrokes() {
        val w = 100
        val h = 90
        val base = BooleanArray(w * h) { true }
        val grayMask = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 15..85 && y in 15..75
        }
        val input = Separation.fromMasks(w, h, listOf(black, gray), listOf(base, grayMask))
        val original = input.mask(1)
        val styled = StrokeStyler.stylize(image(w, h), input)
        val changed = original.indices.count { original[it] && !styled.mask(1)[it] }
        assertTrue(changed > 0, "a broad uniform region should develop some inward negative strokes")
        assertTrue(changed <= (original.count { it } * 0.085).toInt(), "texture cannot erase an arbitrary share of the layer")
        assertContentEquals(input.mask(0), styled.mask(0), "the black silhouette is never stylized")
        assertTrue(StencilPrintTips.detect(styled.mask(1), w, h, 100.0)
            .none { it.kind == PrintTipKind.ISLAND }, "every negative stroke starts at supported stock")
        assertContentEquals(styled.render(), StrokeStyler.stylize(image(w, h), input).render(),
            "the same source must generate identical brushstroke geometry")
    }

    @Test
    fun tinyRequiredDetailsStayUnchanged() {
        val w = 100
        val h = 90
        val tiny = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 39..41 && y in 15..56
        }
        val input = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, tiny))
        val styled = StrokeStyler.stylize(image(w, h), input)
        assertContentEquals(tiny, styled.mask(1), "a narrow highlight must not be punched full of gaps")
    }

    @Test
    fun stylingIsOptInAndDoesNotAffectSingleSheetOrVeryLargeStacks() {
        val w = 50
        val h = 50
        val onlyBase = Separation(w, h, listOf(black), IntArray(w * h) { 0 })
        assertTrue(StrokeStyler.stylize(image(w, h), onlyBase) === onlyBase)
        val many = Separation(w, h, List(17) { gray }, IntArray(w * h) { 0 })
        assertTrue(StrokeStyler.stylize(image(w, h), many) === many)
    }
}
