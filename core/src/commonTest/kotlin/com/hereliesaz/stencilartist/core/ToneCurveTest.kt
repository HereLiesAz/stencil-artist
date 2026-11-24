package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToneCurveTest {
    @Test
    fun identityMapsEveryInputExactly() {
        assertContentEquals(IntArray(256) { it }, ToneCurve().lookup())
    }

    @Test
    fun curveHonoursKnotsAndFlatBandsWithoutOvershoot() {
        val lut = ToneCurve(16, 32, 32, 224, 240).lookup()
        assertEquals(16, lut[0]); assertEquals(32, lut[64]); assertEquals(32, lut[128])
        assertEquals(224, lut[192]); assertEquals(240, lut[255])
        assertTrue((64..128).all { lut[it] == 32 })
        assertTrue((1..255).all { lut[it] >= lut[it - 1] })
        assertTrue(lut[160] in 33..223)
    }

    @Test
    fun linearCurveHasKnownIntermediateValues() {
        val lut = ToneCurve(0, 32, 64, 96, 128).lookup()
        assertEquals(16, lut[32]); assertEquals(48, lut[96]); assertEquals(80, lut[160])
    }

    @Test
    fun editingClampsToNeighboursAndDoesNotMutateOriginal() {
        val original = ToneCurve()
        assertEquals(ToneCurve(midtone = 192), original.withOutput(2, 255))
        assertEquals(ToneCurve(shadow = 0), original.withOutput(1, -20))
        assertEquals(ToneCurve(black = 64), original.withOutput(0, 100))
        assertEquals(ToneCurve(white = 192), original.withOutput(4, 0))
        assertEquals(ToneCurve(), original)
        assertFailsWith<IllegalArgumentException> { ToneCurve(shadow = 180) }
        assertFailsWith<IllegalArgumentException> { original.withOutput(5, 0) }
    }

    @Test
    fun extremeOrderedKnotsProduceMonotoneTransfer() {
        val levels = listOf(0, 1, 64, 128, 192, 254, 255)
        for (a in levels) for (b in levels.filter { it >= a }) for (c in levels.filter { it >= b })
            for (d in levels.filter { it >= c }) for (e in levels.filter { it >= d }) {
                val lut = ToneCurve(a, b, c, d, e).lookup()
                assertTrue((1..255).all { lut[it] >= lut[it - 1] })
                assertEquals(a, lut.first()); assertEquals(e, lut.last())
            }
    }

    @Test
    fun curveRemapsValuePreservingHueAlphaAndOriginalPixels() {
        val original = intArrayOf(0x00123456, 0x80000000.toInt(), 0xFF804020.toInt(), 0xFFFFFFFF.toInt())
        val image = RgbaImage(4, 1, original.copyOf())
        val result = ImageAdjustments(curve = ToneCurve(16, 64, 192, 224, 240)).apply(image)
        assertContentEquals(intArrayOf(0x00123456, 0x80101010.toInt(), 0xFFC06030.toInt(), 0xFFF0F0F0.toInt()), result.pixels)
        assertContentEquals(original, image.pixels)
        assertEquals(4, result.width); assertEquals(1, result.height)
    }
}
