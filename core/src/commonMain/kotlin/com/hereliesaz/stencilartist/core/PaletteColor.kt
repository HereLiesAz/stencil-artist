package com.hereliesaz.stencilartist.core

/**
 * Six-digit sRGB editing for a manually selected stencil palette colour. Alpha is always opaque:
 * the transparency of a stencil belongs to its mask, not to its paint. The resulting value is
 * consumed through [ColorParams.colors], and the existing [ColorParams] validation rejects
 * palettes containing two sheets with the same RGB value.
 */
object PaletteColor {
    /**
     * Fit a palette to a requested layer count without discarding existing distinct colours.
     * Preserve their order and make them opaque, then fill vacant slots with distinct editable
     * starter colours. The starter values are suggestions, never automatic image analysis.
     * A count decrease retains the first slots; an increase retains every existing slot.
     */
    fun resize(colors: List<Int>, count: Int): List<Int> {
        require(count in 1..ColorParams.MAX_SHEETS)
        val result = ArrayList<Int>(count)
        val used = HashSet<Int>()
        fun add(rgb: Int) {
            val opaque = rgb or 0xFF000000.toInt()
            if (result.size < count && used.add(opaque)) result.add(opaque)
        }
        for (color in colors) add(color)
        for (color in listOf(0x223344, 0xD34A32, 0xE9B949, 0x349E9C, 0x7352A1, 0xF1DCC3)) add(color)
        // An odd step traverses distinct 24-bit RGB values. This is only a fallback for
        // larger palettes; it cannot repeat before all possible RGB values have been visited.
        var rgb = 0
        while (result.size < count) {
            rgb = (rgb + 0x9E3779) and 0xFFFFFF
            add(rgb)
        }
        return result.toList()
    }

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
