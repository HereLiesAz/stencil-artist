package com.hereliesaz.stencilartist.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Resizing single-channel float planes, as the models' pre- and post-processing needs it. */
object Resample {

    /**
     * [src] resized to [dstW] x [dstH] by averaging the source area each destination pixel covers,
     * edge pixels weighted by how much of them falls inside (OpenCV's `INTER_AREA` when shrinking).
     * The right way to make a small image from a large one: every source pixel counts.
     */
    fun area(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        require(src.size == srcW * srcH) { "plane size does not match ${srcW}x$srcH" }
        require(dstW > 0 && dstH > 0) { "cannot resize to ${dstW}x$dstH" }
        // Separable: weights along each axis are independent.
        val across = FloatArray(dstW * srcH)
        spans(srcW, dstW) { x, from, weights ->
            for (y in 0 until srcH) {
                var sum = 0f
                for (k in weights.indices) sum += src[y * srcW + from + k] * weights[k]
                across[y * dstW + x] = sum
            }
        }
        val out = FloatArray(dstW * dstH)
        spans(srcH, dstH) { y, from, weights ->
            for (x in 0 until dstW) {
                var sum = 0f
                for (k in weights.indices) sum += across[(from + k) * dstW + x] * weights[k]
                out[y * dstW + x] = sum
            }
        }
        return out
    }

    /**
     * For each of [dst] cells laid evenly over [src] cells: the first source cell it touches and the
     * share of the destination cell each touched source cell makes up (the shares sum to one).
     */
    private inline fun spans(src: Int, dst: Int, each: (index: Int, from: Int, weights: FloatArray) -> Unit) {
        val scale = src.toDouble() / dst
        for (i in 0 until dst) {
            val start = i * scale
            val end = min(src.toDouble(), (i + 1) * scale)
            val from = floor(start).toInt().coerceIn(0, src - 1)
            val to = max(from, min(src - 1, floor(end - 1e-9).toInt()))
            val span = end - start
            val weights = FloatArray(to - from + 1) { k ->
                val cell = from + k
                val covered = min(end, cell + 1.0) - max(start, cell.toDouble())
                if (span > 0) (covered / span).toFloat() else 1f
            }
            each(i, from, weights)
        }
    }

    /**
     * [src] resized to [dstW] x [dstH] by bilinear interpolation between pixel centres, the edge
     * repeated beyond it (OpenCV's `INTER_LINEAR`). For enlarging a smooth map.
     */
    fun bilinear(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        require(src.size == srcW * srcH) { "plane size does not match ${srcW}x$srcH" }
        require(dstW > 0 && dstH > 0) { "cannot resize to ${dstW}x$dstH" }
        val x0 = IntArray(dstW)
        val x1 = IntArray(dstW)
        val fx = FloatArray(dstW)
        for (x in 0 until dstW) {
            val s = (x + 0.5f) * srcW / dstW - 0.5f
            val base = floor(s).toInt()
            x0[x] = base.coerceIn(0, srcW - 1)
            x1[x] = (base + 1).coerceIn(0, srcW - 1)
            fx[x] = s - base
        }
        val out = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val s = (y + 0.5f) * srcH / dstH - 0.5f
            val base = floor(s).toInt()
            val row0 = base.coerceIn(0, srcH - 1) * srcW
            val row1 = (base + 1).coerceIn(0, srcH - 1) * srcW
            val fy = s - base
            for (x in 0 until dstW) {
                val top = src[row0 + x0[x]] + (src[row0 + x1[x]] - src[row0 + x0[x]]) * fx[x]
                val bottom = src[row1 + x0[x]] + (src[row1 + x1[x]] - src[row1 + x0[x]]) * fx[x]
                out[y * dstW + x] = top + (bottom - top) * fy
            }
        }
        return out
    }
}
