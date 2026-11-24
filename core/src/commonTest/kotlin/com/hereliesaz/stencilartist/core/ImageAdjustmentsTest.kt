package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ImageAdjustmentsTest {
    @Test
    fun defaultIsIdentityAndInvertPreservesHiddenRgb() {
        val original = intArrayOf(0x00123456, 0x80808080.toInt(), 0xFFC0C0C0.toInt())
        val image = RgbaImage(3, 1, original.copyOf())
        assertSame(image, ImageAdjustments().apply(image))
        assertContentEquals(intArrayOf(0x00123456, 0x807F7F7F.toInt(), 0xFF3F3F3F.toInt()),
            ImageAdjustments(invert = true).apply(image).pixels)
        assertContentEquals(original, image.pixels)
    }

    @Test
    fun autoLevelsIgnoresTransparentPixelsAndMapsVisibleEndpoints() {
        val image = RgbaImage(3, 1, intArrayOf(0x00123456, 0x80808080.toInt(), 0xFFC0C0C0.toInt()))
        assertContentEquals(intArrayOf(0x00123456, 0x80000000.toInt(), 0xFFFFFFFF.toInt()),
            ImageAdjustments(autoLevels = true).apply(image).pixels)
    }

    @Test
    fun blurCannotImportHiddenGreenIntoRedSubject() {
        val image = RgbaImage(3, 1, intArrayOf(0x0000FF00, 0x80FF0000.toInt(), 0xFFFF0000.toInt()))
        assertContentEquals(image.pixels, ImageAdjustments(blur = 2).apply(image).pixels)
    }

    @Test
    fun despeckleRemovesIsolatedBrightPixel() {
        val image = RgbaImage(3, 3, IntArray(9) { if (it == 4) 0xFFFFFFFF.toInt() else 0xFF444444.toInt() })
        assertEquals(0xFF444444.toInt(), ImageAdjustments(despeckle = true).apply(image).pixels[4])
    }

    @Test
    fun edgeSmoothAveragesSimilarValuesButKeepsStrongBoundary() {
        val image = RgbaImage(3, 1, intArrayOf(0xFF404040.toInt(), 0xFF444444.toInt(), 0xFFE0E0E0.toInt()))
        assertContentEquals(intArrayOf(0xFF424242.toInt(), 0xFF424242.toInt(), 0xFFE0E0E0.toInt()),
            ImageAdjustments(edgeSmooth = 8).apply(image).pixels)
    }

    @Test
    fun valuePosterizationPreservesChannelRatio() {
        val image = RgbaImage(1, 1, intArrayOf(0x80643200.toInt()))
        assertEquals(0x80804000.toInt(), ImageAdjustments(posterize = 3).apply(image).pixels.single())
    }

    @Test
    fun combinedEffectsKeepDimensionsAlphaAndOriginal() {
        val original = intArrayOf(0x00123456, 0x80808080.toInt(), 0xFFC0C0C0.toInt())
        val image = RgbaImage(3, 1, original.copyOf())
        val result = ImageAdjustments(exposure = 1f, brightness = .2f, contrast = .4f, gamma = .8f,
            saturation = 0f, despeckle = true, edgeSmooth = 16, blur = 1, sharpen = 1f,
            clarity = .5f, posterize = 4).apply(image)
        assertEquals(3, result.width)
        assertEquals(1, result.height)
        assertEquals(original.map { it ushr 24 }, result.pixels.map { it ushr 24 })
        assertContentEquals(original, image.pixels)
    }
}
