package com.hereliesaz.stencilartist.core.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimplifyTest {
    private fun pts(vararg xy: Double) = (xy.indices step 2).map { Point(xy[it], xy[it + 1]) }

    @Test
    fun collinearPointsAreRemoved() {
        val ring = Ring(pts(0.0, 0.0, 5.0, 0.0, 10.0, 0.0, 10.0, 5.0, 10.0, 10.0, 5.0, 10.0, 0.0, 10.0, 0.0, 5.0), false)
        val s = assertNotNull(Simplify.ring(ring, 0.0))
        assertEquals(4, s.points.size)
        assertEquals(100.0, s.area)
    }

    @Test
    fun aCollinearPointAtTheSeamIsRemovedToo() {
        // The first point sits mid-edge; a naive split at index 0 would keep it.
        val ring = Ring(pts(5.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0), false)
        val s = assertNotNull(Simplify.ring(ring, 0.0))
        assertEquals(4, s.points.size)
        assertEquals(100.0, s.area)
    }

    @Test
    fun aSquareStaysASquareAtAnyReasonableTolerance() {
        val ring = square(2.0, 3.0, 12.0, 13.0)
        for (tol in listOf(0.0, 0.25, 0.75, 2.0)) {
            val s = assertNotNull(Simplify.ring(ring, tol))
            assertEquals(ring.points.toSet(), s.points.toSet(), "tolerance $tol")
        }
    }

    @Test
    fun aStaircaseCollapsesToItsDiagonal() {
        val mask = traceRows(
            "#.......",
            "##......",
            "###.....",
            "####....",
            "#####...",
            "######..",
            "#######.",
            "########",
        )
        val raw = mask[0].outer
        assertTrue(raw.points.size > 16)
        val s = assertNotNull(Simplify.ring(raw, 0.75))
        assertEquals(3, s.points.size, "a triangle")
    }

    private fun circle(n: Int, wobble: Double): Ring = Ring(
        (0 until n).map {
            val a = 2 * PI * it / n
            val r = 50.0 + wobble * sin(a * 7) + wobble * 0.5 * cos(a * 19)
            Point(r * cos(a), r * sin(a))
        },
        false,
    )

    @Test
    fun morePermissiveTolerancesNeverKeepMorePoints() {
        val ring = circle(400, 2.0)
        var previous = Int.MAX_VALUE
        for (tol in listOf(0.0, 0.05, 0.2, 0.5, 1.0, 2.0, 5.0)) {
            val s = assertNotNull(Simplify.ring(ring, tol))
            assertTrue(s.points.size <= previous, "tolerance $tol kept ${s.points.size} after $previous")
            previous = s.points.size
        }
        assertTrue(previous < 40)
    }

    private fun distToPolygon(p: Point, r: Ring): Double {
        var best = Double.MAX_VALUE
        var prev = r.points.last()
        for (q in r.points) {
            val dx = q.x - prev.x
            val dy = q.y - prev.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.x - prev.x) * dx + (p.y - prev.y) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = prev.x + t * dx
            val cy = prev.y + t * dy
            best = minOf(best, sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy)))
            prev = q
        }
        return best
    }

    @Test
    fun theResultStaysWithinToleranceOfEveryOriginalPoint() {
        val ring = circle(300, 3.0)
        for (tol in listOf(0.3, 1.0, 2.5)) {
            val s = assertNotNull(Simplify.ring(ring, tol))
            for (p in ring.points) assertTrue(distToPolygon(p, s) <= tol + 1e-9, "tolerance $tol")
        }
    }

    @Test
    fun simplifiedRingsAreValidAndKeepTheirWinding() {
        for (seed in 1L..5L) {
            val shapes = Tracer.trace(randomMask(seed, 40, 30, 50), 40, 30)
            for (s in shapes) for (r in s.rings) {
                val out = Simplify.ring(r, 0.75) ?: continue
                assertTrue(out.points.size >= 3)
                assertEquals(r.isHole, out.isHole)
                assertTrue((out.signedArea > 0) == (r.signedArea > 0), "winding flipped")
                assertTrue(out.points.size <= r.points.size)
            }
        }
    }

    @Test
    fun ringsThatCollapseAreDropped() {
        assertNull(Simplify.ring(Ring(pts(0.0, 0.0, 5.0, 0.0, 10.0, 0.0), false), 0.0), "three collinear points")
        assertNull(Simplify.ring(Ring(pts(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), false), 0.0), "one point repeated")
        // A sliver thinner than the tolerance collapses to a line.
        assertNull(Simplify.ring(Ring(pts(0.0, 0.0, 20.0, 0.0, 20.0, 0.1, 0.0, 0.1), false), 1.0))
    }

    @Test
    fun specksAndPinholesFallBelowMinAreaAndAreRemoved() {
        val big = Shape(square(0.0, 0.0, 20.0, 20.0), listOf(square(5.0, 5.0, 15.0, 15.0, hole = true), square(1.0, 1.0, 2.0, 2.0, hole = true)))
        val speck = Shape(square(30.0, 30.0, 31.0, 31.0))
        val kept = Simplify.dropSmall(listOf(big, speck), 4.0)
        assertEquals(1, kept.size)
        assertEquals(1, kept[0].holes.size, "the pinhole went, the real hole stayed")
        assertEquals(2, Simplify.dropSmall(listOf(big, speck), 0.0).size)
        assertEquals(1.0, Simplify.dropSmall(listOf(speck), 1.0)[0].outer.area, "area equal to minArea is kept")
    }

    @Test
    fun simplifyingAShapeDropsItWhenItsOuterCollapsesAndFillsCollapsedHoles() {
        val sliver = Shape(Ring(pts(0.0, 0.0, 20.0, 0.0, 20.0, 0.1, 0.0, 0.1), false))
        assertNull(Simplify.shape(sliver, 1.0))
        val withSliverHole = Shape(square(0.0, 0.0, 20.0, 20.0), listOf(Ring(pts(5.0, 5.0, 5.0, 5.05, 15.0, 5.05, 15.0, 5.0), true)))
        assertEquals(0, assertNotNull(Simplify.shape(withSliverHole, 1.0)).holes.size)
    }

    @Test
    fun theSheetLevelHelperFiltersThenSimplifies() {
        val shapes = traceRows("#........", ".........", "..#####..", "..#####..", "..#####..")
        val out = Simplify.shapes(shapes, 0.75, 4.0)
        assertEquals(1, out.size)
        assertEquals(4, out[0].outer.points.size)
    }
}
