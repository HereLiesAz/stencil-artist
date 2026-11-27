package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandAndGradientTest {
    private val black = Sheet(0xFF000000.toInt())
    private val grey = Sheet(0xFF777777.toInt())
    private val white = Sheet(0xFFFFFFFF.toInt())

    @Test
    fun aBridgeIsCutOnlyWhenLaterPaintConcealsEveryChangedPixel() {
        val w = 9
        val h = 9
        val base = BooleanArray(w * h) { true }
        val middle = BooleanArray(w * h)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) middle[y * w + x] = true
        }
        val top = BooleanArray(w * h)
        // White sits directly over the upper rim of grey; the rest of the grey ring
        // is deliberately not covered and must remain untouched.
        top[2 * w + 4] = true
        val source = Separation.fromMasks(w, h, listOf(black, grey, white), listOf(base, middle, top))
        val before = source.render().copyOf()
        val photograph = RgbaImage(w, h, IntArray(w * h) { white.color })
        val result = IslandPlanner.repair(source, photograph, minimumBridgePixels = 1)
        assertEquals(1, result.concealedBridges)
        assertEquals(0, result.sourceGuidedIslands,
            "an invisible structural bridge is preferable to changing visible paint")
        assertEquals(0, result.unresolved)
        assertFalse(result.separation.mask(1)[2 * w + 4], "only the hidden grey opening should become a negative stroke")
        assertContentEquals(before, result.separation.render(), "finished appearance must not be modified")
        assertTrue(StencilPrintTips.detect(
            result.separation.mask(1), w, h, 90.0,
        ).none { it.kind == PrintTipKind.ISLAND }, "the uncut centre must now connect to the outside")
        assertTrue(source.mask(1)[2 * w + 4], "the original input must remain unchanged")
    }

    @Test
    fun aVisibleBridgeIsNotInventedAndTheIslandRemainsFlagged() {
        val w = 9
        val ring = BooleanArray(w * w)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) ring[y * w + x] = true
        }
        val source = Separation.fromMasks(w, w, listOf(black, grey), listOf(BooleanArray(w * w) { true }, ring))
        val result = IslandPlanner.repair(source, minimumBridgePixels = 1)
        assertEquals(0, result.concealedBridges)
        assertEquals(1, result.unresolved)
        assertContentEquals(source.mask(1), result.separation.mask(1))
        assertContentEquals(source.render(), result.separation.render())
    }

    @Test
    fun blackIslandCanBePaintedOverAndReconstructedWithWhite() {
        val width = 9
        val baseRing = BooleanArray(width * width)
        val whiteCentre = BooleanArray(width * width)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) baseRing[y * width + x] = true
        }
        for (y in 3..5) for (x in 3..5) whiteCentre[y * width + x] = true

        val original = Separation.fromMasks(width, width, listOf(black, white), listOf(baseRing, whiteCentre))
        val expected = IntArray(width * width) { pixel ->
            val x = pixel % width
            val y = pixel / width
            when {
                x in 3..5 && y in 3..5 -> white.color
                x in 2..6 && y in 2..6 && (x == 2 || x == 6 || y == 2 || y == 6) -> black.color
                else -> 0
            }
        }
        assertContentEquals(expected, original.render())
        val plan = IslandPlanner.repair(original)
        assertEquals(1, plan.reconstructedIslands)
        assertEquals(0, plan.concealedBridges)
        assertEquals(0, plan.unresolved)
        for (y in 3..5) for (x in 3..5) {
            assertTrue(plan.separation.mask(0)[y * width + x], "black is sprayed beneath white")
            assertFalse(original.mask(0)[y * width + x], "the original layer must not be mutated")
        }
        assertContentEquals(expected, plan.separation.render())
        assertTrue(StencilPrintTips.detect(plan.separation.mask(0), width, width, 90.0)
            .none { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun blackIslandCanBeReconstructedJointlyByGreyAndWhite() {
        val width = 9
        val baseRing = BooleanArray(width * width)
        val greyCentre = BooleanArray(width * width)
        val whiteCentre = BooleanArray(width * width)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) baseRing[y * width + x] = true
        }
        for (y in 3..5) for (x in 3..5) {
            val pixel = y * width + x
            if (x <= 4) greyCentre[pixel] = true else whiteCentre[pixel] = true
        }
        val original = Separation.fromMasks(
            width, width, listOf(black, grey, white), listOf(baseRing, greyCentre, whiteCentre),
        )
        val expected = IntArray(width * width) { pixel ->
            val x = pixel % width
            val y = pixel / width
            when {
                x in 3..5 && y in 3..5 -> if (x <= 4) grey.color else white.color
                x in 2..6 && y in 2..6 && (x == 2 || x == 6 || y == 2 || y == 6) -> black.color
                else -> 0
            }
        }
        assertContentEquals(expected, original.render())
        val plan = IslandPlanner.repair(original)
        assertEquals(1, plan.reconstructedIslands)
        assertEquals(0, plan.unresolved)
        assertTrue((3..5).all { y -> (3..5).all { x -> plan.separation.mask(0)[y * width + x] } })
        assertContentEquals(expected, plan.separation.render(), "grey and white must reproduce the finished detail")
    }

    @Test
    fun anExposedIslandIsNeverErasedEvenWhenMostPixelsHaveLaterPaint() {
        val width = 9
        val baseRing = BooleanArray(width * width)
        val whiteCentre = BooleanArray(width * width)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) baseRing[y * width + x] = true
        }
        for (y in 3..5) for (x in 3..5) {
            if (x != 3 || y != 3) whiteCentre[y * width + x] = true
        }
        val original = Separation.fromMasks(width, width, listOf(black, white), listOf(baseRing, whiteCentre))
        val plan = IslandPlanner.repair(original)
        assertEquals(0, plan.reconstructedIslands)
        assertEquals(0, plan.concealedBridges)
        assertEquals(1, plan.unresolved)
        assertContentEquals(baseRing, plan.separation.mask(0))
        val expected = IntArray(width * width) { pixel ->
            val x = pixel % width
            val y = pixel / width
            when {
                x in 3..5 && y in 3..5 && (x != 3 || y != 3) -> white.color
                x in 2..6 && y in 2..6 && (x == 2 || x == 6 || y == 2 || y == 6) -> black.color
                else -> 0
            }
        }
        assertContentEquals(expected, original.render())
        assertContentEquals(expected, plan.separation.render())
    }

    @Test
    fun aMissingWhiteMarkIsComposedOnTheWhiteLayerInsteadOfDroppingTheBlackIsland() {
        val w = 9
        val blackRing = BooleanArray(w * w)
        val partialWhite = BooleanArray(w * w)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) blackRing[y * w + x] = true
        }
        for (y in 3..5) for (x in 3..5) {
            if (x != 4 || y != 4) partialWhite[y * w + x] = true
        }
        val image = RgbaImage(w, w, IntArray(w * w) { index ->
            val x = index % w
            val y = index / w
            if (x in 3..5 && y in 3..5) white.color else black.color
        })
        val original = Separation.fromMasks(w, w, listOf(black, white), listOf(blackRing, partialWhite))
        assertEquals(0, original.render()[4 * w + 4], "the original misses the centre")
        val plan = IslandPlanner.repair(original, source = image)
        assertEquals(1, plan.sourceGuidedIslands)
        assertEquals(0, plan.unresolved)
        assertEquals(0, plan.concealedBridges)
        assertTrue(plan.separation.mask(0)[4 * w + 4], "black can now be sprayed beneath the highlight")
        assertTrue(plan.separation.mask(1)[4 * w + 4], "white restores the centre as a corrective mark")
        assertFalse(original.mask(1)[4 * w + 4], "the original white stencil must not be mutated")
        val expected = IntArray(w * w) { pixel ->
            val x = pixel % w
            val y = pixel / w
            when {
                x in 3..5 && y in 3..5 -> white.color
                x in 2..6 && y in 2..6 && (x == 2 || x == 6 || y == 2 || y == 6) -> black.color
                else -> 0
            }
        }
        assertContentEquals(expected, plan.separation.render())
        for (layer in 0..1) {
            assertTrue(StencilPrintTips.detect(plan.separation.mask(layer), w, w, 90.0)
                .none { it.kind == PrintTipKind.ISLAND })
        }
    }

    @Test
    fun correctiveGreyAndWhiteMarksCanJointlyRestoreABlackLayerIsland() {
        val w = 11
        val base = BooleanArray(w * w)
        val greyCut = BooleanArray(w * w)
        val whiteCut = BooleanArray(w * w)
        for (y in 2..8) for (x in 2..8) {
            if (x == 2 || x == 8 || y == 2 || y == 8) base[y * w + x] = true
        }
        for (y in 3..7) for (x in 3..7) {
            val pixel = y * w + x
            if (x <= 5 && (x != 4 || y != 5)) greyCut[pixel] = true
            if (x >= 6 && (x != 6 || y != 5)) whiteCut[pixel] = true
        }
        val source = RgbaImage(w, w, IntArray(w * w) { pixel ->
            val x = pixel % w
            val y = pixel / w
            when {
                x in 3..5 && y in 3..7 -> grey.color
                x in 6..7 && y in 3..7 -> white.color
                else -> black.color
            }
        })
        val plan = IslandPlanner.repair(
            Separation.fromMasks(w, w, listOf(black, grey, white), listOf(base, greyCut, whiteCut)),
            source,
        )
        assertEquals(1, plan.sourceGuidedIslands)
        assertEquals(0, plan.unresolved)
        assertTrue(plan.separation.mask(0)[5 * w + 4])
        assertTrue(plan.separation.mask(1)[5 * w + 4], "a grey correction is necessary")
        assertTrue(plan.separation.mask(2)[5 * w + 6], "a separate white correction is necessary")
        val expected = IntArray(w * w) { pixel ->
            val x = pixel % w
            val y = pixel / w
            when {
                x in 3..5 && y in 3..7 -> grey.color
                x in 6..7 && y in 3..7 -> white.color
                x in 2..8 && y in 2..8 && (x == 2 || x == 8 || y == 2 || y == 8) -> black.color
                else -> 0
            }
        }
        assertContentEquals(expected, plan.separation.render())
    }

    @Test
    fun sourceDarkDetailCannotBePaintedWhiteToHideAnIsland() {
        val w = 9
        val base = BooleanArray(w * w)
        val whiteCut = BooleanArray(w * w)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) base[y * w + x] = true
        }
        for (y in 3..5) for (x in 3..5) {
            if (x != 3 || y != 3) whiteCut[y * w + x] = true
        }
        val source = RgbaImage(w, w, IntArray(w * w) { pixel ->
            if (pixel == 3 * w + 3) black.color else white.color
        })
        val original = Separation.fromMasks(w, w, listOf(black, white), listOf(base, whiteCut))
        val plan = IslandPlanner.repair(original, source)
        assertEquals(0, plan.sourceGuidedIslands)
        assertEquals(1, plan.unresolved)
        assertContentEquals(base, plan.separation.mask(0))
        assertContentEquals(whiteCut, plan.separation.mask(1))
    }

    @Test
    fun aCorrectiveMarkCannotCloseALaterCutAndCreateAnotherIsland() {
        val w = 13
        val base = BooleanArray(w * w)
        val greyCut = BooleanArray(w * w)
        val whiteCut = BooleanArray(w * w)
        for (y in 2..10) for (x in 2..10) {
            if (x == 2 || x == 10 || y == 2 || y == 10) base[y * w + x] = true
        }
        for (y in 3..9) for (x in 3..9) {
            val p = y * w + x
            if (x in 4..8 && y in 4..8 && (x == 4 || x == 8 || y == 4 || y == 8)) {
                if (x != 6 || y != 4) whiteCut[p] = true
            } else {
                greyCut[p] = true
            }
        }
        val source = RgbaImage(w, w, IntArray(w * w) { pixel ->
            val x = pixel % w
            val y = pixel / w
            if (x in 4..8 && y in 4..8 && (x == 4 || x == 8 || y == 4 || y == 8))
                white.color else grey.color
        })
        val original = Separation.fromMasks(w, w, listOf(black, grey, white), listOf(base, greyCut, whiteCut))
        val plan = IslandPlanner.repair(original, source)
        assertEquals(0, plan.sourceGuidedIslands, "white must not strand the stock in the middle of the ring")
        assertFalse(plan.separation.mask(2)[4 * w + 6], "the unsafe white mark stays absent")
        assertTrue(plan.unresolved >= 1, "the original black stock island must remain flagged")
        assertContentEquals(original.render(), plan.separation.render())
    }

    @Test
    fun equidistantLaterPaintsMustNotBeChosenByLayerOrder() {
        val w = 9
        val ring = BooleanArray(w * w)
        for (y in 2..6) for (x in 2..6) {
            if (x == 2 || x == 6 || y == 2 || y == 6) ring[y * w + x] = true
        }
        val light = Sheet(0xFFB0B0B0.toInt())
        val lighter = Sheet(0xFFD0D0D0.toInt())
        val image = RgbaImage(w, w, IntArray(w * w) { 0xFFC0C0C0.toInt() })
        for (order in listOf(listOf(black, light, lighter), listOf(black, lighter, light))) {
            val original = Separation.fromMasks(w, w, order,
                listOf(ring, BooleanArray(w * w), BooleanArray(w * w)))
            val plan = IslandPlanner.repair(original, image)
            assertEquals(0, plan.sourceGuidedIslands)
            assertEquals(0, plan.sourceGuidedAttempts)
            assertEquals(1, plan.unresolved)
            assertContentEquals(ring, plan.separation.mask(0))
            assertContentEquals(BooleanArray(w * w), plan.separation.mask(1))
            assertContentEquals(BooleanArray(w * w), plan.separation.mask(2))
        }
    }

    @Test
    fun laterLayerExistingIslandMustNotBeSplitByAWhiteCorrection() {
        val w = 11
        val blackCut = BooleanArray(w * w)
        val whiteCut = BooleanArray(w * w)
        for (y in 2..8) for (x in 2..8) {
            if (x in 4..6 && (x == 4 || x == 6 || y == 2 || y == 8))
                blackCut[y * w + x] = true
            if (x == 2 || x == 8 || y == 2 || y == 8)
                whiteCut[y * w + x] = true
        }
        val image = RgbaImage(w, w, IntArray(w * w) { white.color })
        val original = Separation.fromMasks(w, w, listOf(black, white), listOf(blackCut, whiteCut))
        val originalWhiteIslands = StencilPrintTips.detect(whiteCut, w, w, 110.0)
            .count { it.kind == PrintTipKind.ISLAND }
        assertEquals(1, originalWhiteIslands, "the white ring already surrounds a stock island")
        val plan = IslandPlanner.repair(original, image, minimumBridgePixels = 2)
        assertEquals(1, plan.sourceGuidedAttempts)
        assertEquals(0, plan.sourceGuidedIslands, "a correction must not split that existing stock")
        for (y in 3..7) assertFalse(plan.separation.mask(1)[y * w + 5])
        assertContentEquals(original.render(), plan.separation.render())
        val resultingWhiteIslands = StencilPrintTips.detect(plan.separation.mask(1), w, w, 110.0)
            .count { it.kind == PrintTipKind.ISLAND }
        assertEquals(1, resultingWhiteIslands)
    }

    @Test
    fun rejectedGeometryChecksAreLimitedToSixteenPerImage() {
        val count = 20
        val w = count * 12 + 1
        val h = 11
        val blackCut = BooleanArray(w * h)
        val whiteCut = BooleanArray(w * h)
        for (tile in 0 until count) {
            val offset = tile * 12
            for (y in 2..8) for (x in 2..8) {
                if (x in 4..6 && (x == 4 || x == 6 || y == 2 || y == 8))
                    blackCut[y * w + offset + x] = true
                if (x == 2 || x == 8 || y == 2 || y == 8)
                    whiteCut[y * w + offset + x] = true
            }
        }
        val original = Separation.fromMasks(w, h, listOf(black, white), listOf(blackCut, whiteCut))
        val image = RgbaImage(w, h, IntArray(w * h) { white.color })
        val plan = IslandPlanner.repair(original, image, minimumBridgePixels = 2)
        assertEquals(16, plan.sourceGuidedAttempts,
            "the hard limit must include rejected geometry validations, not merely accepted fixes")
        assertEquals(0, plan.sourceGuidedIslands)
        assertContentEquals(original.render(), plan.separation.render())
    }

    @Test
    fun aSlowTonalGradientIsLocatedAtTheActualEdgeWithTheOutwardDirection() {
        val w = 80
        val h = 40
        fun stencil() = Separation(w, h, listOf(black, grey),
            IntArray(w * h) { if (it % w <= 24) 1 else 0 })
        fun sourceAt(light: (Int) -> Int) = RgbaImage(w, h, IntArray(w * h) { i ->
            val value = light(i % w).coerceIn(0, 255)
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        })
        val gentle = sourceAt { (it - 15) * 12 }
        val zones = SoftGradientZones.locate(gentle, stencil(), cellSize = 32)
        assertTrue(zones.any { it.sheet == 1 && it.x == 24 && it.towards == LiftDirection.RIGHT })
        assertTrue(zones.all { it.x == 24 }, "gradient tips must be anchored to actual stencil cut edges")
        val step = sourceAt { if (it <= 24) 0 else 255 }
        assertTrue(SoftGradientZones.locate(step, stencil()).isEmpty(), "sharp boundary is not a soft gradient")
        val flat = sourceAt { 128 }
        assertTrue(SoftGradientZones.locate(flat, stencil()).isEmpty(), "flat paint is not a gradient")
    }

    @Test
    fun transparentBackgroundNeverTriggersAFalseLiftHint() {
        val w = 80
        val h = 40
        val source = RgbaImage(w, h, IntArray(w * h) { i ->
            val v = ((i % w - 15) * 12).coerceIn(0, 255)
            (v shl 16) or (v shl 8) or v
        })
        val stencil = Separation(w, h, listOf(black, grey),
            IntArray(w * h) { if (it % w <= 24) 1 else 0 })
        assertTrue(SoftGradientZones.locate(source, stencil).isEmpty())
    }
}
