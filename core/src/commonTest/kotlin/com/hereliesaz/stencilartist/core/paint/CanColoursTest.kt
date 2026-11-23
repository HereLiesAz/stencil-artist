package com.hereliesaz.stencilartist.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanColoursTest {
    @Test
    fun lightenAndDarkenMoveTowardsWhiteAndBlackAndStayOpaque() {
        assertEquals(0xFFFFFFFF.toInt(), CanColours.lighten(0xFF336699.toInt(), 1.0))
        assertEquals(0xFF000000.toInt(), CanColours.darken(0xFF336699.toInt(), 1.0))
        assertEquals(0xFF336699.toInt(), CanColours.lighten(0xFF336699.toInt(), 0.0))
        assertEquals(0xFF336699.toInt(), CanColours.darken(0x00336699, 0.0), "alpha is dropped: the result is always opaque")
        assertEquals(0xFF808080.toInt(), CanColours.lighten(0xFF000000.toInt(), 0.5))
        assertEquals(0xFFFFFFFF.toInt(), CanColours.lighten(0xFFFFFFFF.toInt(), 2.0), "the amount is clamped")
    }

    @Test
    fun luminanceMatchesTheKnownEndsAndPrimaries() {
        assertEquals(0.0, CanColours.luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, CanColours.luminance(0xFFFFFFFF.toInt()), 1e-6)
        assertEquals(0.2126, CanColours.luminance(0xFFFF0000.toInt()), 1e-4)
        assertEquals(0.7152, CanColours.luminance(0xFF00FF00.toInt()), 1e-4)
    }

    @Test
    fun blackIsChosenOnLightColoursAndWhiteOnDarkOnes() {
        assertTrue(CanColours.prefersBlack(0xFFFFFF00.toInt()), "yellow")
        assertTrue(CanColours.prefersBlack(0xFF9ED342.toInt()), "a light green")
        assertFalse(CanColours.prefersBlack(0xFF000000.toInt()))
        assertFalse(CanColours.prefersBlack(0xFF0B5D1E.toInt()), "a deep green")
        assertFalse(CanColours.prefersBlack(0xFF0000FF.toInt()), "blue")
    }
}
