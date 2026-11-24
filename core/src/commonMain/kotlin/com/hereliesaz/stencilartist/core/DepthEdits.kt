package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Height-field brush operation; higher normalized inverse depth means closer to the source camera. */
enum class DepthBrush { RAISE, LOWER, SET, SMOOTH, FLATTEN }

/** One undoable stroke. Strength and hardness are fractions; target is normalized inverse depth.
 * A stroke affects each covered pixel once, regardless of pointer sampling frequency. */
data class DepthStroke(
    val points: List<MaskPoint>,
    val radius: Float,
    val mode: DepthBrush,
    val strength: Float = .25f,
    val hardness: Float = .75f,
    val target: Float = .5f,
) {
    init {
        require(points.isNotEmpty() && radius.isFinite() && radius >= .5f)
        require(strength in 0f..1f && hardness in 0f..1f && target in 0f..1f)
    }
}

/** Non-destructive depth corrections, independent of source RGB, alpha and lighting parameters.
 * Apply strokes in order, then global range-gated smoothing, flattening and clamp/remap.
 * Masked-out pixels remain untouched. All depth values are relative, not physical distances. */
data class DepthEdits(
    val strokes: List<DepthStroke> = emptyList(),
    val smoothingPasses: Int = 0,
    val edgeThreshold: Float = .15f,
    val flatten: Float = 0f,
    val flattenTarget: Float = .5f,
    val low: Float = 0f,
    val high: Float = 1f,
    val gamma: Float = 1f,
) {
    init {
        require(smoothingPasses in 0..4 && edgeThreshold in .01f..1f)
        require(flatten in 0f..1f && flattenTarget in 0f..1f)
        require(low in 0f..1f && high in 0f..1f && high - low >= .009f && gamma in .25f..4f)
    }

    /** Return a fresh normalized map without changing inference results or the subject.
     * A four-neighbour filter ignores transparent pixels and sharp depth steps. */
    fun apply(base: FloatArray, subject: RgbaImage): FloatArray {
        require(base.size == subject.pixels.size && base.all { it.isFinite() && it in 0f..1f })
        val w = subject.width; val h = subject.height
        val visible = BooleanArray(base.size) { subject.pixels[it] ushr 24 != 0 }
        var result = base.copyOf()
        fun average(values: FloatArray, i: Int): Float {
            val center = values[i]
            var sum = center; var count = 1
            fun add(j: Int) {
                if (visible[j] && abs(values[j] - center) <= edgeThreshold) {
                    sum += values[j]; count++
                }
            }
            if (i % w > 0) add(i - 1)
            if (i % w < w - 1) add(i + 1)
            if (i >= w) add(i - w)
            if (i < values.size - w) add(i + w)
            return sum / count
        }
        if (strokes.isNotEmpty()) {
            val weights = FloatArray(base.size)
            val touched = IntArray(base.size)
            for (stroke in strokes) {
                var count = 0
                visitBrushPixels(w, h, stroke.points, stroke.radius) { i, distance ->
                    if (visible[i]) {
                        val weight = if (distance <= stroke.hardness || stroke.hardness == 1f) 1f
                            else (1f - distance) / (1f - stroke.hardness)
                        if (weight > 0f) {
                            if (weights[i] == 0f) touched[count++] = i
                            weights[i] = maxOf(weights[i], weight)
                        }
                    }
                }
                val before = if (stroke.mode == DepthBrush.SMOOTH) result.copyOf() else result
                var total = 0.0; var weightSum = 0.0
                if (stroke.mode == DepthBrush.FLATTEN) for (k in 0 until count) {
                    val i = touched[k]
                    total += before[i].toDouble() * weights[i]
                    weightSum += weights[i]
                }
                val mean = if (weightSum > 0) (total / weightSum).toFloat() else 0f
                for (k in 0 until count) {
                    val i = touched[k]; val v = before[i]
                    val amount = stroke.strength * weights[i]
                    val target = when (stroke.mode) {
                        DepthBrush.RAISE -> v + amount
                        DepthBrush.LOWER -> v - amount
                        DepthBrush.SET -> v + (stroke.target - v) * amount
                        DepthBrush.SMOOTH -> v + (average(before, i) - v) * amount
                        DepthBrush.FLATTEN -> v + (mean - v) * amount
                    }
                    result[i] = target.coerceIn(0f, 1f)
                    weights[i] = 0f
                }
            }
        }
        repeat(smoothingPasses) {
            val before = result
            result = FloatArray(base.size) { i -> if (visible[i]) average(before, i) else before[i] }
        }
        if (flatten != 0f || low != 0f || high != 1f || gamma != 1f) {
            for (i in result.indices) if (visible[i]) {
                val v = result[i] + (flattenTarget - result[i]) * flatten
                result[i] = ((v - low) / (high - low)).coerceIn(0f, 1f).pow(gamma)
            }
        }
        return result
    }

    /** Alpha-preserving diagnostic of the edited field; lighter or warmer means closer.
     * False colour is a blue-to-cyan-to-yellow-to-red ramp, not a stencil palette. */
    fun preview(base: FloatArray, subject: RgbaImage, falseColor: Boolean = false): RgbaImage {
        val field = apply(base, subject)
        return RgbaImage(subject.width, subject.height, IntArray(field.size) { i ->
            val a = subject.pixels[i] and 0xFF000000.toInt()
            if (a == 0) 0 else {
                val v = field[i]
                fun byte(x: Float) = (255f * x.coerceIn(0f, 1f)).roundToInt()
                val rgb = if (!falseColor) byte(v).let { (it shl 16) or (it shl 8) or it }
                    else (byte(3f * v - 1f) shl 16) or (byte(1.5f - abs(3f * v - 1.5f)) shl 8) or byte(1f - v * 1.5f)
                a or rgb
            }
        })
    }
}
