package com.hereliesaz.stencilartist.core

import kotlin.math.roundToInt

/** Five fixed input knots with editable output values. Monotone cubic interpolation preserves
 * tone ordering, including deliberate flat bands, without overshoot or spurious extrema. */
data class ToneCurve(
    val black: Int = 0,
    val shadow: Int = 64,
    val midtone: Int = 128,
    val highlight: Int = 192,
    val white: Int = 255,
) {
    init {
        require(black in 0..255 && black <= shadow && shadow <= midtone && midtone <= highlight && highlight <= white && white <= 255)
    }

    /** Output at each fixed input knot, in black-to-white order. */
    fun outputs(): List<Int> = listOf(black, shadow, midtone, highlight, white)

    /** Move one knot without crossing the adjacent outputs; endpoints can be lifted/clipped. */
    fun withOutput(index: Int, output: Int): ToneCurve {
        require(index in INPUTS.indices)
        val values = outputs().toMutableList()
        values[index] = output.coerceIn(values.getOrElse(index - 1) { 0 }, values.getOrElse(index + 1) { 255 })
        return ToneCurve(values[0], values[1], values[2], values[3], values[4])
    }

    /** The renderer and editor share this exact 256-entry transfer function. */
    fun lookup(): IntArray {
        val values = outputs()
        val slopes = DoubleArray(4) { i -> (values[i + 1] - values[i]).toDouble() / (INPUTS[i + 1] - INPUTS[i]) }
        val tangents = DoubleArray(5)
        tangents[0] = slopes[0]
        tangents[4] = slopes[3]
        for (i in 1..3) {
            val left = slopes[i - 1]; val right = slopes[i]
            if (left == 0.0 || right == 0.0) continue
            val a = INPUTS[i] - INPUTS[i - 1]; val b = INPUTS[i + 1] - INPUTS[i]
            val w1 = 2.0 * b + a; val w2 = b + 2.0 * a
            tangents[i] = (w1 + w2) / (w1 / left + w2 / right)
        }
        val result = IntArray(256)
        for (i in 0..3) {
            val span = INPUTS[i + 1] - INPUTS[i]
            val low = values[i]; val high = values[i + 1]
            for (x in INPUTS[i]..INPUTS[i + 1]) {
                val t = (x - INPUTS[i]).toDouble() / span
                val t2 = t * t; val t3 = t2 * t
                val y = (2 * t3 - 3 * t2 + 1) * low + (t3 - 2 * t2 + t) * span * tangents[i] +
                    (-2 * t3 + 3 * t2) * high + (t3 - t2) * span * tangents[i + 1]
                result[x] = y.roundToInt().coerceIn(low, high)
            }
        }
        return result
    }

    companion object {
        /** Input brightness values in the shared 8-bit HSV-value domain. */
        val INPUTS: List<Int> = listOf(0, 64, 128, 192, 255)
    }
}
