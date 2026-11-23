package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OklabTest {

    @Test
    fun everyColourOfTheCubeSampledComesBackWithin1Over255() {
        var worst = 0
        val levels = (0..255 step 5).toList() + 255
        for (r in levels) for (g in levels) for (b in levels) {
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val lab = Oklab.fromArgb(argb)
            val back = Oklab.toArgb(lab)
            worst = maxOf(worst, abs((back shr 16 and 255) - r), abs((back shr 8 and 255) - g), abs((back and 255) - b))
        }
        assertTrue(worst <= 1, "round trip is off by $worst levels")
    }

    @Test
    fun everyGreyAndTheAnchorsAreWhereOklabPutsThem() {
        val white = Oklab.fromArgb(0xFFFFFFFF.toInt())
        assertEquals(1.0, white.l, 1e-4)
        assertEquals(0.0, white.a, 1e-4)
        assertEquals(0.0, white.b, 1e-4)
        assertEquals(0.0, Oklab.fromArgb(0xFF000000.toInt()).l, 1e-12)
        for (level in 0..255) {
            val grey = Oklab.fromArgb((0xFF shl 24) or (level shl 16) or (level shl 8) or level)
            assertEquals(0.0, grey.a, 1e-4)
            assertEquals(0.0, grey.b, 1e-4)
            assertEquals((0xFF shl 24) or (level shl 16) or (level shl 8) or level, Oklab.toArgb(grey))
        }
        // Reference values from Ottosson's published implementation.
        val red = Oklab.fromArgb(0xFFFF0000.toInt())
        assertEquals(0.62796, red.l, 1e-4)
        assertEquals(0.22486, red.a, 1e-4)
        assertEquals(0.12585, red.b, 1e-4)
    }

    @Test
    fun theCubeRootMatchesTheLibraryToRoundingError() {
        var x = 1e-9
        while (x < 4.0) {
            assertEquals(cbrt(x), Oklab.cbrt(x), cbrt(x) * 1e-13, "cbrt($x)")
            x *= 1.7
        }
        assertEquals(0.0, Oklab.cbrt(0.0))
    }

    @Test
    fun aColourOutsideTheGamutIsClamped() {
        val out = Oklab.toArgb(0.7, 0.4, 0.3)
        assertEquals(0xFF, out ushr 24)
    }
}
