package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic of a TONAL separation: splitting brightness into bands and deciding which end is
 * the base. Carried over from GraffitiXR's `StencilTones`, generalised from three tones to any
 * number. With three it gives GraffitiXR's answers exactly.
 */
object Tones {

    /**
     * The [count] - 1 thresholds splitting a 256-bin value histogram into [count] tones: 1-D K-means
     * started at the histogram's evenly spaced quantiles (for three tones: 1/6, 3/6, 5/6), so the
     * same image always gives the same split. A value belongs to tone `i` when it is above
     * threshold `i - 1` and at or below threshold `i`. Thresholds are strictly increasing.
     *
     * Null when the histogram is empty. [count] is limited only by the 256 levels there are to
     * split; that is a property of 8-bit brightness, not of a stencil.
     */
    fun thresholds(histogram: IntArray, count: Int = 3): IntArray? {
        require(histogram.size == 256) { "a value histogram has 256 bins" }
        require(count in 1..256) { "8-bit brightness splits into 1..256 tones, not $count" }
        val total = histogram.sum()
        if (total == 0) return null
        fun quantile(fraction: Double): Double {
            val target = fraction * total
            var seen = 0
            for (v in 0..255) {
                seen += histogram[v]
                if (seen >= target) return v.toDouble()
            }
            return 255.0
        }
        val centres = DoubleArray(count) { quantile((2 * it + 1).toDouble() / (2 * count)) }
        for (round in 0 until 50) {
            val sum = DoubleArray(count)
            val n = IntArray(count)
            for (v in 0..255) {
                val weight = histogram[v]
                if (weight == 0) continue
                var best = 0
                for (k in 1 until count) if (abs(v - centres[k]) < abs(v - centres[best])) best = k
                sum[best] += v.toDouble() * weight
                n[best] += weight
            }
            var moved = false
            for (k in 0 until count) {
                if (n[k] == 0) continue
                val next = sum[k] / n[k]
                if (abs(next - centres[k]) > 1e-6) moved = true
                centres[k] = next
            }
            if (!moved) break
        }
        centres.sort()
        // Boundaries sit halfway between neighbouring centres; clamped so the thresholds stay
        // ordered and each tone keeps at least one level.
        val out = IntArray(count - 1)
        for (i in out.indices) {
            val floor = if (i == 0) 0 else out[i - 1] + 1
            val ceiling = 255 - (out.size - 1 - i)
            out[i] = ((centres[i] + centres[i + 1]) / 2).toInt().coerceIn(floor, ceiling)
        }
        return out
    }

    /** Luminance of an RGB pixel in 0..1, the weights GraffitiXR's stencil tool used. */
    fun luma(r: Int, g: Int, b: Int): Double = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

    /** Brightness taken off before the contrast push, in luminance levels (0..255). */
    const val DARK_CALL_BRIGHTNESS = -40

    /** Contrast gain about mid-grey for the dark-or-light call. */
    const val DARK_CALL_CONTRAST = 2.0

    /**
     * The dark-or-light call, which picks the base sheet. Made on the isolated subject: its
     * luminance is turned down by [DARK_CALL_BRIGHTNESS] and the contrast turned up by
     * [DARK_CALL_CONTRAST] about mid-grey, which leaves each pixel clearly dark or clearly light,
     * and then the call goes to whichever has the greater AREA. A tie is dark.
     *
     * [lumaHistogram] is a 256-bin histogram of the subject's luminance scaled to 0..255, counting
     * only pixels that are not fully transparent.
     */
    fun isDarkByArea(lumaHistogram: IntArray): Boolean {
        require(lumaHistogram.size == 256) { "a luminance histogram has 256 bins" }
        var dark = 0L
        var light = 0L
        for (level in 0..255) {
            val n = lumaHistogram[level]
            if (n == 0) continue
            val adjusted = (level + DARK_CALL_BRIGHTNESS - 128) * DARK_CALL_CONTRAST + 128
            if (adjusted >= 128.0) light += n else dark += n
        }
        return dark >= light
    }

    /**
     * The default colours of a [count]-sheet tonal stencil, in paint order: an even grey ramp that
     * starts at black for a [dark] image and at white for a light one. Three sheets give black,
     * `#808080`, white. These are defaults only; a sheet's colour is the caller's to change.
     */
    fun ramp(count: Int, dark: Boolean): List<Sheet> {
        require(count >= 1) { "a stencil has at least one sheet" }
        return List(count) { i ->
            val step = if (dark) i else count - 1 - i
            val level = if (count == 1) (if (dark) 0 else 255) else (255.0 * step / (count - 1)).roundToInt()
            Sheet((0xFF shl 24) or (level shl 16) or (level shl 8) or level)
        }
    }
}
