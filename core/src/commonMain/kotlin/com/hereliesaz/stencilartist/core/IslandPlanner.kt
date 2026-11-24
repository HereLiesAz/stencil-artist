package com.hereliesaz.stencilartist.core

import kotlin.math.abs

/**
 * A physically safe island repair result. [unresolved] counts stock islands that still need
 * cross-layer reconstruction or an explicit artistic bridge; none are silently dropped.
 */
data class IslandPlan(
    val separation: Separation,
    val concealedBridges: Int,
    val unresolved: Int,
    val skipped: Boolean = false,
    /** Islands removed from an earlier cut and exactly repainted by existing later cuts. */
    val reconstructedIslands: Int = 0,
    /** Islands repaired with small later-layer source-matched corrective marks. */
    val sourceGuidedIslands: Int = 0,
)

/**
 * Plans islands across the entire painting order. An unsupported stock island can be
 * removed by opening the earlier stencil over it if EVERY pixel is already painted by
 * a later sheet. Black may therefore be sprayed beneath a white detail, or beneath a
 * detail reconstructed jointly by grey and white. Otherwise, a negative brushstroke
 * bridge is allowed only where its entire footprint is concealed by later paint.
 * Both strategies keep the finished composite exactly unchanged. When a processed
 * source is available, a small remaining gap may instead be filled with later-layer
 * paint that is strongly supported by the source, if the new opening does not create
 * a detached stock island in any later sheet. All other exposed details remain intact.
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
    /**
     * Border-supported retained stencil stock. The same four-connected rule is used
     * both to find unsupported islands and to reject new ones in correction layers.
     */
    private fun supportedStock(cut: BooleanArray, w: Int, h: Int): BooleanArray {
        val n = w * h
        val supported = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun visit(pixel: Int) {
            if (!cut[pixel] && !supported[pixel]) {
                supported[pixel] = true
                queue[tail++] = pixel
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
            val p = queue[head++]
            val x = p % w
            if (x > 0) visit(p - 1)
            if (x + 1 < w) visit(p + 1)
            if (p >= w) visit(p - w)
            if (p + w < n) visit(p + w)
        }
        return supported
    }

    fun repair(
        original: Separation,
        source: RgbaImage? = null,
        minimumBridgePixels: Int = 1,
        maxPathPixels: Int = 128,
        maxCorrectivePaintPixels: Int = 64,
    ): IslandPlan {
        require(minimumBridgePixels >= 1 && maxPathPixels >= 1 && maxCorrectivePaintPixels >= 0)
        require(source == null || (source.width == original.width && source.height == original.height))
        val w = original.width
        val h = original.height
        if (w <= 0 || h <= 0 || original.sheets.isEmpty()) return IslandPlan(original, 0, 0)
        // The separate-cut representation is intended for a practical painting stack. Avoid
        // materialising thousands of full-frame masks for huge palette separations.
        if (original.sheets.size > 16) return IslandPlan(original, 0, 0, skipped = true)
        val cuts = original.sheets.indices.map { original.mask(it) }
        val renderedBefore = original.render()
        var repaired = 0
        var reconstructed = 0
        var sourceGuided = 0
        var unresolved = 0
        val n = w * h
        val correctedPixels = BooleanArray(n)
        // Limit the work spent validating geometry for source-guided corrections.
        val maxSourceGuidedIslands = 16
        val maxSourceErrorSquared = 3 * 36 * 36
        fun colourError(a: Int, b: Int): Int {
            val dr = (a shr 16 and 255) - (b shr 16 and 255)
            val dg = (a shr 8 and 255) - (b shr 8 and 255)
            val db = (a and 255) - (b and 255)
            return dr * dr + dg * dg + db * db
        }
        val radius = minimumBridgePixels / 2

        for (sheet in cuts.indices) {
            val cut = cuts[sheet]
            // The material connected to the border remains in the stencil after cutting.
            val supported = supportedStock(cut, w, h)
            val queue = IntArray(n)
            var tail = 0
            var head = 0
            val visitedIsland = BooleanArray(n)
            val visitedPath = IntArray(n)
            val pathQueue = IntArray(n)
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
                // Painting order can eliminate this island entirely: open the earlier
                // stencil over its retained stock, then let existing later cuts restore
                // each pixel's final grey or white (or other later-layer) colour.
                // Only do this when every pixel will be restored. No new later cut is
                // invented, and no previously transparent or dark detail is destroyed.
                val restoredLater = (0 until tail).all { i ->
                    val pixel = queue[i]
                    (sheet + 1 until cuts.size).any { later -> cuts[later][pixel] }
                }
                if (restoredLater) {
                    for (i in 0 until tail) cut[queue[i]] = true
                    reconstructed++
                    continue
                }
                // Prefer a completely concealed negative-stroke bridge over a
                // source-guided correction, because the bridge changes no visible paint.
                generation++
                var searchHead = 0
                var searchTail = 0
                for (i in 0 until tail) {
                    neighbours(queue[i]) { q ->
                        if (cut[q] && visitedPath[q] != generation && canBridge(q)) {
                            visitedPath[q] = generation
                            parent[q] = -1
                            depth[q] = 1
                            pathQueue[searchTail++] = q
                        }
                    }
                }
                var destination = -1
                while (searchHead < searchTail && destination < 0) {
                    val p = pathQueue[searchHead++]
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
                            pathQueue[searchTail++] = q
                        }
                    }
                }
                if (destination < 0) {
                    // If existing later cuts and concealed bridges cannot restore
                    // the island, try a limited source-guided correction. Every missing pixel must closely match
                    // an opaque later paint, and that paint must improve the source
                    // colour match over the current composite. A dark feature, uncertain
                    // detail, or transparent source pixel is never repainted on a guess.
                    if (source != null && sourceGuided < maxSourceGuidedIslands &&
                        maxCorrectivePaintPixels > 0 && sheet + 1 < cuts.size) {
                        val targets = IntArray(tail) { -1 }
                        var missing = 0
                        var viable = true
                        for (i in 0 until tail) {
                            val pixel = queue[i]
                            if ((sheet + 1 until cuts.size).any { later -> cuts[later][pixel] }) continue
                            if (++missing > maxCorrectivePaintPixels) {
                                viable = false
                                break
                            }
                            val sampled = source.pixels[pixel]
                            if (sampled ushr 24 < 224) {
                                viable = false
                                break
                            }
                            val currentPaint = renderedBefore[pixel].let {
                                if (it ushr 24 == 0) original.sheets[sheet].color else it
                            }
                            val oldError = colourError(sampled, currentPaint)
                            var bestError = maxSourceErrorSquared + 1
                            var bestSheet = -1
                            for (later in sheet + 1 until cuts.size) {
                                val colour = original.sheets[later].color
                                if (colour ushr 24 != 255) continue
                                val error = colourError(sampled, colour)
                                if (error < bestError) {
                                    bestError = error
                                    bestSheet = later
                                }
                            }
                            if (bestSheet < 0 || bestError > maxSourceErrorSquared ||
                                bestError >= oldError) {
                                viable = false
                                break
                            }
                            targets[i] = bestSheet
                        }
                        if (viable && missing > 0) {
                            val changedSheets = targets.filter { it >= 0 }.distinct()
                            val originalSupport = changedSheets.associateWith { later ->
                                supportedStock(cuts[later], w, h)
                            }
                            for (i in 0 until tail) if (targets[i] >= 0) {
                                cuts[targets[i]][queue[i]] = true
                            }
                            // A later paint stroke may close a cut ring and detach
                            // previously supported stock. Reject the whole correction
                            // instead of exchanging one island for another.
                            val safe = changedSheets.all { later ->
                                val afterSupport = supportedStock(cuts[later], w, h)
                                val beforeSupport = originalSupport.getValue(later)
                                (0 until n).none { pixel ->
                                    beforeSupport[pixel] && !cuts[later][pixel] && !afterSupport[pixel]
                                }
                            }
                            if (safe) {
                                for (i in 0 until tail) {
                                    cut[queue[i]] = true
                                    if (targets[i] >= 0) correctedPixels[queue[i]] = true
                                }
                                sourceGuided++
                                continue
                            }
                            for (i in 0 until tail) if (targets[i] >= 0) {
                                cuts[targets[i]][queue[i]] = false
                            }
                        }
                    }
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
        val renderedAfter = after.render()
        for (pixel in 0 until n) {
            if (renderedBefore[pixel] == renderedAfter[pixel]) continue
            check(correctedPixels[pixel] && source != null &&
                colourError(source.pixels[pixel], renderedAfter[pixel]) <= maxSourceErrorSquared) {
                "Island planning changed an unapproved detail; refusing the repair"
            }
        }
        return IslandPlan(after, repaired, unresolved,
            reconstructedIslands = reconstructed, sourceGuidedIslands = sourceGuided)
    }
}
