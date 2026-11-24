package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReliefSceneTest {
    private val frontal = LightingParams(enabled = true, elevation = 90f, ambient = 0f, intensity = 1f)

    @Test
    fun flatSurfaceRetainsExactPixelsAndFraming() {
        val source = RgbaImage(3, 2, intArrayOf(0xFF808080.toInt(), 0x80123456.toInt(), 0x00123456,
            0xFF222222.toInt(), 0, 0xFFBBBBBB.toInt()))
        val result = ReliefScene.prepare(source, FloatArray(6), frontal).renderSource(frontal)
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        assertContentEquals(source.pixels, result.pixels)
    }

    @Test
    fun raisedSolidCastsShadowAcrossGapAndLightReversalRemovesIt() {
        // A flat receiver on the left and a separate raised solid on the right.
        val source = RgbaImage(12, 4, IntArray(48) { if (it % 12 in 6..7 || it % 12 == 11) 0 else 0xFFAAAAAA.toInt() })
        val depth = FloatArray(48) { if (it % 12 in 8..10) 1f else 0f }
        val light = frontal.copy(azimuth = 0f, elevation = 25f, depthStrength = 8f, ambient = .1f)
        val scene = ReliefScene.prepare(source, depth, light)
        val shadow = scene.renderSource(light)
        val lit = scene.renderSource(light.copy(castShadows = false))
        val reverse = scene.renderSource(light.copy(azimuth = 180f))
        // The receiver's pixel is flat and faces the same elevation in both directions.
        assertEquals(17, shadow.pixels[15] and 255) // ambient only, 170 * .1
        assertEquals(89, lit.pixels[15] and 255)
        assertEquals(lit.pixels[15], reverse.pixels[15])
        assertEquals(0, shadow.pixels[18]) // no opaque shadow on the transparent gap
    }

    @Test
    fun orbitShowsWallsWithoutMovingTheStencilCamera() {
        val source = RgbaImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt()))
        val light = frontal.copy(ambient = 1f, intensity = 0f)
        val scene = ReliefScene.prepare(source, floatArrayOf(.2f, .8f), light)
        val before = scene.renderSource(light)
        val orbit = scene.renderOrbit(light, 65f, 20f)
        assertTrue(orbit.pixels.any { it == 0xFF909090.toInt() }, "Inferred wall/back material should be visible")
        assertTrue(orbit.pixels.any { it == 0xFFFF0000.toInt() }, "Source texture should be visible")
        assertContentEquals(before.pixels, scene.renderSource(light).pixels)
    }

    @Test
    fun coarseGeometryNeverErasesThinNonSquareSourceDetails() {
        val source = RgbaImage(301, 73, IntArray(301 * 73) { if (it % 111 == 0) 0x80FFFFFF.toInt() else 0x00123456 })
        val result = ReliefScene.prepare(source, FloatArray(source.pixels.size), frontal).renderSource(frontal)
        assertContentEquals(source.pixels, result.pixels)
    }

    @Test
    fun emptyMaskDoesNotInventGeometryOrAnOpaqueBackground() {
        val source = RgbaImage(1, 1, intArrayOf(0x00123456))
        val scene = ReliefScene.prepare(source, floatArrayOf(1f), frontal)
        assertContentEquals(source.pixels, scene.renderSource(frontal).pixels)
        assertTrue(scene.renderOrbit(frontal, 45f, 30f).pixels.all { it == 0 })
    }
}
