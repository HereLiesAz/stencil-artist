package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import kotlin.math.abs

/**
 * Clips closed rings to a rectangle, for the text formats that have no clip path of their own (the PDF
 * uses a real clip path instead).
 *
 * This is Sutherland-Hodgman. It is exact for convex rings. For a concave ring that leaves the
 * rectangle and comes back, the pieces inside are joined into ONE ring by edges that run along the
 * clip boundary, doubled back on themselves. The result is still a valid closed path and fills
 * correctly, but a cutter will trace those boundary edges twice. A caller that cares should tile along
 * a line that does not split such a shape, or accept the doubled edge on the mat's border.
 */
object Clip {
    /** [ring] clipped to [rect]. Fewer than 3 points back means nothing of it is inside. */
    fun ring(ring: List<Point>, rect: RectMm): List<Point> {
        var pts = ring
        pts = against(pts, { it.x >= rect.x0 }, { a, b -> Point(rect.x0, a.y + (rect.x0 - a.x) / (b.x - a.x) * (b.y - a.y)) })
        pts = against(pts, { it.x <= rect.x1 }, { a, b -> Point(rect.x1, a.y + (rect.x1 - a.x) / (b.x - a.x) * (b.y - a.y)) })
        pts = against(pts, { it.y >= rect.y0 }, { a, b -> Point(a.x + (rect.y0 - a.y) / (b.y - a.y) * (b.x - a.x), rect.y0) })
        pts = against(pts, { it.y <= rect.y1 }, { a, b -> Point(a.x + (rect.y1 - a.y) / (b.y - a.y) * (b.x - a.x), rect.y1) })
        return dedupe(pts)
    }

    private fun against(input: List<Point>, inside: (Point) -> Boolean, cross: (Point, Point) -> Point): List<Point> {
        if (input.isEmpty()) return input
        val out = ArrayList<Point>(input.size + 4)
        var prev = input.last()
        var prevIn = inside(prev)
        for (cur in input) {
            val curIn = inside(cur)
            if (curIn) {
                if (!prevIn) out.add(cross(prev, cur))
                out.add(cur)
            } else if (prevIn) {
                out.add(cross(prev, cur))
            }
            prev = cur
            prevIn = curIn
        }
        return out
    }

    private fun dedupe(points: List<Point>): List<Point> {
        val out = ArrayList<Point>(points.size)
        for (p in points) if (out.isEmpty() || !same(out.last(), p)) out.add(p)
        while (out.size > 1 && same(out.last(), out.first())) out.removeAt(out.size - 1)
        return out
    }

    private fun same(a: Point, b: Point): Boolean = abs(a.x - b.x) < 1e-9 && abs(a.y - b.y) < 1e-9
}
