package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Conservative stroke-shaped NEGATIVE marks for the upper sheets of a stencil.
 *
 * Each mark begins at the physically supported stock outside a paint opening and follows
 * a source-luminance contour into a broad region. It is an inward tongue of retained
 * material, not a random blob. The tongue is continuous from its source and never paints
 * beyond an original opening, so narrow dark details cannot be newly overpainted.
 *
 * Strokes start from the mask normal, then bend gradually into the local isophote
 * T = normalize(-dL/dy, dL/dx). Their width is deliberately small and constant;
 * broad areas are articulated by neighboring strokes, not one giant round brush.
 *
 * This first stage is optional: final artistic reconstruction and scale-dependent
 * cutting thickness still require visual and physical verification.
 */
object StrokeStyler {
    /** Apply directional texture to up to 16 sheets, leaving the base untouched. */
    fun stylize(
        source: RgbaImage,
        original: Separation,
        strokeWidthPixels: Int = 2,
        maximumRemovedFraction: Double = 0.085,
    ): Separation {
        require(source.width == original.width && source.height == original.height)
        require(strokeWidthPixels in 1..6)
        require(maximumRemovedFraction in 0.0..0.25)
        val w = original.width
        val h = original.height
        if (w < 16 || h < 16 || original.sheets.size <= 1 || original.sheets.size > 16) return original
        val masks = original.sheets.indices.map { original.mask(it) }
        val luminance = IntArray(w * h) { i ->
            val p = source.pixels[i]
            ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 +
                (p and 255) * 114 + 500) / 1000
        }
        val halfWidth = strokeWidthPixels / 2

        for (sheet in 1 until masks.size) {
            val mask = masks[sheet]
            val originalPainted = mask.count { it }
            if (originalPainted < 200) continue
            val budget = (originalPainted * maximumRemovedFraction).toInt()
            if (budget < 8) continue

            // Supported stock is 4-connected to the full sheet's outside edge.
            val supported = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            var tail = 0
            fun addStock(p: Int) {
                if (!mask[p] && !supported[p]) {
                    supported[p] = true
                    queue[tail++] = p
                }
            }
            for (x in 0 until w) {
                addStock(x)
                addStock((h - 1) * w + x)
            }
            for (y in 0 until h) {
                addStock(y * w)
                addStock(y * w + w - 1)
            }
            var head = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                if (x > 0) addStock(p - 1)
                if (x + 1 < w) addStock(p + 1)
                if (p >= w) addStock(p - w)
                if (p + w < mask.size) addStock(p + w)
            }
            if (tail == 0) continue

            // One seed per small spatial cell at most. A seed must enter a substantial
            // painted region, so single-pixel contours and highlights remain intact.
            data class Seed(val x: Int, val y: Int, val vx: Float, val vy: Float)
            val cellSize = max(6, strokeWidthPixels * 4)
            val cellsWide = (w + cellSize - 1) / cellSize
            val selected = BooleanArray(cellsWide * ((h + cellSize - 1) / cellSize))
            val seeds = ArrayList<Seed>()
            for (y in 3 until h - 3) for (x in 3 until w - 3) {
                val p = y * w + x
                if (!mask[p]) continue
                val cell = (y / cellSize) * cellsWide + x / cellSize
                if (selected[cell]) continue
                val nx = (if (supported[p - 1]) 1 else 0) - (if (supported[p + 1]) 1 else 0)
                val ny = (if (supported[p - w]) 1 else 0) - (if (supported[p + w]) 1 else 0)
                if (nx == 0 && ny == 0) continue
                // Do not perforate a narrow stroke: require painted material continuing
                // into the region for several pixels along the inward normal.
                val dx = if (nx > 0) 1 else if (nx < 0) -1 else 0
                val dy = if (ny > 0) 1 else if (ny < 0) -1 else 0
                var length = 0
                for (k in 1..6) {
                    val q = (y + k * dy) * w + x + k * dx
                    if (q !in mask.indices || !mask[q]) break
                    length++
                }
                if (length < 5) continue
                selected[cell] = true
                val norm = sqrt((nx * nx + ny * ny).toFloat())
                seeds += Seed(x, y, nx / norm, ny / norm)
            }
            if (seeds.isEmpty()) continue
            // Reserve some budget per seed, distributing marks across the whole subject.
            // The deterministic sparse stride prevents a limit from affecting only the top.
            val expectedCost = max(1, strokeWidthPixels * 14)
            val stride = max(1, (seeds.size * expectedCost + budget - 1) / budget)
            val chosen = seeds.filterIndexed { i, _ -> i % stride == 0 }
            val lengthPerStroke = min(36, max(5, budget / max(1, chosen.size * strokeWidthPixels)))
            var removed = 0
            fun carve(x: Int, y: Int): Boolean {
                if (x !in 0 until w || y !in 0 until h) return false
                var did = false
                for (oy in -halfWidth..halfWidth) for (ox in -halfWidth..halfWidth) {
                    val xx = x + ox
                    val yy = y + oy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val p = yy * w + xx
                    if (mask[p] && removed < budget) {
                        mask[p] = false
                        supported[p] = true
                        removed++
                        did = true
                    }
                }
                return did
            }

            for (seed in chosen) {
                if (removed >= budget) break
                var px = seed.x.toFloat()
                var py = seed.y.toFloat()
                var vx = seed.vx
                var vy = seed.vy
                var lastX = seed.x
                var lastY = seed.y
                for (step in 0 until lengthPerStroke) {
                    val x = px.roundToInt()
                    val y = py.roundToInt()
                    if (x !in 2 until w - 2 || y !in 2 until h - 2) break
                    if (!mask[y * w + x] && step > 0) break
                    // Complete 4-connected stock even when rounding moves diagonally.
                    if (step > 0 && x != lastX && y != lastY) carve(x, lastY)
                    if (!carve(x, y) && step > 1) break
                    lastX = x
                    lastY = y

                    val p = y * w + x
                    val gx = luminance[p + 1] - luminance[p - 1]
                    val gy = luminance[p + w] - luminance[p - w]
                    val strength = sqrt((gx * gx + gy * gy).toFloat())
                    if (strength > 8f) {
                        // Choose the tangent's sign that turns the stroke least.
                        var tx = -gy / strength
                        var ty = gx / strength
                        if (tx * vx + ty * vy < 0f) {
                            tx = -tx
                            ty = -ty
                        }
                        // Start into the region; gradually flow with image contours.
                        val blend = min(0.55f, 0.12f + 0.02f * step)
                        val ux = (1f - blend) * vx + blend * tx
                        val uy = (1f - blend) * vy + blend * ty
                        val norm = sqrt(ux * ux + uy * uy)
                        if (norm > 0f) {
                            vx = ux / norm
                            vy = uy / norm
                        }
                    }
                    px += vx
                    py += vy
                }
            }
        }
        return Separation.fromMasks(w, h, original.sheets, masks, original.liftZones)
    }
}
