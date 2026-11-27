package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
        // The hand-specified 71 x 61 paint patch contains 4331 pixels; no more than
        // 866 of them may be exchanged for negative space in this fixture.
        assertTrue(changed <= 866, "texture cannot erase an arbitrary share of the layer")
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
    fun negativeBrushGapsEnterFromRightWhenLeftEdgeTouchesCanvas() {
        val w = 150
        val h = 120
        val opening = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 0..95 && y in 12..105
        }
        val input = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, opening))
        val actual = StrokeStyler.stylize(image(w, h), input).mask(1)
        assertFalse(actual[19 * w + 95],
            "The right edge must support a connected inward brush gap even when no left edge can")
        assertTrue(actual.indices.all { !actual[it] || opening[it] },
            "A negative brush gap must never add paint beyond the original opening")
        assertTrue(StencilPrintTips.detect(actual, w, h, 150.0)
            .none { it.kind == PrintTipKind.ISLAND },
            "The new stroke entry must not create unsupported retained-stock islands")
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
    fun negativeBrushstrokesCannotEraseAnAttachedSinglePixelHighlight() {
        val w = 120
        val h = 90
        val paint = BooleanArray(w * h) { p ->
            val x = p % w
            val y = p / w
            (x in 25..95 && y in 10..70) || (x in 20..24 && y == 17)
        }
        val image = RgbaImage(w, h, IntArray(w * h) { 0xFF777777.toInt() })
        val source = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, paint))
        val styled = StrokeStyler.stylize(image, source).mask(1)
        for (x in 20..24) {
            assertTrue(styled[17 * w + x],
                "single-pixel highlight at ($x,17) must not be carved as a negative stroke")
        }
        assertTrue((24..60).any { y -> (35..82).any { x -> !styled[y * w + x] } },
            "wide coat or face areas must still show connected stroke texture")
    }

    @Test
    fun negativeGapsDoNotEnlargeAlreadyUnsupportedMaterial() {
        // A narrow paint ring encloses unsupported stock. A separate large painted
        // patch inside that ring cannot safely carve negative marks into the stock:
        // there is NO route from that stock to the sheet's outside edge.
        val w = 160
        val h = 160
        val paint = BooleanArray(w * h) { p ->
            val x = p % w
            val y = p / w
            val ring = x in 10..145 && y in 10..145 &&
                (x !in 13..142 || y !in 13..142)
            val inner = x in 40..110 && y in 40..110
            ring || inner
        }
        val image = RgbaImage(w, h, IntArray(w * h) { 0xFF888888.toInt() })
        val source = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, paint))
        val actual = StrokeStyler.stylize(image, source).mask(1)
        for (y in 40..110) for (x in 40..110) {
            assertTrue(actual[y * w + x],
                "unsupported retained material must not grow into the inner painted patch")
        }
        assertFalse(actual[20 * w + 20], "the original uncut material must remain unpainted")
        assertContentEquals(paint, source.mask(1), "the source stencil stays unchanged")
    }

    @Test
    fun attachedDiagonalHighlightsRemainFullyPainted() {
        // A 5-row-wide diagonal branch is visually thinner than its horizontal
        // bounding runs. Check the geometry itself, not a calculation reproduced
        // from the reconstruction algorithm.
        val w = 160
        val h = 150
        val originalMask = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 15..85 && y in 15..75) ||
                (x in 85..125 && kotlin.math.abs(y - (x - 40)) <= 2)
        }
        val flat = RgbaImage(w, h, IntArray(w * h) { 0xFF777777.toInt() })
        val source = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, originalMask))
        val styled = StrokeStyler.stylize(flat, source).mask(1)
        for (x in 88..125) for (y in (x - 42)..(x - 38)) {
            assertTrue(styled[y * w + x],
                "attached diagonal highlight must survive at ($x,$y)")
        }
        assertTrue(StencilPrintTips.detect(styled, w, h, 160.0)
            .none { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun aLongThinStrokeCannotConsumeTheQuotaOfItsAttachedBroadPatch() {
        val w = 900
        val h = 80
        val opening = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 20..39 && y in 20..39) || (x in 39..839 && y in 28..30)
        }
        val flat = RgbaImage(w, h, IntArray(w * h) { 0xFF888888.toInt() })
        val input = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, opening))
        val actual = StrokeStyler.stylize(flat, input).mask(1)
        val retainedSquare = (20..39).sumOf { y -> (20..39).count { x -> actual[y * w + x] } }
        assertTrue(retainedSquare >= 280,
            "at least 70% of a required broad patch must remain, regardless of a long attached line")
        for (y in 28..30) for (x in 45..839) {
            assertTrue(actual[y * w + x], "the slim extended feature must remain continuous")
        }
        assertTrue(StencilPrintTips.detect(actual, w, h, 900.0)
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
    fun oversizedBrushPreservesAWholeNarrowBlotchyOpening() {
        val w = 150
        val h = 110
        // This 101 x 9 patch is well above the existing small-component cutoff.
        // Its *width across the paint* is nonetheless smaller than the chosen brush.
        val patch = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 15..115 && y in 45..53
        }
        val original = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, patch))
        val actual = StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 16)
        assertContentEquals(patch, actual.mask(1),
            "a brush wider than a region must leave its original solid/blotchy shape intact")
        assertContentEquals(original.mask(0), actual.mask(0))
    }

    @Test
    fun oversizedBrushPreservesNarrowRegionBesideStylizedBroadPaint() {
        val w = 180
        val h = 130
        val opening = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 12..110 && y in 12..98) ||
                (x in 135..172 && y in 54..62)
        }
        val original = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, opening))
        val actual = StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 16).mask(1)
        for (y in 54..62) for (x in 135..172) {
            assertTrue(actual[y * w + x],
                "the narrow separate region must retain every original paint pixel")
        }
        assertTrue((25..85).any { y -> (25..96).any { x -> !actual[y * w + x] } },
            "a separate, substantially larger region must still admit brush gaps")
        assertTrue(actual.indices.all { !actual[it] || opening[it] },
            "reconstructed strokes cannot paint outside the original openings")
        assertTrue(StencilPrintTips.detect(actual, w, h, 180.0)
            .none { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun oversizedBrushDoesNotHollowAnAttachedNarrowBranch() {
        val w = 170
        val h = 120
        val opening = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (x in 15..80 && y in 15..96) ||
                (x in 81..139 && y in 48..54)
        }
        val original = Separation.fromMasks(w, h, listOf(black, white),
            listOf(BooleanArray(w * h) { true }, opening))
        val actual = StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 18).mask(1)
        for (y in 48..54) for (x in 84..139) {
            assertTrue(actual[y * w + x],
                "the 7-pixel branch must stay solid when the brush is 18 pixels wide")
        }
        assertTrue((24..75).any { y -> (25..70).any { x -> !actual[y * w + x] } },
            "a separate broad interior should still be stylized")
        assertTrue(StencilPrintTips.detect(actual, w, h, 170.0)
            .none { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun changingBrushSizeChangesTheMarksAndKeepsTheSameOriginalCoverageBounds() {
        val w = 140
        val h = 135
        val patch = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 15..123 && y in 14..117
        }
        val original = Separation.fromMasks(w, h, listOf(black, gray),
            listOf(BooleanArray(w * h) { true }, patch))
        val small = StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 2).mask(1)
        val large = StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 12).mask(1)
        assertFalse(small.contentEquals(large),
            "the width control must affect rendered geometry, not merely a UI readout")
        assertTrue(small.indices.all { !small[it] || patch[it] })
        assertTrue(large.indices.all { !large[it] || patch[it] })
        assertContentEquals(small, StrokeStyler.stylize(image(w, h), original).mask(1),
            "the original 2px default behavior must remain unchanged")
    }

    @Test
    fun brushWidthValidationRejectsOutOfRangeValues() {
        val w = 20
        val h = 20
        val original = Separation(w, h, listOf(black), IntArray(w * h) { 0 })
        assertFailsWith<IllegalArgumentException> {
            StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 25)
        }
        assertTrue(StrokeStyler.stylize(image(w, h), original, strokeWidthPixels = 24) === original)
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
