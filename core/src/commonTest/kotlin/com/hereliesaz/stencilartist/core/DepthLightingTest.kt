package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DepthLightingTest {
    @Test
    fun redRgbBecomesImageNetNormalisedNchwTensor() {
        val image = RgbaImage(2, 2, IntArray(4) { 0xFFFF0000.toInt() })
        val tensor = DepthLighting.input(image)
        assertContentEquals(intArrayOf(1, 3, 518, 518), tensor.shape)
        val plane = 518 * 518
        assertEquals(2.2489f, tensor.values[0], 0.001f)
        assertEquals(-2.0357f, tensor.values[plane], 0.001f)
        assertEquals(-1.8044f, tensor.values[2 * plane], 0.001f)
    }

    @Test
    fun depthInputRetainsAspectAndViTPatchMultiples() {
        val image = RgbaImage(28, 14, IntArray(28 * 14) { 0xFF808080.toInt() })
        val input = DepthLighting.input(image)
        assertEquals(518, input.width)
        assertEquals(266, input.height)
        assertEquals(0, input.height % 14)
        assertEquals(0, input.width % 14)
        assertEquals(3 * 518 * 266, input.values.size)
    }

    @Test
    fun depthPredictionIsNormalisedAndEnlargedToSourceResolution() {
        val tensor = DepthLighting.TensorInput(FloatArray(3 * 14 * 14), 14, 14)
        val modelOutput = FloatArray(14 * 14) { (it % 14).toFloat() }
        val depth = DepthLighting.depth(modelOutput, tensor, 2, 1)
        assertEquals(2, depth.size)
        assertEquals(0.230769f, depth[0], 0.001f)
        assertEquals(0.769231f, depth[1], 0.001f)
    }

    @Test
    fun flatAndInvalidPredictionsNeverProduceArtificialRelief() {
        val tensor = DepthLighting.TensorInput(FloatArray(3 * 14 * 14), 14, 14)
        val flat = DepthLighting.depth(FloatArray(196) { 9f }, tensor, 2, 2)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), flat)
        assertFailsWith<IllegalArgumentException> {
            DepthLighting.depth(FloatArray(196) { if (it == 4) Float.NaN else 0f }, tensor, 2, 2)
        }
    }

    @Test
    fun depthRefinesOnlyAmbiguousSilhouetteEdges() {
        val saliency = floatArrayOf(.95f, .95f, .95f, .95f, .05f, .05f, .05f, .05f, .5f, .5f)
        val depth = floatArrayOf(.9f, .9f, .9f, .9f, .1f, .1f, .1f, .1f, .85f, .15f)
        val refined = DepthLighting.refineMask(saliency, depth)
        for (i in 0 until 8) assertEquals(saliency[i], refined[i])
        assertTrue(refined[8] > .7f, "Foreground-like uncertain pixel should be retained")
        assertTrue(refined[9] < .3f, "Background-like uncertain pixel should be removed")
    }

    @Test
    fun modelDepthOrientationIsLearnedFromTheMaskNotHardCoded() {
        val saliency = floatArrayOf(.95f, .95f, .95f, .95f, .05f, .05f, .05f, .05f, .5f, .5f)
        val inverted = floatArrayOf(.1f, .1f, .1f, .1f, .9f, .9f, .9f, .9f, .15f, .85f)
        val refined = DepthLighting.refineMask(saliency, inverted)
        assertTrue(refined[8] > .7f)
        assertTrue(refined[9] < .3f)
    }

    @Test
    fun weakOrMissingSeedEvidenceNeverChangesTheOriginalMask() {
        val saliency = floatArrayOf(.95f, .05f, .5f, .5f)
        assertContentEquals(saliency,
            DepthLighting.refineMask(saliency, floatArrayOf(.9f, .1f, .9f, .1f)))
        val larger = FloatArray(10) { if (it < 4) .95f else if (it < 8) .05f else .5f }
        assertContentEquals(larger, DepthLighting.refineMask(larger, FloatArray(10) { .5f }))
    }

    @Test
    fun relightingIsAppliedToSourceBeforeCutsAndNeverRewritesAlpha() {
        val colors = IntArray(25) { 0x801080F0.toInt() }
        colors[0] = 0x00123456
        val source = RgbaImage(5, 5, colors)
        val depth = FloatArray(25) { (it % 5) / 4f }
        val disabled = DepthLighting.relight(source, depth, LightingParams())
        assertContentEquals(source.pixels, disabled.pixels)
        val left = DepthLighting.relight(source, depth,
            LightingParams(enabled = true, azimuth = 0f, elevation = 0f,
                intensity = 1f, ambient = 0f, depthStrength = 10f))
        val right = DepthLighting.relight(source, depth,
            LightingParams(enabled = true, azimuth = 180f, elevation = 0f,
                intensity = 1f, ambient = 0f, depthStrength = 10f))
        assertTrue((right.pixels[12] and 255) > (left.pixels[12] and 255))
        assertContentEquals(source.pixels.map { it ushr 24 },
            right.pixels.map { it ushr 24 })
        assertEquals(colors[0], right.pixels[0])
    }
}
