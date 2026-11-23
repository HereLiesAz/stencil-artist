package com.hereliesaz.stencilartist.core.vector

/**
 * Chaikin corner cutting. Each pass replaces every corner with two points a quarter of the way along
 * its neighbouring edges, so the staircase of a traced mask becomes a curve a cutter can follow. Rings
 * stay closed, a ring of n points becomes 2n, and the curve stays inside the original polygon, so
 * outer rings shrink and holes shrink slightly with each pass.
 */
object Smooth {
    /** The most passes allowed: each doubles the point count. */
    const val MAX_ITERATIONS = 6

    /** [ring] after [iterations] passes; 0 returns it unchanged. */
    fun ring(ring: Ring, iterations: Int): Ring {
        require(iterations in 0..MAX_ITERATIONS) { "smoothing must be 0..$MAX_ITERATIONS passes, got $iterations" }
        var pts = ring.points
        repeat(iterations) {
            val next = ArrayList<Point>(pts.size * 2)
            var prev = pts.last()
            for (p in pts) {
                next.add(Point(0.75 * prev.x + 0.25 * p.x, 0.75 * prev.y + 0.25 * p.y))
                next.add(Point(0.25 * prev.x + 0.75 * p.x, 0.25 * prev.y + 0.75 * p.y))
                prev = p
            }
            pts = next
        }
        return if (iterations == 0) ring else Ring(pts, ring.isHole)
    }

    /** [ring] on every ring of [shape]. */
    fun shape(shape: Shape, iterations: Int): Shape =
        Shape(ring(shape.outer, iterations), shape.holes.map { ring(it, iterations) })
}
