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
                for (i in 0 until tail) rebuilt[queue[i]] = false
                var painted = 0
                // Component-wide thickness misses thin extensions attached to a wide
                // blob. Preserve any locally narrow cross-section, regardless of
                // whether it connects to a broad face, garment fold, or letter.
                // Measure local width in four directions, not distance to one
                // perimeter edge; thin strokes can be attached at any orientation.
                for (i in 0 until tail) {
                    val p = queue[i]
                    // Skip deep interior farther than the protected local stroke
                    // width from a retained-material boundary. Near-edge pixels
                    // still need full orientation-independent width checks.
                    if (distance[p] > 5) continue
                    val x = p % w
                    val y = p / w
                    // Check four axes, including both diagonals. A thin diagonal
                    // highlight has broad horizontal AND vertical runs, so checking
                    // only those axes erased the very feature being protected.
                    fun chord(dx: Int, dy: Int): Int {
                        var length = 1
                        for (sign in -1..1 step 2) {
                            for (k in 1..4) {
                                val xx = x + sign * k * dx
                                val yy = y + sign * k * dy
                                if (xx !in 0 until w || yy !in 0 until h ||
                                    !mask[yy * w + xx]) break
                                length++
                            }
                        }
                        return length
                    }
                    if (minOf(chord(1, 0), chord(0, 1), chord(1, 1), chord(1, -1)) <= 5) {
                        rebuilt[p] = true
                        painted++
                    }
                }
                // The broad-area quota MUST exclude already protected fine details.
                // Otherwise an 800x3 line can consume the entire quota and cause
                // an attached 20x20 patch to vanish without a single painted ribbon.
                val unprotected = tail - painted
                val allowedMissing = (unprotected * maximumRemovedFraction).toInt()
                if (allowedMissing < 1) {
                    for (i in 0 until tail) rebuilt[queue[i]] = true
                    continue
                }
                // Leave part of the missing-paint allowance for *connected* negative
                // brushstrokes. Free-floating gaps between positive ribbons are
                // physically unusable islands and are later filled back in.
                val reservedForConnectedGaps = max(1, allowedMissing / 2)
                val required = tail - (allowedMissing - reservedForConnectedGaps)
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
                    // Short intentional marks are easier to cut than long wandering paths;
                    // overlapping neighboring marks can still build a large folded surface.
                    for (step in 0 until 70) {
                        val sx = x.roundToInt()
                        val sy = y.roundToInt()
                        if (sx !in 2 until w - 2 || sy !in 2 until h - 2) break
                        val p = sy * w + sx
                        if (member[p] != component) break
                        val size = if (step < 2 || distance[p] <= 1) 1 else strokeWidthPixels
                        mark(sx, sy, vx, vy, size)
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

                // Finish the painting with narrow negative brushstrokes that START
                // at the original opening boundary and remain connected to stock.
                // This is not a second arbitrary texture filter: it makes genuine
                // supported gaps between the positive paint ribbons, where purely
                // internal ribbon gaps would detach as stencil islands.
                var unpainted = 0
                for (i in 0 until tail) if (!rebuilt[queue[i]]) unpainted++
                val gapSpacing = max(12, strokeWidthPixels * 7)
                var seedY = minY + gapSpacing / 2
                while (seedY <= maxY - gapSpacing / 2 && unpainted < allowedMissing) {
                    var seedX = -1
                    for (x in max(1, minX)..min(maxX - 8, w - 9)) {
                        val p = seedY * w + x
                        // The first pixel is adjacent to retained material, and a
                        // substantial body of paint extends inward from that edge.
                        if (member[p] == component && member[p - 1] != component &&
                            member[p + 8] == component && distance[p + 8] >= 5) {
                            seedX = x
                            break
                        }
                    }
                    if (seedX >= 0) {
                        var px = seedX.toFloat()
                        var py = seedY.toFloat()
                        var vx = 1f
                        var vy = 0f
                        var lastX = seedX
                        var lastY = seedY
                        for (step in 0 until 38) {
                            if (unpainted >= allowedMissing) break
                            val x = px.roundToInt()
                            val y = py.roundToInt()
                            if (x !in 2 until w - 2 || y !in 2 until h - 2) break
                            val p = y * w + x
                            if (member[p] != component ||
                                (step > 8 && distance[p] <= 3)) break
                            // Preserve four-connected stock even across a diagonal
                            // rounding step, as an artist would keep a scribble joined.
                            if (step > 0 && x != lastX && y != lastY) {
                                val corner = lastY * w + x
                                if (member[corner] == component && rebuilt[corner] &&
                                    unpainted < allowedMissing) {
                                    rebuilt[corner] = false
                                    unpainted++
                                }
                            }
                            val nx = -vy
                            val ny = vx
                            val span = if (step < 3) 1 else strokeWidthPixels
                            for (side in 0 until span) {
                                val offset = side - (span - 1) * 0.5f
                                val xx = (x + nx * offset).roundToInt()
                                val yy = (y + ny * offset).roundToInt()
                                if (xx !in 0 until w || yy !in 0 until h) continue
                                val q = yy * w + xx
                                if (member[q] == component && rebuilt[q] &&
                                    unpainted < allowedMissing) {
                                    rebuilt[q] = false
                                    unpainted++
                                }
                            }
                            lastX = x
                            lastY = y
                            val target = direction(p, vx, vy)
                            // Keep the negative stroke flowing inward long enough
                            // to connect to the surrounding sheet before following
                            // the source-image contour as a brushstroke would.
                            val blend = min(0.6f, 0.10f + 0.02f * step)
                            val dx = (1f - blend) * vx + blend * target.first
                            val dy = (1f - blend) * vy + blend * target.second
                            val length = sqrt(dx * dx + dy * dy)
                            if (length <= 0f) break
                            vx = dx / length
                            vy = dy / length
                            px += vx
                            py += vy
                        }
                    }
                    seedY += gapSpacing
                }
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
