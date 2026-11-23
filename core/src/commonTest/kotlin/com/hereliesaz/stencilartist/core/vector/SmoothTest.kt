package com.hereliesaz.stencilartist.core.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmoothTest {
    private val sq = square(0.0, 0.0, 4.0, 4.0)

    @Test
    fun oneChaikinPassCutsEveryCornerOfASquare() {
        val out = Smooth.ring(sq, 1)
        val expected = setOf(
            Point(1.0, 0.0), Point(3.0, 0.0), Point(4.0, 1.0), Point(4.0, 3.0),
            Point(3.0, 4.0), Point(1.0, 4.0), Point(0.0, 3.0), Point(0.0, 1.0),
        )
        assertEquals(expected, out.points.toSet())
        assertEquals(8, out.points.size)
        assertEquals(14.0, out.area)
    }

    @Test
    fun eachPassDoublesThePointsAndZeroPassesChangeNothing() {
        assertEquals(sq, Smooth.ring(sq, 0))
        var n = 4
        for (it in 1..4) {
            n *= 2
            assertEquals(n, Smooth.ring(sq, it).points.size)
        }
    }

    @Test
    fun smoothedRingsStayClosedInsideTheOriginalAndKeepTheirWinding() {
        val shapes = Tracer.trace(randomMask(5, 30, 20, 50), 30, 20)
        for (s in shapes) for (r in s.rings) {
            val out = Smooth.ring(r, 2)
            assertEquals(r.isHole, out.isHole)
            assertTrue((out.signedArea > 0) == (r.signedArea > 0))
            assertTrue(out.area < r.area, "corner cutting only removes area")
            assertTrue(out.points.first() != out.points.last(), "no repeated end point")
            val minX = r.points.minOf { it.x }
            val maxX = r.points.maxOf { it.x }
            val minY = r.points.minOf { it.y }
            val maxY = r.points.maxOf { it.y }
            assertTrue(out.points.all { it.x in minX..maxX && it.y in minY..maxY })
        }
    }

    @Test
    fun shapesSmoothAllTheirRings() {
        val s = Shape(square(0.0, 0.0, 10.0, 10.0), listOf(square(3.0, 3.0, 7.0, 7.0, hole = true)))
        val out = Smooth.shape(s, 1)
        assertEquals(8, out.outer.points.size)
        assertEquals(8, out.holes[0].points.size)
        assertTrue(out.holes[0].isHole)
    }

    @Test
    fun theNumberOfPassesIsBounded() {
        assertFailsWith<IllegalArgumentException> { Smooth.ring(sq, -1) }
        assertFailsWith<IllegalArgumentException> { Smooth.ring(sq, Smooth.MAX_ITERATIONS + 1) }
    }
}
