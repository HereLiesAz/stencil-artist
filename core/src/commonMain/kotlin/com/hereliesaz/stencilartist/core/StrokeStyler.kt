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
    const val MIN_BRUSH_WIDTH_PIXELS = 1
    const val MAX_BRUSH_WIDTH_PIXELS = 24
    const val DEFAULT_BRUSH_WIDTH_PIXELS = 2

    fun stylize(
        source: RgbaImage,
        original: Separation,
        strokeWidthPixels: Int = DEFAULT_BRUSH_WIDTH_PIXELS,
        maximumRemovedFraction: Double = 0.20,
    ): Separation {
        require(source.width == original.width && source.height == original.height)
        require(strokeWidthPixels in MIN_BRUSH_WIDTH_PIXELS..MAX_BRUSH_WIDTH_PIXELS)
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

            // Only original stock connected to the page border can anchor a
            // new negative stroke. Adjacency to a pre-existing unsupported
            // island must never be mistaken for a physical connection.
            val supported = BooleanArray(n)
            val stockQueue = IntArray(n)
            var stockTail = 0
            fun addSupported(p: Int) {
                if (!mask[p] && !supported[p]) {
                    supported[p] = true
                    stockQueue[stockTail++] = p
                }
            }
            for (x in 0 until w) {
                addSupported(x)
                addSupported((h - 1) * w + x)
            }
            for (y in 0 until h) {
                addSupported(y * w)
                addSupported(y * w + w - 1)
            }
            var stockHead = 0
            while (stockHead < stockTail) {
                val p = stockQueue[stockHead++]
                val x = p % w
                if (x > 0) addSupported(p - 1)
                if (x + 1 < w) addSupported(p + 1)
                if (p >= w) addSupported(p - w)
                if (p + w < n) addSupported(p + w)
            }

            val rebuilt = mask.copyOf()
            val protected = BooleanArray(n)
            val narrowCandidate = BooleanArray(n)
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

                // A brush broader than a separate region's narrowest bounding span
                // cannot fit there. Leave its complete original, solid paint opening
                // untouched instead of turning the small shape into coarse texture.
                // The original small-detail limit also applies at the smallest width.
                if (tail < 160 || thickest < 4 ||
                    min(maxX - minX + 1, maxY - minY + 1) < strokeWidthPixels) continue
                // Below the selected brush width, slender extensions of a broad region
                // remain solid as well. The nearby broad-core check distinguishes a
                // real thin limb from the perimeter of an otherwise wide paint area.
                val localHalfSpan = max(4, (strokeWidthPixels + 1) / 2)
                val protectedWidth = max(5, strokeWidthPixels - 1)
                val broadCoreDepth = max(6, (strokeWidthPixels + 2) / 2)
                val broadReach = max(5, (strokeWidthPixels + 1) / 2)
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
                    if (distance[p] > broadReach) continue
                    val x = p % w
                    val y = p / w
                    // Check four axes, including both diagonals. A thin diagonal
                    // highlight has broad horizontal AND vertical runs, so checking
                    // only those axes erased the very feature being protected.
                    fun chord(dx: Int, dy: Int): Int {
                        var length = 1
                        for (sign in -1..1 step 2) {
                            for (k in 1..localHalfSpan) {
                                val xx = x + sign * k * dx
                                val yy = y + sign * k * dy
                                if (xx !in 0 until w || yy !in 0 until h ||
                                    !mask[yy * w + xx]) break
                                length++
                            }
                        }
                        return length
                    }
                    if (minOf(chord(1, 0), chord(0, 1), chord(1, 1), chord(1, -1)) <= protectedWidth) {
                        narrowCandidate[p] = true
                        // A broad blob's boundary is thin along one axis too. If
                        // substantial painted interior lies nearby, this is a
                        // broad edge, not a separate delicate mark. Actual narrow
                        // extensions have no nearby core in any direction.
                        var nearBroadCore = false
                        for (dy in -broadReach..broadReach) {
                            if (nearBroadCore) break
                            for (dx in -broadReach..broadReach) {
                                if (abs(dx) + abs(dy) > broadReach) continue
                                val xx = x + dx
                                val yy = y + dy
                                if (xx in 0 until w && yy in 0 until h &&
                                    distance[yy * w + xx] >= broadCoreDepth) {
                                    nearBroadCore = true
                                    break
                                }
                            }
                        }
                        if (!nearBroadCore) {
                            protected[p] = true
                            rebuilt[p] = true
                            painted++
                        }
                    }
                }
                // A thin stroke attached to a broad region may have one final
                // junction pixel close enough to the broad core to escape the
                // local-width test. Include only directly neighboring narrow
                // candidates, using a two-phase update so BFS traversal order
                // cannot change the result. This retains the complete feature
                // without protecting the entire boundary of the large region.
                val continuations = ArrayList<Int>()
                for (i in 0 until tail) {
                    val p = queue[i]
                    if (!narrowCandidate[p] || protected[p]) continue
                    val x = p % w
                    if ((x > 0 && protected[p - 1]) ||
                        (x + 1 < w && protected[p + 1]) ||
                        (p >= w && protected[p - w]) ||
                        (p + w < n && protected[p + w])) {
                        continuations += p
                    }
                }
                for (p in continuations) {
                    protected[p] = true
                    rebuilt[p] = true
                    painted++
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
                // Very wide brushes still need at least one chance to make a
                // supported negative gap in ordinary image-sized paint regions.
                val gapSpacing = max(12, min(48, strokeWidthPixels * 7))
                // A negative brush mark must begin in stock that is connected to the
                // outside of the sheet. Scan all four opening boundaries, not just
                // left-facing ones: otherwise rotating the image changes whether
                // the same fold can receive a physically supported brush gap.
                for (side in 0 until 4) {
                    if (unpainted >= allowedMissing) break
                    val inwardX = when (side) {
                        0 -> 1
                        1 -> -1
                        else -> 0
                    }
                    val inwardY = when (side) {
                        2 -> 1
                        3 -> -1
                        else -> 0
                    }
                    val alongMin = if (side < 2) minY else minX
                    val alongMax = if (side < 2) maxY else maxX
                    val scanLength = if (side < 2) maxX - minX + 1 else maxY - minY + 1
                    var along = alongMin + gapSpacing / 2
                    while (along <= alongMax - gapSpacing / 2 && unpainted < allowedMissing) {
                        var seedX = -1
                        var seedY = -1
                        for (depth in 0 until scanLength) {
                            val x = when (side) {
                                0 -> minX + depth
                                1 -> maxX - depth
                                else -> along
                            }
                            val y = when (side) {
                                2 -> minY + depth
                                3 -> maxY - depth
                                else -> along
                            }
                            val aheadX = x + inwardX * 8
                            val aheadY = y + inwardY * 8
                            if (x !in 1 until w - 1 || y !in 1 until h - 1 ||
                                aheadX !in 1 until w - 1 || aheadY !in 1 until h - 1) continue
                            val p = y * w + x
                            val ahead = aheadY * w + aheadX
                            val outside = (y - inwardY) * w + x - inwardX
                            // The entry point must touch the actual border-supported
                            // stock, not merely a pre-existing detached stock island.
                            if (member[p] == component && supported[outside] &&
                                !protected[p] && member[ahead] == component &&
                                distance[ahead] >= 5) {
                                seedX = x
                                seedY = y
                                break
                            }
                        }
                        if (seedX >= 0) {
                            var px = seedX.toFloat()
                            var py = seedY.toFloat()
                            var vx = inwardX.toFloat()
                            var vy = inwardY.toFloat()
                            var lastX = seedX
                            var lastY = seedY
                            for (step in 0 until 38) {
                                if (unpainted >= allowedMissing) break
                                val x = px.roundToInt()
                                val y = py.roundToInt()
                                if (x !in 2 until w - 2 || y !in 2 until h - 2) break
                                val p = y * w + x
                                if (member[p] != component || protected[p] ||
                                    (step > 8 && distance[p] <= 3)) break
                                // A diagonal round-off needs the joining corner to
                                // remain connected to the supported stock.
                                if (step > 0 && x != lastX && y != lastY) {
                                    val corner = lastY * w + x
                                    if (member[corner] != component || protected[corner]) break
                                    if (rebuilt[corner] && unpainted < allowedMissing) {
                                        rebuilt[corner] = false
                                        unpainted++
                                    }
                                }
                                val nx = -vy
                                val ny = vx
                                val span = if (step < 3) 1 else strokeWidthPixels
                                for (offsetIndex in 0 until span) {
                                    val offset = offsetIndex - (span - 1) * 0.5f
                                    val xx = (x + nx * offset).roundToInt()
                                    val yy = (y + ny * offset).roundToInt()
                                    if (xx !in 0 until w || yy !in 0 until h) continue
                                    val q = yy * w + xx
                                    if (member[q] == component && !protected[q] &&
                                        rebuilt[q] && unpainted < allowedMissing) {
                                        rebuilt[q] = false
                                        unpainted++
                                    }
                                }
                                lastX = x
                                lastY = y
                                val target = direction(p, vx, vy)
                                val blend = if (step < 12) 0f else
                                    min(0.6f, 0.10f + 0.02f * (step - 12))
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
                        along += gapSpacing
                    }
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
                q[tail++] = p
                seen[p] = true
                while (head < tail) {
                    val current = q[head++]
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
                // This component is NOT border-connected. Restore only newly
                // removed paint. Preserve every original uncut pixel, including
                // pre-existing islands, but never let new ribbons enlarge them.
                for (i in 0 until tail) if (mask[q[i]]) rebuilt[q[i]] = true
            }
            cuts[sheet] = rebuilt
        }
        return Separation.fromMasks(w, h, original.sheets, cuts, original.liftZones)
    }
}
