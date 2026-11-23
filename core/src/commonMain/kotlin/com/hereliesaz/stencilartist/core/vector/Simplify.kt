package com.hereliesaz.stencilartist.core.vector

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cleans traced outlines: Douglas-Peucker thinning of closed rings and removal of specks.
 *
 * Rings are simplified independently, so with a large tolerance a hole can come to touch its outer
 * ring. At the tolerances a stencil uses (about a pixel) that does not happen in practice.
 */
object Simplify {
    private const val COLLINEAR = 1e-9

    /**
     * Douglas-Peucker on a closed ring: no point of the original lies further than [tolerance]
     * (pixels) from the result. The ring is split at its first point and the point farthest from it,
     * and both halves are thinned, so there is no privileged seam. Points that are exactly in line with
     * their neighbours are always removed, even at tolerance 0. Returns null when the ring collapses
     * (fewer than 3 points, or no area left), so a caller drops it.
     */
    fun ring(ring: Ring, tolerance: Double): Ring? {
        require(tolerance >= 0.0 && tolerance.isFinite()) { "tolerance must be >= 0, got $tolerance" }
        val pts = ring.points
        val n = pts.size
        var far = 0
        var best = 0.0
        for (i in 1 until n) {
            val dx = pts[i].x - pts[0].x
            val dy = pts[i].y - pts[0].y
            val d = dx * dx + dy * dy
            if (d > best) {
                best = d
                far = i
            }
        }
        if (far == 0) return null
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        thin(pts, 0, far, tolerance, keep, n)
        thin(pts, far, n, tolerance, keep, n)
        val kept = ArrayList<Point>()
        for (i in 0 until n) if (keep[i]) kept.add(pts[i])
        val cleaned = dropCollinear(kept)
        if (cleaned.size < 3) return null
        val result = Ring(cleaned, ring.isHole)
        return if (result.area < 1e-9) null else result
    }

    /**
     * Thins the open chain pts[lo..hi], where index hi wraps to 0 when it equals [n]. Iterative, so a
     * long ring cannot exhaust the stack on any target.
     */
    private fun thin(pts: List<Point>, lo: Int, hi: Int, tol: Double, keep: BooleanArray, n: Int) {
        val stack = ArrayList<Int>()
        stack.add(lo)
        stack.add(hi)
        while (stack.isNotEmpty()) {
            val h = stack.removeAt(stack.size - 1)
            val l = stack.removeAt(stack.size - 1)
            if (h - l < 2) continue
            val a = pts[l]
            val b = pts[h % n]
            var worst = -1
            var worstDist = tol
            for (i in l + 1 until h) {
                val d = distanceToSegment(pts[i], a, b)
                if (d > worstDist) {
                    worstDist = d
                    worst = i
                }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack.add(l); stack.add(worst)
            stack.add(worst); stack.add(h)
        }
    }

    private fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y))
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = a.x + t * dx
        val cy = a.y + t * dy
        return sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
    }

    /** Whether b lies on the straight line a-c, between them (not a spike back on itself). */
    private fun collinear(a: Point, b: Point, c: Point): Boolean {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        val cross = abx * bcy - aby * bcx
        val scale = sqrt(abx * abx + aby * aby) * sqrt(bcx * bcx + bcy * bcy)
        return abs(cross) <= COLLINEAR * scale && abx * bcx + aby * bcy > 0.0
    }

    /** Removes points in line with their neighbours, including across the closing seam. */
    private fun dropCollinear(points: List<Point>): List<Point> {
        val result = ArrayList<Point>(points.size)
        for (p in points) {
            while (result.size >= 2 && collinear(result[result.size - 2], result[result.size - 1], p)) {
                result.removeAt(result.size - 1)
            }
            result.add(p)
        }
        val deque = ArrayDeque(result)
        var changed = true
        while (changed && deque.size >= 3) {
            changed = false
            if (collinear(deque[deque.size - 2], deque.last(), deque.first())) {
                deque.removeLast()
                changed = true
            }
            if (deque.size >= 3 && collinear(deque.last(), deque.first(), deque[1])) {
                deque.removeFirst()
                changed = true
            }
        }
        return deque.toList()
    }

    /** [ring] on every ring of [shape]. A collapsed outer drops the shape; a collapsed hole is filled in. */
    fun shape(shape: Shape, tolerance: Double): Shape? {
        val outer = ring(shape.outer, tolerance) ?: return null
        return Shape(outer, shape.holes.mapNotNull { ring(it, tolerance) })
    }

    /**
     * Removes specks and pinholes: shapes whose outer ring encloses less than [minArea] (px²) and holes
     * enclosing less than that. Applied to the exact traced rings, where area is a pixel count.
     */
    fun dropSmall(shapes: List<Shape>, minArea: Double): List<Shape> {
        require(minArea >= 0.0 && minArea.isFinite()) { "minArea must be >= 0, got $minArea" }
        return shapes.filter { it.outer.area >= minArea }
            .map { s -> if (s.holes.all { it.area >= minArea }) s else Shape(s.outer, s.holes.filter { it.area >= minArea }) }
    }

    /** [dropSmall] with [minArea], then [shape] with [tolerance], for a whole sheet. */
    fun shapes(shapes: List<Shape>, tolerance: Double, minArea: Double): List<Shape> =
        dropSmall(shapes, minArea).mapNotNull { shape(it, tolerance) }
}
