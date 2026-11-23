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
        val result = IslandPlanner.repair(source, minimumBridgePixels = 1)
        assertEquals(1, result.concealedBridges)
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
