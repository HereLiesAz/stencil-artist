package com.hereliesaz.stencilartist.core

/** A colour in Oklab: lightness [l] (0 black, 1 white) and the two opponent axes [a] (green to red) and [b] (blue to yellow). */
class OklabColor(val l: Double, val a: Double, val b: Double)

/**
 * Conversion between sRGB and Oklab (Bjorn Ottosson, 2020), the space a colour separation measures
 * distance in. Oklab was chosen over CIE Lab because its distances track what the eye sees about as
 * well, it needs only a cube root where CIE Lab needs a piecewise one, and it keeps hue steady across
 * lightness, so a dark red is not pulled towards purple when clusters are averaged.
 *
 * Every function here uses only addition, multiplication, division and comparison, never `pow`,
 * `cbrt` or `exp`. Those library functions may round differently on the JVM and in the browser, and
 * a colour separation must give the same sheets on both. IEEE arithmetic is the same everywhere.
 */
object Oklab {

    /** Linear light of each 8-bit sRGB level. */
    private val linear = DoubleArray(256) { level ->
        val c = level / 255.0
        if (c <= 0.04045) c / 12.92 else power24((c + 0.055) / 1.055)
    }

    /** `v` to the power 2.4 as the twelfth power of its fifth root, the fifth root by Newton's method. */
    private fun power24(v: Double): Double {
        var y = 1.0
        repeat(60) { y = (4.0 * y + v / (y * y * y * y)) / 5.0 }
        val y2 = y * y
        val y4 = y2 * y2
        return y4 * y4 * y4
    }

    /** The cube root of [x] for x >= 0, by scaling into [1/8, 1] and four Newton steps from a chord. */
    internal fun cbrt(x: Double): Double {
        if (x <= 0.0) return 0.0
        var v = x
        var scale = 1.0
        while (v < 0.125) { v *= 8.0; scale *= 0.5 }
        while (v > 1.0) { v *= 0.125; scale *= 2.0 }
        var y = 0.5 + (v - 0.125) * (0.5 / 0.875)
        repeat(5) { y = (2.0 * y + v / (y * y)) / 3.0 }
        return y * scale
    }

    /** Oklab of the 8-bit sRGB colour [r], [g], [b] written to `out[0..2]` as l, a, b. */
    internal fun fromRgb(r: Int, g: Int, b: Int, out: DoubleArray) {
        val lr = linear[r]
        val lg = linear[g]
        val lb = linear[b]
        val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    }

    /** Oklab of the colour part of [argb]; alpha is ignored. */
    fun fromArgb(argb: Int): OklabColor {
        val out = DoubleArray(3)
        fromRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, out)
        return OklabColor(out[0], out[1], out[2])
    }

    /**
     * The opaque sRGB colour nearest to the Oklab colour ([l], [a], [b]), as an ARGB int. A colour
     * outside the sRGB gamut is clamped to it. The nearest level is found in linear light, by search
     * over the table the forward conversion uses, so the two directions agree exactly.
     */
    fun toArgb(l: Double, a: Double, b: Double): Int {
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        val r = level(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3)
        val g = level(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3)
        val bl = level(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** [toArgb] of an [OklabColor]. */
    fun toArgb(color: OklabColor): Int = toArgb(color.l, color.a, color.b)

    /** The 8-bit level whose linear light is closest to [light]. */
    private fun level(light: Double): Int {
        if (light <= linear[0]) return 0
        if (light >= linear[255]) return 255
        var lo = 0
        var hi = 255
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (linear[mid] <= light) lo = mid else hi = mid
        }
        return if (light - linear[lo] <= linear[hi] - light) lo else hi
    }
}
