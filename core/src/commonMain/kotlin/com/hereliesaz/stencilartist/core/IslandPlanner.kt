package com.hereliesaz.stencilartist.core

import kotlin.math.abs

/**
 * A physically safe island repair result. [unresolved] counts stock islands that still need
 * cross-layer reconstruction or an explicit artistic bridge; none are silently dropped.
 */
data class IslandPlan(val separation: Separation, val concealedBridges: Int, val unresolved: Int)

/**
 * Plans negative brushstroke bridges across paint openings. Only cuts that will be COMPLETELY
 * hidden by a later sheet are automatically changed: every pixel removed from an earlier
 * opening must already be covered by a later paint opening. This makes the painted composite
 * *exactly* unchanged. No island is ever "fixed" by deleting a visible feature.
 *
 * Paths use 4-connected stock, trace shortest supported routes, and break equal-distance ties
 * along the source's luminance-contour tangent when one is available. Width is the minimum
 * physical connection specified by the caller, not a cosmetic stroke thickness. The stencil
 * must be scaled before that pixel width can be treated as a cutting-material guarantee.
 *
 * A bridge that would be visible or would exceed the search budget remains unresolved and
 * must appear in the existing contextual cutting tips. Users never have to guess what vanished.
 */
object IslandPlanner {
    fun repair(
        original: Separation,
        source: RgbaImage? = null,
        minimumBridgePixels: Int = 1,
        maxPathPixels: Int = 128,
    ): IslandPlan {
        require(minimumBridgePixels >= 1 && maxPathPixels >= 1)
        require(source == null || (source.width == original.width && source.height == original.height))
        val w = original.width
        val h = original.height
        if (w <= 0 || h <= 0 || original.sheets.isEmpty()) return IslandPlan(original, 0, 0)
        // The separate-cut representation is intended for a practical painting stack. Avoid
        // materialising thousands of full-frame masks for huge palette separations.
        if (original.sheets.size > 16) return IslandPlan(original, 0, -1)
        val cuts = original.sheets.indices.map { original.mask(it) }
        val renderedBefore = original.render()
        var repaired = 0
        var unresolved = 0
        val n = w * h
        val offsets = intArrayOf(-1, 1, -w, w)
        val radius = minimumBridgePixels / 2

        for (sheet in cuts.indices) {
            val cut = cuts[sheet]
            // The material connected to the border remains in the stencil after cutting.
            val supported = BooleanArray(n)
            val queue = IntArray(n)
            var tail = 0
            fun support(pixel: Int) {
                if (!cut[pixel] && !supported[pixel]) {
                    supported[pixel] = true
                    queue[tail++] = pixel
                }
            }
            for (x in 0 until w) {
                support(x)
                support((h - 1) * w + x)
            }
            for (y in 0 until h) {
                support(y * w)
                support(y * w + w - 1)
            }
            var head = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                if (x > 0) support(p - 1)
                if (x + 1 < w) support(p + 1)
                if (p >= w) support(p - w)
                if (p + w < n) support(p + w)
            }
            val visitedIsland = BooleanArray(n)
            val visitedPath = IntArray(n)
            val parent = IntArray(n)
            val depth = IntArray(n)
            var generation = 0

            // A bridge is permitted only where *all* of its footprint is hidden by later paint.
            fun canBridge(pixel: Int): Boolean {
                val x = pixel % w
                val y = pixel / w
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) return false
                    val q = yy * w + xx
                    if (cut[q] && (sheet + 1 until cuts.size).none { cuts[it][q] }) return false
                }
                return true
            }
            fun tangentScore(at: Int, candidate: Int): Int {
                val pixels = source?.pixels ?: return 0
                val x = at % w
                val y = at / w
                if (x <= 0 || x + 1 >= w || y <= 0 || y + 1 >= h) return 0
                fun light(p: Int): Int {
                    val v = pixels[p]
                    return ((v shr 16 and 255) * 299 + (v shr 8 and 255) * 587 +
                        (v and 255) * 114) / 1000
                }
                val gx = light(at + 1) - light(at - 1)
                val gy = light(at + w) - light(at - w)
                val dx = (candidate % w) - x
                val dy = (candidate / w) - y
                // Low cost when the path follows the luminance contour.
                return abs(gx * dx + gy * dy)
            }
            fun neighbours(p: Int, visit: (Int) -> Unit) {
                val x = p % w
                if (x > 0) visit(p - 1)
                if (x + 1 < w) visit(p + 1)
                if (p >= w) visit(p - w)
                if (p + w < n) visit(p + w)
            }

            for (start in 0 until n) {
                if (cut[start] || supported[start] || visitedIsland[start]) continue
                head = 0
                tail = 0
                queue[tail++] = start
                visitedIsland[start] = true
                while (head < tail) {
                    val p = queue[head++]
                    neighbours(p) { q ->
                        if (!cut[q] && !supported[q] && !visitedIsland[q]) {
                            visitedIsland[q] = true
                            queue[tail++] = q
                        }
                    }
                }
                // A connected island is now in queue[0..tail); search only concealed paint
                // openings from its boundary to previously supported, uncut stock.
                generation++
                var searchHead = 0
                var searchTail = 0
                for (i in 0 until tail) {
                    neighbours(queue[i]) { q ->
                        if (cut[q] && visitedPath[q] != generation && canBridge(q)) {
                            visitedPath[q] = generation
                            parent[q] = -1
                            depth[q] = 1
                            queue[searchTail++] = q
                        }
                    }
                }
                var destination = -1
                while (searchHead < searchTail && destination < 0) {
                    val p = queue[searchHead++]
                    if (depth[p] > maxPathPixels) break
                    val options = ArrayList<Int>(4)
                    neighbours(p) { q -> options.add(q) }
                    options.sortBy { tangentScore(p, it) }
                    for (q in options) {
                        if (!cut[q] && supported[q]) {
                            destination = p
                            break
                        }
                        if (cut[q] && visitedPath[q] != generation && depth[p] < maxPathPixels && canBridge(q)) {
                            visitedPath[q] = generation
                            parent[q] = p
                            depth[q] = depth[p] + 1
                            queue[searchTail++] = q
                        }
                    }
                }
                if (destination < 0) {
                    unresolved++
                    continue
                }
                var p = destination
                while (p >= 0) {
                    val x = p % w
                    val y = p / w
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        val q = (y + dy) * w + x + dx
                        cut[q] = false
                        supported[q] = true
                    }
                    p = parent[p]
                }
                // The original disconnected stock is connected through the negative stroke.
                // No visible pixel was changed: the bridge footprint was fully later-painted.
                for (i in 0 until tail) supported[queue[i]] = true
                repaired++
            }
        }

        val after = Separation.fromMasks(w, h, original.sheets, cuts, original.liftZones)
        check(renderedBefore.contentEquals(after.render())) {
            "Island planning changed finished paint; refusing to replace the original artwork"
        }
        return IslandPlan(after, repaired, unresolved)
    }
}
