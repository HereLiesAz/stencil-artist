package com.hereliesaz.stencilartist.core.export

import kotlin.math.abs
import kotlin.math.floor

/**
 * Number text for the file formats. Kotlin's common library has no String.format, and Double.toString
 * differs between the JVM and Wasm (and may use exponent notation), so this is written by hand: fixed
 * decimals, no exponent, no locale, trailing zeros trimmed, identical on every target.
 */
internal object Decimal {
    private val POW10 = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000)

    /**
     * [value] rounded half away from zero to [decimals] places and written without trailing zeros
     * ("2.5", "3", "-0.125"). Negative zero and values that round to zero are written "0". With
     * [pointAlways] a whole number keeps one decimal ("3.0"), which DXF readers expect of real numbers.
     */
    fun format(value: Double, decimals: Int, pointAlways: Boolean = false): String {
        require(value.isFinite()) { "cannot write a non-finite number: $value" }
        require(decimals in 0..9) { "decimals must be 0..9, got $decimals" }
        val scale = POW10[decimals]
        val scaled = floor(abs(value) * scale + 0.5)
        require(scaled < 9.0e15) { "number too large to write: $value" }
        val whole = scaled.toLong()
        val sb = StringBuilder()
        if (value < 0 && whole != 0L) sb.append('-')
        sb.append(whole / scale)
        val fraction = whole % scale
        if (fraction != 0L) {
            sb.append('.').append(fraction.toString().padStart(decimals, '0').trimEnd('0'))
        } else if (pointAlways) {
            sb.append(".0")
        }
        return sb.toString()
    }

    /** Two lowercase hex digits for a byte value 0..255. */
    fun hex2(v: Int): String {
        val digits = "0123456789abcdef"
        return "${digits[(v shr 4) and 15]}${digits[v and 15]}"
    }

    /** "#rrggbb" for the colour part of an ARGB int. */
    fun rgbHex(argb: Int): String = "#" + hex2(argb shr 16 and 255) + hex2(argb shr 8 and 255) + hex2(argb and 255)
}
