package com.hereliesaz.stencilartist.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A normalized brush footprint predicted by a neural stroke model. The angle is radians, and
 * coordinates refer to the entire source image rather than an individual prediction patch.
 */
data class NeuralStroke(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
)

/**
 * Converts predicted strokes to optional negative space in *existing* cut openings.
 *
 * The input mask remains authoritative. New paint cannot escape it, the base is untouched, and
 * changes are limited to broad interiors. All newly retained material must connect to the
 * original border-supported stock. Model predictions cannot silently introduce new loose islands.
 * This is a conservative raster prototype, not a reconstruction of the upstream oil renderer.
 */
object NeuralStrokeMask {
    private const val RASTER_EDGE = 256

    fun apply(original: Separation, marks: List<List<NeuralStroke>>, maximumRemovedFraction: Double = 0.20): Separation {
        require(maximumRemovedFraction in 0.0..0.40)
        require(marks.size == original.sheets.size)
        if (original.width < 16 || original.height < 16 || maximumRemovedFraction == 0.0) return original
        val w = original.width
        val h = original.height
        val n = w * h
        val smallW = min(w, RASTER_EDGE)
        val smallH = min(h, RASTER_EDGE)
        val masks = original.sheets.indices.map { original.mask(it) }.toMutableList()
        val queue = IntArray(n)

        for (sheet in 1 until masks.size) {
            val input = masks[sheet]
            if (marks[sheet].isEmpty()) continue
            val predicted = BooleanArray(smallW * smallH)
            for (stroke in marks[sheet]) {
                if (!stroke.x.isFinite() || !stroke.y.isFinite() || !stroke.width.isFinite() ||
                    !stroke.height.isFinite() || !stroke.angle.isFinite()) continue
                if (stroke.width <= 0f || stroke.height <= 0f) continue
                val cx = stroke.x * smallW
                val cy = stroke.y * smallH
                val radiusX = (stroke.width * smallW / 2f).coerceAtLeast(0.5f)
                val radiusY = (stroke.height * smallH / 2f).coerceAtLeast(0.5f)
                val reach = max(radiusX, radiusY)
                val left = max(0, (cx - reach).toInt() - 1)
                val right = min(smallW - 1, (cx + reach).toInt() + 1)
                val top = max(0, (cy - reach).toInt() - 1)
                val bottom = min(smallH - 1, (cy + reach).toInt() + 1)
                val ca = cos(stroke.angle)
                val sa = sin(stroke.angle)
                for (y in top..bottom) for (x in left..right) {
                    val dx = (x + 0.5f - cx)
                    val dy = (y + 0.5f - cy)
                    val rx = (dx * ca + dy * sa) / radiusX
                    val ry = (-dx * sa + dy * ca) / radiusY
                    if (rx * rx + ry * ry <= 1f) predicted[y * smallW + x] = true
                }
            }
            val wanted = BooleanArray(n) { pixel ->
                val x = pixel % w
                val y = pixel / w
                predicted[(y * smallH / h) * smallW + (x * smallW / w)]
            }
            // Preserve small regions and their attached narrow extensions by requiring a broad
            // surrounding opening before a negative stroke can remove any original paint.
            val thickness = IntArray(n) { if (input[it]) w + h else 0 }
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                if (!input[p]) continue
                thickness[p] = min(thickness[p], min(
                    if (x == 0) 1 else thickness[p - 1] + 1,
                    if (y == 0) 1 else thickness[p - w] + 1,
                ))
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val p = y * w + x
                if (!input[p]) continue
                thickness[p] = min(thickness[p], min(
                    if (x == w - 1) 1 else thickness[p + 1] + 1,
                    if (y == h - 1) 1 else thickness[p + w] + 1,
                ))
            }
            val output = input.copyOf()
            val budget = (input.count { it } * maximumRemovedFraction).toInt()
            if (budget <= 0) continue
            // The candidate gaps come from the neural strokes' negative space, but are opened
            // only where original retained stock can reach them through an existing boundary.
            var head = 0
            var tail = 0
            val seen = BooleanArray(n)
            fun visit(p: Int) {
                if (!input[p] && !seen[p]) {
                    seen[p] = true
                    queue[tail++] = p
                }
            }
            for (x in 0 until w) {
                visit(x)
                visit((h - 1) * w + x)
            }
            for (y in 0 until h) {
                visit(y * w)
                visit(y * w + w - 1)
            }
            var removed = 0
            while (head < tail && removed < budget) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                fun visitAdjacent(q: Int) {
                    if (seen[q]) return
                    if (!input[q]) {
                        seen[q] = true
                        queue[tail++] = q
                    } else if (!wanted[q] && thickness[q] >= 1) {
                        // Do not strip thin details. At least one point 5 pixels farther into
                        // the opening must exist in the same direction as the candidate.
                        val dx = q % w - x
                        val dy = q / w - y
                        val xx = q % w + 5 * dx
                        val yy = q / w + 5 * dy
                        if (xx in 0 until w && yy in 0 until h &&
                            thickness[yy * w + xx] >= 5) {
                            seen[q] = true
                            output[q] = false
                            removed++
                            queue[tail++] = q
                        }
                    } else {
                        seen[q] = true
                    }
                }
                if (x > 0) visitAdjacent(p - 1)
                if (x + 1 < w && removed < budget) visitAdjacent(p + 1)
                if (y > 0 && removed < budget) visitAdjacent(p - w)
                if (y + 1 < h && removed < budget) visitAdjacent(p + w)
            }
            // Exclusion is intentionally conservative: if neural strokes cannot produce a
            // supported gap, keep the original opening rather than create an uncut island.
            masks[sheet] = output
        }
        return Separation.fromMasks(w, h, original.sheets, masks, original.liftZones)
    }
}
