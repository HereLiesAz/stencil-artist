package com.hereliesaz.stencilartist.core.vector

import kotlin.math.abs

/** A position in the plane. In a [Drawing] it is measured in source pixels, y growing downward. */
data class Point(val x: Double, val y: Double)

/**
 * A closed polygon. The last point is NOT repeated: the edge from the final point back to the first
 * is implied. A ring has at least three points.
 *
 * Winding convention, in y-down coordinates (the way images are addressed): an outer ring has a
 * POSITIVE [signedArea], which is clockwise as it appears on screen; a hole has a NEGATIVE one,
 * counter-clockwise on screen. Material (the part that is cut open) is always on the right-hand side
 * of travel, so a consumer can fill with either rule and still get holes right. [isHole] says which
 * kind a ring is; it always agrees with the sign of [signedArea] for rings made by [Tracer].
 */
data class Ring(val points: List<Point>, val isHole: Boolean) {
    init {
        require(points.size >= 3) { "a ring needs at least 3 points, got ${points.size}" }
    }

    /** Shoelace area with its sign: positive for the outer winding, negative for the hole winding (y-down). */
    val signedArea: Double
        get() {
            var sum = 0.0
            var prev = points.last()
            for (p in points) {
                sum += prev.x * p.y - p.x * prev.y
                prev = p
            }
            return sum / 2.0
        }

    /** The enclosed area, always positive. */
    val area: Double get() = abs(signedArea)

    /** Whether ([x], [y]) is inside the ring by the even-odd rule. A point on the boundary may go either way. */
    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        var prev = points.last()
        for (p in points) {
            if ((p.y > y) != (prev.y > y) && x < (prev.x - p.x) * (y - p.y) / (prev.y - p.y) + p.x) inside = !inside
            prev = p
        }
        return inside
    }
}

/**
 * One connected piece of cut material: an [outer] ring and the [holes] inside it. A shape that sits
 * inside another shape's hole (an island) is its own Shape, never a hole of anything.
 */
data class Shape(val outer: Ring, val holes: List<Ring> = emptyList()) {
    init {
        require(!outer.isHole) { "a shape's outer ring must not be a hole" }
        require(holes.all { it.isHole }) { "a shape's holes must all be hole rings" }
    }

    /** Every ring, outer first. */
    val rings: List<Ring> get() = listOf(outer) + holes

    /** Outer area minus the holes. */
    val area: Double get() = outer.area - holes.sumOf { it.area }
}

/** The shapes of one sheet, in that sheet's [color] (straight-alpha ARGB, alpha preserved). */
data class SheetOutline(val color: Int, val shapes: List<Shape>)

/**
 * A stencil as vector outlines. [width] and [height] are the source image in pixels, which is the unit
 * of every point; the exporters map pixels to millimetres. [sheets] are in paint order.
 */
data class Drawing(val width: Double, val height: Double, val sheets: List<SheetOutline>) {
    init {
        require(width > 0.0 && width.isFinite()) { "drawing width must be positive, got $width" }
        require(height > 0.0 && height.isFinite()) { "drawing height must be positive, got $height" }
    }
}
