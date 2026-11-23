package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reconstructs large later-layer paint openings from small, flowing POSITIVE brushstrokes.
 *
 * Unlike tracing the outline of a colour blob or cutting decorative notches into it, this
 * starts with an empty opening for each sufficiently broad connected paint region and lays
 * curved, narrow ribbons of paint inside the original region. The direction follows the
 * processed image's luminance contours, falling back to the region's own contour flow when
 * luminance is flat. Independent ribbons make broad grey/white masses read as brushwork.
 *
 * The original mask is authoritative: no generated stroke paints outside it; the base sheet
 * and narrow or isolated details are copied unchanged. A bounded amount of broad-area paint
 * may be omitted as the spaces *between* marks. Enclosed gaps introduced by reconstruction
 * are filled back in rather than manufacturing new retained-stock islands.
 *
 * This is optional, and does not claim semantic reconstruction or calibrated spray technique.
 * Printable bridge widths are still handled separately in physical units.
 */
object StrokeStyler {
    fun stylize(
        source: RgbaImage,
        original: Separation,
        strokeWidthPixels: Int = 2,
        maximumRemovedFraction: Double = 0.20,
    ): Separation {
        require(source.width == original.width && source.height == original.height)
        require(strokeWidthPixels in 1..6)
        require(maximumRemovedFraction in 0.0..0.40)
        val w = original.width
        val h = original.height
        if (w < 16 || h < 16 || original.sheets.size <= 1 || original.sheets.size > 16 ||
            maximumRemovedFraction == 0.0) return original
        val n = w * h
        val cuts = original.sheets.indices.map { original.mask(it) }.toMutableList()
        val raw = IntArray(n) { p ->
            val c = source.pixels[p]
            ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 +
                (c and 255) * 114 + 500) / 1000
        }
        // A small smoothing kernel prevents individual source pixels from steering a stroke
        // into noise. High-gradient structure remains visible to the path integrator.
        val light = raw.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val p = y * w + x
            light[p] = (raw[p] * 4 + raw[p - 1] + raw[p + 1] +
                raw[p - w] + raw[p + w]) / 8
        }

        for (sheet in 1 until cuts.size) {
            val mask = cuts[sheet]
            val distance = IntArray(n) { if (mask[it]) w + h else 0 }
            // Manhattan distance to an opening's boundary. A local thickness test protects
            // small distinctive highlights without relying on a single global area cutoff.
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                if (!mask[p]) continue
                distance[p] = min(distance[p], min(
                    if (x == 0) 1 else distance[p - 1] + 1,
                    if (y == 0) 1 else distance[p - w] + 1,
                ))
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val p = y * w + x
                if (!mask[p]) continue
                distance[p] = min(distance[p], min(
                    if (x == w - 1) 1 else distance[p + 1] + 1,
                    if (y == h - 1) 1 else distance[p + w] + 1,
                ))
            }

            val rebuilt = mask.copyOf()
            val member = IntArray(n)
            val queue = IntArray(n)
            var component = 0

            for (start in 0 until n) {
                if (!mask[start] || member[start] != 0) continue
                component++
                var head = 0
                var tail = 0
                var minX = w
                var minY = h
                var maxX = 0
                var maxY = 0
                var thickest = 0
                member[start] = component
                queue[tail++] = start
                while (head < tail) {
                    val p = queue[head++]
                    val x = p % w
                    val y = p / w
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    thickest = max(thickest, distance[p])
                    fun visit(q: Int) {
                        if (mask[q] && member[q] == 0) {
                            member[q] = component
                            queue[tail++] = q
                        }
                    }
                    if (x > 0) visit(p - 1)
                    if (x + 1 < w) visit(p + 1)
                    if (y > 0) visit(p - w)
                    if (y + 1 < h) visit(p + w)
                }

                // Small regions and thin lines carry important information (eyes, teeth,
                // lens reflections, letters) and are not a place to introduce hatching.
                if (tail < 160 || thickest < 4) continue
                val required = (tail * (1.0 - maximumRemovedFraction)).toInt()
                if (required >= tail) continue
                for (i in 0 until tail) rebuilt[queue[i]] = false
                var painted = 0
                val seedSpacing = max(3, strokeWidthPixels + 2)
                val fallbackX = if (maxX - minX >= maxY - minY) 1f else 0f
                val fallbackY = 1f - fallbackX

                fun direction(p: Int, previousX: Float, previousY: Float): Pair<Float, Float> {
                    val gx = light[p + 1] - light[p - 1]
                    val gy = light[p + w] - light[p - w]
                    val magnitude = sqrt((gx * gx + gy * gy).toFloat())
                    var tx: Float
                    var ty: Float
                    if (magnitude >= 3f) {
                        tx = -gy / magnitude
                        ty = gx / magnitude
                    } else {
                        // In a nearly uniform painted plane, follow the shape's contour.
                        val dx = distance[p + 1] - distance[p - 1]
                        val dy = distance[p + w] - distance[p - w]
                        val norm = sqrt((dx * dx + dy * dy).toFloat())
                        tx = if (norm >= 1f) -dy / norm else fallbackX
                        ty = if (norm >= 1f) dx / norm else fallbackY
                    }
                    if (tx * previousX + ty * previousY < 0f) {
                        tx = -tx
                        ty = -ty
                    }
                    return tx to ty
                }

                fun mark(x: Int, y: Int, vx: Float, vy: Float, span: Int) {
                    val nx = -vy
                    val ny = vx
                    for (side in 0 until span) {
                        val offset = side - (span - 1) * 0.5f
                        val sx = (x + nx * offset).roundToInt()
                        val sy = (y + ny * offset).roundToInt()
                        if (sx !in 0 until w || sy !in 0 until h) continue
                        val q = sy * w + sx
                        if (member[q] == component && !rebuilt[q]) {
                            rebuilt[q] = true
                            painted++
                        }
                    }
                }

                fun trace(seedX: Int, seedY: Int, sign: Float) {
                    var x = seedX.toFloat()
                    var y = seedY.toFloat()
                    val initial = direction(seedY * w + seedX, fallbackX, fallbackY)
                    var vx = initial.first * sign
                    var vy = initial.second * sign
                    var previous = -1
                    // Short intentional marks are easier to cut than long wandering paths;
                    // overlapping neighboring marks can still build a large folded surface.
                    for (step in 0 until 70) {
                        val sx = x.roundToInt()
                        val sy = y.roundToInt()
                        if (sx !in 2 until w - 2 || sy !in 2 until h - 2) break
                        val p = sy * w + sx
                        if (member[p] != component || p == previous) break
                        val size = if (step < 2 || distance[p] <= 1) 1 else strokeWidthPixels
                        mark(sx, sy, vx, vy, size)
                        previous = p
                        val desired = direction(p, vx, vy)
                        // Low curvature avoids jitter and spiky contour-tracing artefacts.
                        val dx = vx * 0.82f + desired.first * 0.18f
                        val dy = vy * 0.82f + desired.second * 0.18f
                        val length = sqrt(dx * dx + dy * dy)
                        if (length <= 0f) break
                        vx = dx / length
                        vy = dy / length
                        x += vx
                        y += vy
                    }
                }

                // A sweep per grid phase makes the result spatially balanced, rather than
                // spending the stroke budget in the top-left corner of the image.
                for (phase in 0 until seedSpacing * seedSpacing) {
                    if (painted >= required) break
                    val ox = phase % seedSpacing
                    val oy = phase / seedSpacing
                    val firstY = minY + (oy - minY % seedSpacing + seedSpacing) % seedSpacing
                    val firstX = minX + (ox - minX % seedSpacing + seedSpacing) % seedSpacing
                    var y = firstY
                    while (y <= maxY && painted < required) {
                        var x = firstX
                        while (x <= maxX && painted < required) {
                            val p = y * w + x
                            if (member[p] == component && distance[p] >= 3 && !rebuilt[p]) {
                                trace(x, y, 1f)
                                trace(x, y, -1f)
                            }
                            x += seedSpacing
                        }
                        y += seedSpacing
                    }
                }

                // Insufficiently reconstructed regions retain their source geometry; the
                // algorithm must not quietly discard distinctive large shapes.
                if (painted < required) for (i in 0 until tail) rebuilt[queue[i]] = true
            }

            // Reconstructed neighbouring paint strokes must never create NEW detached
            // retained-stock pieces. An enclosed gap made wholly of originally painted
            // pixels is filled back in. Existing dark details are deliberately untouched.
            val outside = BooleanArray(n)
            val q = IntArray(n)
            var head = 0
            var tail = 0
            fun visit(p: Int) {
                if (!rebuilt[p] && !outside[p]) {
                    outside[p] = true
                    q[tail++] = p
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
            while (head < tail) {
                val p = q[head++]
                val x = p % w
                if (x > 0) visit(p - 1)
                if (x + 1 < w) visit(p + 1)
                if (p >= w) visit(p - w)
                if (p + w < n) visit(p + w)
            }
            val seen = outside.copyOf()
            for (p in 0 until n) {
                if (rebuilt[p] || seen[p]) continue
                head = 0
                tail = 0
                var newlyCreated = true
                q[tail++] = p
                seen[p] = true
                while (head < tail) {
                    val current = q[head++]
                    if (!mask[current]) newlyCreated = false
                    val x = current % w
                    fun visitIsland(next: Int) {
                        if (!rebuilt[next] && !seen[next]) {
                            seen[next] = true
                            q[tail++] = next
                        }
                    }
                    if (x > 0) visitIsland(current - 1)
                    if (x + 1 < w) visitIsland(current + 1)
                    if (current >= w) visitIsland(current - w)
                    if (current + w < n) visitIsland(current + w)
                }
                if (newlyCreated) for (i in 0 until tail) rebuilt[q[i]] = true
            }
            cuts[sheet] = rebuilt
        }
        return Separation.fromMasks(w, h, original.sheets, cuts, original.liftZones)
    }
}
