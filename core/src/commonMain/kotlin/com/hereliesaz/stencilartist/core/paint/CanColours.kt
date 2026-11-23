package com.hereliesaz.stencilartist.core.paint

import kotlin.math.roundToInt

/**
 * Colour arithmetic for drawing a can from its one colour value: the lighter and darker tones of the
 * shading, and whether a mark on top of it should be black or white. Pure and deterministic, on opaque
 * ARGB ints, so the drawing code stays free of choices and the choices can be tested.
 */
object CanColours {
    /** [rgb] mixed [amount] (0..1) of the way to white, opaque. */
    fun lighten(rgb: Int, amount: Double): Int = mix(rgb, 255, amount)

    /** [rgb] mixed [amount] (0..1) of the way to black, opaque. */
    fun darken(rgb: Int, amount: Double): Int = mix(rgb, 0, amount)

    private fun mix(rgb: Int, target: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val c = (rgb shr shift) and 0xFF
            return (c + (target - c) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The relative luminance of [rgb] (0 black, 1 white), in linear light as WCAG defines it. */
    fun luminance(rgb: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else power((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    /**
     * Whether black reads better than white on [rgb]. The luminance 0.179 is where black and white
     * give equal contrast, so a check mark or number on a can's colour is legible either way.
     */
    fun prefersBlack(rgb: Int): Boolean = luminance(rgb) > 0.179

    /** `x` to the power [y] for positive [x], from exp and ln, as common Kotlin has `pow` on doubles only via kotlin.math. */
    private fun power(x: Double, y: Double): Double = kotlin.math.exp(y * kotlin.math.ln(x))
}
