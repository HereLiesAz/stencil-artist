package com.hereliesaz.stencilartist.core

import kotlin.math.ceil
import kotlin.math.floor

/** Image-space brush centre; pixel centres lie at x + .5, y + .5. */
data class MaskPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}

/** One undoable brush stroke. Restore uses original alpha rather than inventing opaque pixels. */
data class MaskStroke(val points: List<MaskPoint>, val radius: Float, val restore: Boolean) {
    init { require(points.isNotEmpty() && radius.isFinite() && radius >= .5f) }
}

/** Corrected source and explicit exclusions, distinct from automatic-mask holes. */
class MaskCorrection(val image: RgbaImage, val excluded: BooleanArray)

/** Non-destructive foreground/background edits in working-image coordinates. */
object MaskCorrections {
    /** Last stroke wins. Swept discs join sparse samples without gaps; untouched alpha is retained. */
    fun apply(source: RgbaImage, automatic: RgbaImage, strokes: List<MaskStroke>): MaskCorrection {
        require(source.width == automatic.width && source.height == automatic.height)
        val w = source.width; val h = source.height
        val pixels = automatic.pixels.copyOf()
        val excluded = BooleanArray(pixels.size)
        for (stroke in strokes) {
            visitBrushPixels(w, h, stroke.points, stroke.radius) { i, _ ->
                excluded[i] = !stroke.restore
                pixels[i] = if (stroke.restore) source.pixels[i] else source.pixels[i] and 0xFFFFFF
            }
        }
        return MaskCorrection(RgbaImage(w, h, pixels), excluded)
    }

    /** Original photograph tinted red where alpha was removed. Diagnostic only. */
    fun overlay(source: RgbaImage, masked: RgbaImage): RgbaImage {
        require(source.width == masked.width && source.height == masked.height)
        return RgbaImage(source.width, source.height, IntArray(source.pixels.size) { i ->
            val p = source.pixels[i]; val a = p ushr 24
            if (a == 0) p else {
                val removed = (a - (masked.pixels[i] ushr 24)).coerceAtLeast(0)
                val mix = removed * .65f / a
                fun tint(shift: Int, target: Int): Int {
                    val channel = p ushr shift and 255
                    return (channel + (target - channel) * mix + .5f).toInt().coerceIn(0, 255)
                }
                (p and 0xFF000000.toInt()) or (tint(16, 255) shl 16) or (tint(8, 24) shl 8) or tint(0, 48)
            }
        })
    }
}

/** Visit pixel centres in the swept image-space disc. Multiple segments can visit the same
 * pixel; callers requiring one application per stroke must accumulate maximum coverage. */
internal fun visitBrushPixels(width: Int, height: Int, points: List<MaskPoint>, radius: Float,
    visit: (Int, Float) -> Unit) {
    fun segment(a: MaskPoint, b: MaskPoint) {
        val x0 = floor(minOf(a.x, b.x) - radius).toInt().coerceIn(0, width)
        val x1 = ceil(maxOf(a.x, b.x) + radius).toInt().coerceIn(0, width)
        val y0 = floor(minOf(a.y, b.y) - radius).toInt().coerceIn(0, height)
        val y1 = ceil(maxOf(a.y, b.y) + radius).toInt().coerceIn(0, height)
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = dx * dx + dy * dy
        for (y in y0 until y1) for (x in x0 until x1) {
            val px = x + .5f - a.x; val py = y + .5f - a.y
            val t = if (length == 0f) 0f else ((px * dx + py * dy) / length).coerceIn(0f, 1f)
            val ex = px - t * dx; val ey = py - t * dy
            val squared = ex * ex + ey * ey
            if (squared <= radius * radius) visit(y * width + x, kotlin.math.sqrt(squared) / radius)
        }
    }
    segment(points.first(), points.first())
    for (i in 1 until points.size) segment(points[i - 1], points[i])
}
