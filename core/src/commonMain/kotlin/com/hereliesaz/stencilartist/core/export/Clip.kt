package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.Ring
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Clips the CUT REGION of a shape (outer ring minus holes) to a rectangle, for vector cutters.
 *
 * A concave polygon can intersect one tile in several disconnected pieces. Sutherland-Hodgman
 * joins those pieces by overlapping/reversed edges on the tile border; plotters and lasers then cut
 * those edges twice. Instead, retain original edges inside the tile and add each rectangle-border
 * interval only when the material immediately INSIDE that interval belongs to the shape. Directed
 * segments form separate closed loops, with no artificial bridge or retraced border.
 *
 * Each original polygon is assumed simple, with holes contained in its outer ring (as produced by
 * the tracer). Output may contain multiple outer loops and hole loops. All output coordinates stay
 * in the ORIGINAL drawing frame; exporters translate a tile to their own origin.
 */
object Clip {
    private const val EPS = 1e-9
    private data class Edge(val start: Point, val end: Point)

    /**
     * Clip an outer ring and all its [holes] as one cut region. Rings are oriented with cut
     * material to the right of travel (positive area for outer loops, negative for holes).
     * Preserves input points and ordering when the entire shape fits in [rect].
     */
    fun shape(outer: List<Point>, holes: List<List<Point>>, rect: RectMm): List<List<Point>> {
        require(outer.size >= 3 && holes.all { it.size >= 3 }) { "each ring needs three points" }
        require(rect.width > 0.0 && rect.height > 0.0) { "clip rectangle needs positive area" }
        val source = listOf(outer) + holes
        if (source.all { polygon -> polygon.all { p -> within(p, rect) } }) return source

        val outerRing = Ring(outer, false)
        val holeRings = holes.map { Ring(it, true) }
        val edges = ArrayList<Edge>()
        for ((index, polygon) in source.withIndex()) {
            // Tracer uses clockwise outer and counter-clockwise hole winding in y-down space.
            // Normalize externally supplied shapes too, before assembling directed edges.
            val expectedPositive = index == 0
            val oriented = if ((area(polygon) > 0.0) == expectedPositive) polygon else polygon.asReversed()
            var previous = oriented.last()
            for (point in oriented) {
                val clipped = clipSegment(previous, point, rect)
                if (clipped != null && !onSameSide(clipped.start, clipped.end, rect)) edges.add(clipped)
                previous = point
            }
        }

        // Clockwise walk around the rectangle: top -> right -> bottom -> left. For every
        // subinterval, sample immediately INSIDE the tile, not directly on a polygon border.
        // Holes are subtracted here, so a tile swallowed by a hole yields NO cutting paths.
        val corners = listOf(
            Point(rect.x0, rect.y0), Point(rect.x1, rect.y0),
            Point(rect.x1, rect.y1), Point(rect.x0, rect.y1),
        )
        val boundaries = Array(4) { side -> mutableListOf(corners[side], corners[(side + 1) % 4]) }
        for (edge in edges) for (point in listOf(edge.start, edge.end)) {
            if (point.y == rect.y0) boundaries[0].add(point)
            if (point.x == rect.x1) boundaries[1].add(point)
            if (point.y == rect.y1) boundaries[2].add(point)
            if (point.x == rect.x0) boundaries[3].add(point)
        }
        for (side in 0..3) {
            val sorted = boundaries[side].distinct().sortedBy { p ->
                when (side) {
                    0 -> p.x
                    1 -> p.y
                    2 -> -p.x
                    else -> -p.y
                }
            }
            for (i in 1 until sorted.size) {
                val start = sorted[i - 1]
                val end = sorted[i]
                if (same(start, end)) continue
                val distance = abs(end.x - start.x) + abs(end.y - start.y)
                val probe = minOf(1e-6, distance / 8.0, rect.width / 8.0, rect.height / 8.0)
                val mx = (start.x + end.x) / 2.0
                val my = (start.y + end.y) / 2.0
                val interior = when (side) {
                    0 -> Point(mx, rect.y0 + probe)
                    1 -> Point(rect.x1 - probe, my)
                    2 -> Point(mx, rect.y1 - probe)
                    else -> Point(rect.x0 + probe, my)
                }
                if (outerRing.contains(interior.x, interior.y) &&
                    holeRings.none { it.contains(interior.x, interior.y) }) {
                    edges.add(Edge(start, end))
                }
            }
        }
        if (edges.isEmpty()) return emptyList()

        val outgoing = HashMap<Point, MutableList<Int>>()
        edges.forEachIndexed { index, edge -> outgoing.getOrPut(edge.start) { ArrayList(1) }.add(index) }
        val visited = BooleanArray(edges.size)
        val loops = ArrayList<List<Point>>()
        for (seed in edges.indices) {
            if (visited[seed]) continue
            val first = edges[seed].start
            val points = ArrayList<Point>()
            var next = seed
            while (true) {
                check(!visited[next]) { "clip created an overlapping or unclosed cutting edge" }
                val edge = edges[next]
                visited[next] = true
                points.add(edge.start)
                if (edge.end == first) break
                val candidates = outgoing[edge.end]?.filterNot { visited[it] }.orEmpty()
                check(candidates.isNotEmpty()) { "clip created an open cutting path" }
                // At a point touch, continue along the rightmost path to keep cut material
                // on the right rather than welding two otherwise separate components together.
                next = candidates.minBy { candidate ->
                    val other = edges[candidate]
                    val ax = edge.end.x - edge.start.x
                    val ay = edge.end.y - edge.start.y
                    val bx = other.end.x - other.start.x
                    val by = other.end.y - other.start.y
                    val radians = atan2(ax * by - ay * bx, ax * bx + ay * by)
                    if (radians < 0.0) radians + 2.0 * kotlin.math.PI else radians
                }
            }
            val clean = dedupe(points)
            if (clean.size >= 3 && abs(area(clean)) > EPS) loops.add(clean)
        }
        check(visited.all { it }) { "clip left a cutting edge unused" }
        // Keep outer loops ahead of holes in SVG and DXF even when a tile splits one outer ring.
        return loops.sortedByDescending { area(it) }
    }

    /**
     * Legacy single-polygon clipping for callers that know the intersection is connected.
     * Multi-component cut geometry must use [shape]; it cannot be honestly returned as one ring.
     */
    fun ring(ring: List<Point>, rect: RectMm): List<Point> {
        val loops = shape(ring, emptyList(), rect)
        require(loops.size <= 1) { "ring meets tile in disconnected pieces; use Clip.shape" }
        return loops.firstOrNull().orEmpty()
    }

    private fun clipSegment(a: Point, b: Point, rect: RectMm): Edge? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        var from = 0.0
        var to = 1.0
        fun keep(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val t = q / p
            if (p < 0.0) {
                if (t > to) return false
                if (t > from) from = t
            } else {
                if (t < from) return false
                if (t < to) to = t
            }
            return true
        }
        if (!keep(-dx, a.x - rect.x0) || !keep(dx, rect.x1 - a.x) ||
            !keep(-dy, a.y - rect.y0) || !keep(dy, rect.y1 - a.y) ||
            to - from <= EPS) return null

        fun snapped(t: Double): Point {
            val x = a.x + t * dx
            val y = a.y + t * dy
            return Point(
                when {
                    abs(x - rect.x0) < EPS -> rect.x0
                    abs(x - rect.x1) < EPS -> rect.x1
                    else -> x
                },
                when {
                    abs(y - rect.y0) < EPS -> rect.y0
                    abs(y - rect.y1) < EPS -> rect.y1
                    else -> y
                },
            )
        }
        return Edge(snapped(from), snapped(to))
    }

    private fun onSameSide(a: Point, b: Point, rect: RectMm): Boolean =
        (a.x == b.x && (a.x == rect.x0 || a.x == rect.x1)) ||
            (a.y == b.y && (a.y == rect.y0 || a.y == rect.y1))

    private fun within(p: Point, rect: RectMm): Boolean =
        p.x >= rect.x0 && p.x <= rect.x1 && p.y >= rect.y0 && p.y <= rect.y1

    private fun dedupe(points: List<Point>): List<Point> {
        val out = ArrayList<Point>(points.size)
        for (point in points) if (out.isEmpty() || !same(out.last(), point)) out.add(point)
        while (out.size > 1 && same(out.first(), out.last())) out.removeAt(out.lastIndex)
        return out
    }

    private fun same(a: Point, b: Point): Boolean =
        abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS

    private fun area(points: List<Point>): Double {
        var sum = 0.0
        var previous = points.last()
        for (point in points) {
            sum += previous.x * point.y - point.x * previous.y
            previous = point
        }
        return sum / 2.0
    }
}
