package com.hereliesaz.stencilartist.core.vector

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TracerTest {
    private fun corners(r: Ring) = r.points.map { it.x.toInt() to it.y.toInt() }

    @Test
    fun aSinglePixelIsAUnitSquareWithOuterWinding() {
        val shapes = traceRows("#")
        assertEquals(1, shapes.size)
        val s = shapes[0]
        assertEquals(listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1), corners(s.outer))
        assertTrue(s.holes.isEmpty())
        assertEquals(1.0, s.outer.signedArea, "outer rings wind positive in y-down space")
        assertTrue(!s.outer.isHole)
    }

    @Test
    fun aRectangleKeepsOnlyItsFourCorners() {
        val shapes = traceRows("....", ".###", ".###", "....")
        assertEquals(1, shapes.size)
        assertEquals(listOf(1 to 1, 4 to 1, 4 to 3, 1 to 3), corners(shapes[0].outer))
        assertEquals(6.0, shapes[0].outer.area)
    }

    @Test
    fun aRectangleWithAHoleHasOneNegativelyWoundHole() {
        val shapes = traceRows("#####", "#...#", "#...#", "#...#", "#####")
        assertEquals(1, shapes.size)
        val s = shapes[0]
        assertEquals(25.0, s.outer.signedArea)
        assertEquals(1, s.holes.size)
        assertEquals(-9.0, s.holes[0].signedArea, "holes wind negative")
        assertTrue(s.holes[0].isHole)
        assertEquals(16.0, s.area)
        assertEquals(setOf(1 to 1, 4 to 1, 4 to 4, 1 to 4), corners(s.holes[0]).toSet())
    }

    @Test
    fun anIslandInsideAHoleIsItsOwnShape() {
        val shapes = traceRows(
            "#######",
            "#.....#",
            "#.....#",
            "#..#..#",
            "#.....#",
            "#.....#",
            "#######",
        )
        assertEquals(2, shapes.size)
        assertEquals(49.0, shapes[0].outer.area)
        assertEquals(1, shapes[0].holes.size)
        assertEquals(25.0, shapes[0].holes[0].area)
        assertEquals(1.0, shapes[1].outer.area)
        assertTrue(shapes[1].holes.isEmpty())
    }

    @Test
    fun nestedIslandsAndTheirHolesGoToTheRightOwners() {
        val shapes = traceRows(
            "###########",
            "#.........#",
            "#.#######.#",
            "#.#.....#.#",
            "#.#.###.#.#",
            "#.#.#.#.#.#",
            "#.#.###.#.#",
            "#.#.....#.#",
            "#.#######.#",
            "#.........#",
            "###########",
        )
        assertEquals(3, shapes.size)
        assertEquals(listOf(121.0, 49.0, 9.0), shapes.map { it.outer.area })
        assertEquals(listOf(81.0, 25.0, 1.0), shapes.map { s -> s.holes.single().area })
    }

    @Test
    fun twoDiagonalPixelsAreTwoShapes() {
        assertEquals(2, traceRows("#.", ".#").size)
        assertEquals(2, traceRows(".#", "#.").size)
    }

    @Test
    fun backgroundIsEightConnectedSoADiagonalGapIsNotAHole() {
        val shapes = traceRows(".#.", "#.#", ".#.")
        assertEquals(4, shapes.size)
        assertTrue(shapes.all { it.holes.isEmpty() && it.outer.area == 1.0 })
    }

    @Test
    fun aRingClosedOnlyDiagonallyIsNotAHoleEither() {
        // The notch at (1,1) meets the empty pixel (2,2) only at a corner, and (2,2) is open to the edge.
        val shapes = traceRows("###.", "#.#.", "##.#")
        assertEquals(2, shapes.size, "the pixel at the far right is cut off at a corner")
        assertTrue(shapes.all { it.holes.isEmpty() })
        // Closing the diagonal gap off makes the notch a real hole.
        assertEquals(1, traceRows("###.", "#.#.", "####")[0].holes.size)
    }

    @Test
    fun aMaskTouchingTheBorderIsClosedAtTheImageEdge() {
        val full = traceRows("###", "###", "###")
        assertEquals(1, full.size)
        assertEquals(listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3), corners(full[0].outer))
        val l = traceRows("#..", "#..", "###")
        assertEquals(listOf(0 to 0, 1 to 0, 1 to 2, 3 to 2, 3 to 3, 0 to 3), corners(l[0].outer))
    }

    @Test
    fun anEmptyMaskHasNoShapes() {
        assertTrue(traceRows("...", "...").isEmpty())
    }

    @Test
    fun theTraceRejectsAMaskOfTheWrongSize() {
        assertFailsWith<IllegalArgumentException> { Tracer.trace(BooleanArray(5), 2, 3) }
        assertFailsWith<IllegalArgumentException> { Tracer.trace(BooleanArray(0), 0, 0) }
    }

    @Test
    fun ringsRoundTripThroughEvenOddRasterisationOnIrregularMasks() {
        val sizes = listOf(Triple(23, 17, 48), Triple(31, 29, 55), Triple(40, 25, 35), Triple(19, 33, 62), Triple(50, 50, 50), Triple(7, 41, 45))
        for ((i, spec) in sizes.withIndex()) {
            val (w, h, density) = spec
            for (seed in 1L..4L) {
                val mask = randomMask(seed * 1000 + i, w, h, density)
                val shapes = Tracer.trace(mask, w, h)
                assertContentEquals(mask, rasterize(shapes, w, h), "mask ${w}x$h seed $seed density $density")

                // Winding: material is on the right everywhere, so signed areas add up to the pixel count.
                val total = shapes.sumOf { s -> s.outer.signedArea + s.holes.sumOf { it.signedArea } }
                assertEquals(mask.count { it }.toDouble(), total)
                for (s in shapes) {
                    assertTrue(s.outer.signedArea > 0)
                    assertTrue(s.holes.all { it.signedArea < 0 })
                    // Every vertex is an integer corner and no two consecutive points repeat.
                    for (r in s.rings) {
                        assertTrue(r.points.all { it.x == kotlin.math.floor(it.x) && it.y == kotlin.math.floor(it.y) })
                        for (k in r.points.indices) assertTrue(r.points[k] != r.points[(k + 1) % r.points.size])
                    }
                }
            }
        }
    }

    @Test
    fun anInvertedMaskSharesItsEdgesExactlyWithTheOriginal() {
        val w = 30
        val h = 22
        val mask = randomMask(77, w, h, 50)
        val inverse = BooleanArray(mask.size) { !mask[it] }
        fun unitEdges(shapes: List<Shape>): Set<String> {
            val edges = HashSet<String>()
            for (s in shapes) for (r in s.rings) {
                for (k in r.points.indices) {
                    val a = r.points[k]
                    val b = r.points[(k + 1) % r.points.size]
                    // A ring edge is a straight axis-aligned run; split it into unit cracks.
                    val steps = (abs(b.x - a.x) + abs(b.y - a.y)).toInt()
                    val dx = (b.x - a.x) / steps
                    val dy = (b.y - a.y) / steps
                    for (t in 0 until steps) {
                        val x0 = a.x + dx * t
                        val y0 = a.y + dy * t
                        val x1 = x0 + dx
                        val y1 = y0 + dy
                        val first = if (x0 < x1 || y0 < y1) "$x0,$y0-$x1,$y1" else "$x1,$y1-$x0,$y0"
                        edges.add(first)
                    }
                }
            }
            return edges
        }
        fun onBorder(e: String): Boolean {
            val (a, b) = e.split("-").map { p -> p.split(",").map { it.toDouble() } }
            return (a[0] == b[0] && (a[0] == 0.0 || a[0] == w.toDouble())) || (a[1] == b[1] && (a[1] == 0.0 || a[1] == h.toDouble()))
        }
        val a = unitEdges(Tracer.trace(mask, w, h)).filterNot { onBorder(it) }.toSet()
        val b = unitEdges(Tracer.trace(inverse, w, h)).filterNot { onBorder(it) }.toSet()
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }
}
