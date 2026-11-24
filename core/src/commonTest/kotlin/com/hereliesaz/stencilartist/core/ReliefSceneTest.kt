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
    @Test
    fun independentColoredLightsAddAndPointLightFallsOff() {
        val source = RgbaImage(1, 1, intArrayOf(0x80FFFFFF.toInt()))
        val point = SceneLight(kind = SceneLightKind.POINT, elevation = 90f, distance = 2f,
            intensity = 2f, color = 0xFF0000)
        val blue = SceneLight(elevation = 90f, intensity = .25f, color = 0x0000FF)
        val light = frontal.copy(lights = listOf(point, blue))
        val scene = ReliefScene.prepare(source, floatArrayOf(0f), light)
        assertEquals(0x80800040.toInt(), scene.renderSource(light).pixels.single())
        assertEquals(0x80200040.toInt(), scene.renderSource(light.copy(lights = listOf(point.copy(distance = 4f), blue))).pixels.single())
        assertEquals(0x80000000.toInt(), scene.renderSource(light.copy(lights = emptyList())).pixels.single())
    }

    @Test
    fun spotAimAndConeExcludeAnOffAxisReceiver() {
        val source = RgbaImage(1, 1, intArrayOf(0xFFFFFFFF.toInt()))
        val spot = SceneLight(kind = SceneLightKind.SPOT, elevation = 90f, distance = 2f, intensity = 4f, cone = 5f)
        val light = frontal.copy(lights = listOf(spot))
        val scene = ReliefScene.prepare(source, floatArrayOf(0f), light)
        assertEquals(0xFFFFFFFF.toInt(), scene.renderSource(light).pixels.single())
        assertEquals(0xFF000000.toInt(), scene.renderSource(light.copy(lights = listOf(spot.copy(targetX = .5f)))).pixels.single())
    }

    @Test
    fun finitePositionCastsGeometricShadowsAndPerLightToggleWorks() {
        val source = RgbaImage(12, 4, IntArray(48) { if (it % 12 in 6..7 || it % 12 == 11) 0 else 0xFFAAAAAA.toInt() })
        val depth = FloatArray(48) { if (it % 12 in 8..10) 1f else 0f }
        val point = SceneLight(kind = SceneLightKind.POINT, azimuth = 0f, elevation = 25f, distance = 4f, intensity = 4f)
        val light = frontal.copy(depthStrength = 8f, ambient = .1f, lights = listOf(point))
        val scene = ReliefScene.prepare(source, depth, light)
        val shadow = scene.renderSource(light)
        val lit = scene.renderSource(light.copy(lights = listOf(point.copy(castsShadows = false))))
        assertEquals(17, shadow.pixels[15] and 255)
        assertTrue((lit.pixels[15] and 255) > 17)
        assertEquals(0, shadow.pixels[18])
        val spot = point.copy(kind = SceneLightKind.SPOT, cone = 60f)
        assertEquals(17, scene.renderSource(light.copy(lights = listOf(spot))).pixels[15] and 255)
        assertTrue((scene.renderSource(light.copy(lights = listOf(spot.copy(castsShadows = false)))).pixels[15] and 255) > 17)
    }

    @Test
    fun softnessCreatesPartialVisibilityAtGeometricShadowEdge() {
        val w = 128; val h = 64
        val source = RgbaImage(w, h, IntArray(w * h) { if (it % w in 75..83) 0 else 0xFFFFFFFF.toInt() })
        val depth = FloatArray(w * h) { if (it % w in 84..95 && it / w in 20..43) .8f else 0f }
        val key = SceneLight(azimuth = 0f, elevation = 45f, intensity = 1f)
        val light = frontal.copy(lights = listOf(key), depthStrength = 6f)
        val scene = ReliefScene.prepare(source, depth, light)
        val hard = scene.renderSource(light)
        val soft = scene.renderSource(light.copy(lights = listOf(key.copy(softness = 4))))
        val unshadowed = scene.renderSource(light.copy(castShadows = false))
        assertTrue(soft.pixels.indices.any { i -> i % w < 60 && (hard.pixels[i] and 255) == 0 &&
            (soft.pixels[i] and 255) in 1 until (unshadowed.pixels[i] and 255) })
        assertContentEquals(hard.pixels, scene.renderSource(light).pixels)
        val diagnostic = scene.renderOrbit(light, 0f, 0f, shadowOnly = true)
        assertTrue(diagnostic.pixels.any { it == 0xFF000000.toInt() })
        assertTrue(diagnostic.pixels.any { it == 0xFFFFFFFF.toInt() })
        assertContentEquals(hard.pixels, scene.renderSource(light).pixels)
    }
    @Test
    fun solidMaterialReplacesPhotographicRgbButPreservesAlphaAndSource() {
        val original = intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt(), 0x00123456)
        val source = RgbaImage(3, 1, original.copyOf())
        val light = frontal.copy(useSourceTexture = false, surfaceColor = 0x4080C0)
        val scene = ReliefScene.prepare(source, FloatArray(3), light)
        assertContentEquals(intArrayOf(0xFF4080C0.toInt(), 0x804080C0.toInt(), 0x00123456),
            scene.renderSource(light).pixels)
        assertContentEquals(original, scene.renderSource(light.copy(useSourceTexture = true)).pixels)
        assertContentEquals(original, source.pixels)
        val dim = scene.renderSource(light.copy(intensity = .5f))
        assertEquals(0xFF204060.toInt(), dim.pixels[0])
    }

    @Test
    fun solidMaterialCoversInferredWallsAndBackAndKeepsDiagnosticIndependent() {
        val source = RgbaImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val light = frontal.copy(useSourceTexture = false, surfaceColor = 0x00CC80,
            ambient = 1f, intensity = 0f)
        val scene = ReliefScene.prepare(source, floatArrayOf(.2f, .8f), light)
        val solid = scene.renderOrbit(light, 135f, 20f)
        val textured = scene.renderOrbit(light.copy(useSourceTexture = true), 135f, 20f)
        assertTrue(solid.pixels.any { it == 0xFF00CC80.toInt() })
        assertTrue(solid.pixels.all { it == 0 || it == 0xFF00CC80.toInt() })
        assertTrue(textured.pixels.any { it == 0xFF909090.toInt() })
        assertContentEquals(scene.renderOrbit(light, 45f, 20f, shadowOnly = true).pixels,
            scene.renderOrbit(light.copy(useSourceTexture = true), 45f, 20f, shadowOnly = true).pixels)
    }

    @Test
    fun receiverIsOptInAndCompositesPartialAlpha() {
        val source = RgbaImage(3, 1, intArrayOf(0, 0x80FF0000.toInt(), 0))
        val light = LightingParams(enabled = true, ambient = 1f, intensity = 0f, receiverColor = 0x0000FF)
        val scene = ReliefScene.prepare(source, FloatArray(3), light)
        assertContentEquals(source.pixels, scene.renderSource(light).pixels)
        val result = scene.renderSource(light.copy(receiverEnabled = true))
        assertContentEquals(intArrayOf(0xFF0000FF.toInt(), 0xFF80007F.toInt(), 0xFF0000FF.toInt()), result.pixels)
        assertContentEquals(source.pixels, scene.renderSource(light).pixels)
    }

    @Test
    fun raisedSolidCastsShadowOntoOptionalBackdrop() {
        val w = 32; val h = 16
        val source = RgbaImage(w, h, IntArray(w * h) { i ->
            if (i % w in 20..23 && i / w in 4..11) 0xFFFFFFFF.toInt() else 0
        })
        val light = LightingParams(enabled = true, azimuth = 0f, elevation = 45f, depthStrength = 3f,
            ambient = .1f, intensity = 1f, receiverEnabled = true)
        val scene = ReliefScene.prepare(source, FloatArray(w * h) { 1f }, light)
        val shadow = scene.renderSource(light)
        val clear = scene.renderSource(light.copy(castShadows = false))
        val reverse = scene.renderSource(light.copy(azimuth = 180f))
        // At 45 degrees, a raised object blocks light coming from the right on the left of its footprint.
        val receiverPixel = 8 * w + 16
        assertEquals(26, shadow.pixels[receiverPixel] and 255)
        assertEquals(206, clear.pixels[receiverPixel] and 255)
        assertEquals(clear.pixels[receiverPixel], reverse.pixels[receiverPixel])
        assertContentEquals(shadow.pixels, scene.renderSource(light).pixels)
        assertTrue(scene.renderOrbit(light, 35f, 20f).pixels.any { it ushr 24 != 0 })
    }
}
