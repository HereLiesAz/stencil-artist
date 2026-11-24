package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DepthEditsTest {
    private fun image(vararg alpha: Int) = RgbaImage(alpha.size, 1, IntArray(alpha.size) { (alpha[it] shl 24) or 0x808080 })
    private fun stroke(mode: DepthBrush, strength: Float = 1f, target: Float = .5f) =
        DepthStroke(listOf(MaskPoint(1.5f, .5f)), .5f, mode, strength, 1f, target)

    @Test
    fun raiseLowerAndSetRespectBoundsAndUndo() {
        val subject = image(255, 128, 255)
        val base = floatArrayOf(.2f, .4f, .6f)
        val raise = stroke(DepthBrush.RAISE, .25f)
        val lower = stroke(DepthBrush.LOWER)
        val set = stroke(DepthBrush.SET, target = .75f)
        assertContentEquals(floatArrayOf(.2f, .65f, .6f), DepthEdits(listOf(raise)).apply(base, subject))
        assertContentEquals(floatArrayOf(.2f, 0f, .6f), DepthEdits(listOf(raise, lower)).apply(base, subject))
        assertContentEquals(floatArrayOf(.2f, .75f, .6f), DepthEdits(listOf(set)).apply(base, subject))
        assertContentEquals(floatArrayOf(.2f, .4f, .6f), DepthEdits().apply(base, subject))
        assertContentEquals(floatArrayOf(.2f, .4f, .6f), base)
    }

    @Test
    fun brushIsContinuousAndIndependentOfPointerSampleCount() {
        val subject = image(255, 255, 0, 255, 255)
        val base = FloatArray(5) { .25f }
        val a = MaskPoint(.5f, .5f); val b = MaskPoint(4.5f, .5f)
        val sparse = DepthStroke(listOf(a, b), .5f, DepthBrush.RAISE, .25f, 1f)
        val dense = sparse.copy(points = listOf(a, MaskPoint(1.5f, .5f), MaskPoint(2.5f, .5f), b, b))
        val expected = floatArrayOf(.5f, .5f, .25f, .5f, .5f)
        assertContentEquals(expected, DepthEdits(listOf(sparse)).apply(base, subject))
        assertContentEquals(expected, DepthEdits(listOf(dense)).apply(base, subject))
    }

    @Test
    fun softBrushFalloffIsLocal() {
        val subject = image(255, 255, 255, 255, 255)
        val stroke = DepthStroke(listOf(MaskPoint(2.5f, .5f)), 2f, DepthBrush.RAISE, .5f, 0f)
        assertContentEquals(floatArrayOf(0f, .25f, .5f, .25f, 0f), DepthEdits(listOf(stroke)).apply(FloatArray(5), subject))
    }

    @Test
    fun smoothingUsesSnapshotAndPreservesEdgesAndTransparentGaps() {
        val subject = image(255, 255, 255, 0, 255)
        val base = floatArrayOf(.25f, .5f, 1f, 0f, .5f)
        val global = DepthEdits(smoothingPasses = 1, edgeThreshold = .3f).apply(base, subject)
        assertContentEquals(floatArrayOf(.375f, .375f, 1f, 0f, .5f), global)
        val local = DepthEdits(listOf(stroke(DepthBrush.SMOOTH)), edgeThreshold = .3f).apply(base, subject)
        assertContentEquals(floatArrayOf(.25f, .375f, 1f, 0f, .5f), local)
    }

    @Test
    fun flattenUsesCoveredForegroundMeanAndGlobalTarget() {
        val subject = image(255, 255, 0, 255)
        val base = floatArrayOf(0f, .5f, 1f, 1f)
        val flat = DepthStroke(listOf(MaskPoint(.5f, .5f), MaskPoint(1.5f, .5f)), .5f, DepthBrush.FLATTEN, 1f, 1f)
        assertContentEquals(floatArrayOf(.25f, .25f, 1f, 1f), DepthEdits(listOf(flat)).apply(base, subject))
        assertContentEquals(floatArrayOf(.25f, .25f, 1f, .25f), DepthEdits(flatten = 1f, flattenTarget = .25f).apply(base, subject))
    }

    @Test
    fun clampAndGammaRemapKnownValuesAndPreviewPreservesAlpha() {
        val subject = image(255, 128, 255, 0, 255)
        val base = floatArrayOf(0f, .5f, .75f, .3f, 1f)
        val edits = DepthEdits(low = .25f, high = .75f, gamma = 2f)
        assertContentEquals(floatArrayOf(0f, .25f, 1f, .3f, 1f), edits.apply(base, subject))
        assertContentEquals(intArrayOf(0xFF000000.toInt(), 0x80404040.toInt(), 0xFFFFFFFF.toInt(), 0, 0xFFFFFFFF.toInt()),
            edits.preview(base, subject).pixels)
        assertEquals(0x80808080.toInt(), subject.pixels[1])
        assertEquals(128, edits.preview(base, subject, true).pixels[1] ushr 24)
        assertFailsWith<IllegalArgumentException> { DepthEdits(low = .8f, high = .7f) }
    }

    @Test
    fun editedDepthChangesBothLightingModesWithoutChangingAlpha() {
        val subject = RgbaImage(8, 4, IntArray(32) { 0xFFAAAAAA.toInt() })
        val base = FloatArray(32) { (it % 8) / 7f }
        val edited = DepthEdits(flatten = 1f).apply(base, subject)
        val light = LightingParams(enabled = true, azimuth = 0f, elevation = 35f, ambient = .1f, intensity = 1f)
        val flatBefore = DepthLighting.relight(subject, base, light)
        val flatAfter = DepthLighting.relight(subject, edited, light)
        val sceneBefore = ReliefScene.prepare(subject, base, light).renderSource(light)
        val sceneAfter = ReliefScene.prepare(subject, edited, light).renderSource(light)
        assertTrue(!flatBefore.pixels.contentEquals(flatAfter.pixels))
        assertTrue(!sceneBefore.pixels.contentEquals(sceneAfter.pixels))
        assertTrue(flatAfter.pixels.all { it ushr 24 == 255 })
        assertTrue(sceneAfter.pixels.all { it ushr 24 == 255 })
    }
}
