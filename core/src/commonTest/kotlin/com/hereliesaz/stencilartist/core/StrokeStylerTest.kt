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
    fun broadUpperToneIsReconstructedFromPositiveRibbonsWithoutNewIslands() {
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
        val cut = styled.mask(1)
        val changed = original.indices.count { original[it] && !cut[it] }
        assertTrue(changed > 0, "a broad uniform region should be reconstructed with gaps between paint ribbons")
        assertTrue(changed <= (original.count { it } * 0.20).toInt(), "texture cannot erase an arbitrary share of the layer")
        assertContentEquals(input.mask(0), styled.mask(0), "the black silhouette is never stylized")
        assertTrue(StencilPrintTips.detect(styled.mask(1), w, h, 100.0)
            .none { it.kind == PrintTipKind.ISLAND }, "no new retained-stock islands may be introduced by ribbon reconstruction")
        assertContentEquals(styled.render(), StrokeStyler.stylize(image(w, h), input).render(),
            "the same source must generate identical brushstroke geometry")
    }

    @Test
    fun strokesRespondToTheSourceGradientInsteadOfBeingFixedGeometricStripes() {
        val w = 96
        val h = 96
        val broad = BooleanArray(w * h) { i ->
            (i % w) in 12..83 && (i / w) in 12..83
        }
        val input = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, broad))
        val horizontalRamp = image(w, h)
        val verticalRamp = RgbaImage(w, h, IntArray(w * h) { i ->
            val v = (45 + (i / w) * 2).coerceAtMost(245)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
        val alongVertical = StrokeStyler.stylize(horizontalRamp, input).mask(1)
        val alongHorizontal = StrokeStyler.stylize(verticalRamp, input).mask(1)
        assertFalse(alongVertical.contentEquals(alongHorizontal),
            "rotating a strong luminance gradient must change the ribbon paths")
        assertTrue(StencilPrintTips.detect(alongVertical, w, h, 96.0)
            .none { it.kind == PrintTipKind.ISLAND })
        assertTrue(StencilPrintTips.detect(alongHorizontal, w, h, 96.0)
            .none { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun darkUncutDetailsAndUnrelatedOpeningsAreNotErasedToMakeTexture() {
        val w = 110
        val h = 92
        val base = BooleanArray(w * h) { true }
        val grayMask = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 10..95 && y in 10..79) && !(x in 48..52 && y in 39..43)
        }
        val input = Separation.fromMasks(w, h, listOf(black, gray), listOf(base, grayMask))
        val styled = StrokeStyler.stylize(image(w, h), input)
        val result = styled.mask(1)
        for (y in 39..43) for (x in 48..52) {
            assertFalse(result[y * w + x], "the dark eye/detail exclusion must remain unpainted")
        }
        assertTrue(result.indices.all { !result[it] || grayMask[it] },
            "painting must never leak outside the original stencil openings")
        assertContentEquals(input.mask(0), styled.mask(0))
    }

    @Test
    fun attachedFineHighlightRemainsIntactWhileItsBroadRegionBecomesStrokes() {
        // The attached 3 px tail was erased by the first ribbon generator because the
        // connected blob as a whole was broad. This is a required detail, not filler.
        val w = 140
        val h = 92
        val opening = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 15..85 && y in 15..75) || (x in 85..115 && y in 44..46)
        }
        val original = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, opening))
        val flat = RgbaImage(w, h, IntArray(w * h) { 0xFF888888.toInt() })
        val result = StrokeStyler.stylize(flat, original).mask(1)
        for (y in 44..46) for (x in 88..115) {
            assertTrue(result[y * w + x], "the attached narrow stroke at ($x,$y) must survive")
        }
        assertTrue((25..75).any { y -> (25..75).any { x -> !result[y * w + x] } },
            "the broad area must still have reconstructed paint-ribbon gaps")
        assertTrue(StencilPrintTips.detect(result, w, h, 140.0)
            .none { it.kind == PrintTipKind.ISLAND })
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
