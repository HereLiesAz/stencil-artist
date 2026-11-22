package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaliencyTest {

    /** 16-bit fixed point, as tools/golden/generate.py writes floats. */
    private fun String.unhexFloats(): FloatArray = FloatArray(length / 4) { substring(it * 4, it * 4 + 4).toInt(16) / 65535f }

    private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float, what: String) {
        assertEquals(expected.size, actual.size, what)
        var worst = 0f
        for (i in expected.indices) worst = maxOf(worst, abs(expected[i] - actual[i]))
        assertTrue(worst <= tolerance, "$what: off by up to $worst")
    }

    private val source get() = Golden.RESIZE_SRC.unhex().let { b -> FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f } }

    @Test
    fun shrinkingByAreaMatchesOpenCv() {
        val small = Resample.area(source, Golden.RESIZE_W, Golden.RESIZE_H, 20, 16)
        assertClose(Golden.AREA_20_16.unhexFloats(), small, 1e-4f, "INTER_AREA")
    }

    @Test
    fun enlargingBilinearlyMatchesOpenCv() {
        val small = Golden.AREA_20_16.unhexFloats()
        val big = Resample.bilinear(small, 20, 16, Golden.RESIZE_W, Golden.RESIZE_H)
        assertClose(Golden.LINEAR_61_47.unhexFloats(), big, 1e-4f, "INTER_LINEAR")
    }

    @Test
    fun resizingToTheSameSizeChangesNothing() {
        val plane = source
        assertClose(plane, Resample.area(plane, Golden.RESIZE_W, Golden.RESIZE_H, Golden.RESIZE_W, Golden.RESIZE_H), 1e-6f, "area")
        assertClose(plane, Resample.bilinear(plane, Golden.RESIZE_W, Golden.RESIZE_H, Golden.RESIZE_W, Golden.RESIZE_H), 1e-6f, "bilinear")
    }

    @Test
    fun theNetworkInputIsThreeNormalisedPlanesInRgbOrder() {
        val red = RgbaImage(4, 4, IntArray(16) { 0xFFFF0000.toInt() })
        val tensor = Saliency.input(red)
        val plane = Saliency.INPUT * Saliency.INPUT
        assertEquals(3 * plane, tensor.size)
        assertEquals((1f - 0.485f) / 0.229f, tensor[0], 1e-4f)
        assertEquals((0f - 0.456f) / 0.224f, tensor[plane], 1e-4f)
        assertEquals((0f - 0.406f) / 0.225f, tensor[2 * plane], 1e-4f)
    }

    private fun map(inside: (x: Int, y: Int) -> Boolean) =
        FloatArray(Saliency.INPUT * Saliency.INPUT) { if (inside(it % Saliency.INPUT, it / Saliency.INPUT)) 7f else -3f }

    @Test
    fun theMapIsStretchedToItsOwnRangeAndEnlarged() {
        val confidence = Saliency.confidence(map { x, _ -> x >= Saliency.INPUT / 2 }, 64, 32)
        assertEquals(0f, confidence[0], 1e-6f)
        assertEquals(1f, confidence[63], 1e-6f)
        assertTrue(confidence.all { it in 0f..1f })
    }

    @Test
    fun aBlankMapIsNoConfidenceAnywhere() {
        assertTrue(Saliency.confidence(FloatArray(Saliency.INPUT * Saliency.INPUT) { 2f }, 8, 8).all { it == 0f })
    }

    @Test
    fun cuttingOutKeepsColourAndRewritesAlphaWithAFeatheredEdge() {
        val image = RgbaImage(4, 1, IntArray(4) { 0xFF123456.toInt() })
        val cut = assertNotNull(Saliency.cutOut(image, floatArrayOf(0.9f, 0.45f, 0.4f, 0.1f)))
        assertEquals(listOf(255, 127, 0, 0), cut.pixels.map { it ushr 24 })
        assertTrue(cut.pixels.all { it and 0x00FFFFFF == 0x123456 })
    }

    @Test
    fun noSubjectIsNullNotAnEmptyImage() {
        val image = RgbaImage(100, 10, IntArray(1000) { 0xFF808080.toInt() })
        assertNull(Saliency.cutOut(image, FloatArray(1000) { if (it < 4) 0.9f else 0f }), "0.4% is not a subject")
        assertNotNull(Saliency.cutOut(image, FloatArray(1000) { if (it < 5) 0.9f else 0f }), "0.5% is")
    }
}
