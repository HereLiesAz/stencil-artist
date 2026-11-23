package com.hereliesaz.stencilartist.core

/**
 * Six-digit sRGB editing for a manually selected stencil palette colour. Alpha is always opaque:
 * the transparency of a stencil belongs to its mask, not to its paint. The resulting value is
 * consumed through [ColorParams.colors], and the existing [ColorParams] validation rejects
 * palettes containing two sheets with the same RGB value.
 */
object PaletteColor {
    /** Format [argb] as a six-digit upper-case #RRGGBB string, ignoring the input alpha. */
    fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    /** Parse an optional #-prefixed six-digit RGB hex string; null means the draft is incomplete/invalid. */
    fun parseHex(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return (0xFF shl 24) or digits.toInt(16)
    }

    /**
     * Replace the 8-bit [shift] channel of [argb] with [value], retaining the other two channels.
     * [shift] is 16 (red), 8 (green), or 0 (blue). The result is always opaque ARGB.
     */
    fun withChannel(argb: Int, shift: Int, value: Int): Int {
        require(shift == 16 || shift == 8 || shift == 0) { "only red, green, and blue are editable" }
        require(value in 0..255) { "RGB channel is 0..255, not $value" }
        return (0xFF shl 24) or ((argb and 0xFFFFFF) and (0xFF shl shift).inv()) or (value shl shift)
    }
}
