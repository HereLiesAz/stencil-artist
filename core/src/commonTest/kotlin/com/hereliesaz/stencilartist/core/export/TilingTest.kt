package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.Ring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TilingTest {
    @Test
    fun aFiveHundredMillimetreDrawingOnA4HasTheTilesDerivedByHand() {
        // A4 is 210 x 297 with 10 mm margins: printable 190 x 277. Overlap 10, so each tile advances
        // 180 across and 267 down. The drawing is 500 x 375 mm.
        //   columns: 190 covers 0-190; 500 - 190 = 310 left, 310 / 180 = 1.72, round up to 2 more: 3 columns
        //            (0-190, 180-370, 360-500).
        //   rows:    277 covers 0-277; 375 - 277 = 98 left, 98 / 267 = 0.37, round up to 1 more: 2 rows
        //            (0-277, 267-375).
        val plan = Tiling.plan(500.0, 375.0, TileSpec.A4)
        assertEquals(3, plan.cols)
        assertEquals(2, plan.rows)
        assertEquals(6, plan.tiles.size)
        assertEquals(RectMm(0.0, 0.0, 190.0, 277.0), plan.tile(0, 0).clip)
        assertEquals(RectMm(180.0, 0.0, 370.0, 277.0), plan.tile(0, 1).clip)
        assertEquals(RectMm(360.0, 0.0, 500.0, 277.0), plan.tile(0, 2).clip)
        assertEquals(RectMm(0.0, 267.0, 190.0, 375.0), plan.tile(1, 0).clip)
        assertEquals(RectMm(360.0, 267.0, 500.0, 375.0), plan.tile(1, 2).clip)
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2), plan.tiles.map { it.row to it.col }, "row by row from the top-left")
    }

    @Test
    fun tilesCoverTheWholeDrawingAndNeighboursOverlapByExactlyTheOverlap() {
        for (spec in listOf(TileSpec.A4, TileSpec.US_LETTER, TileSpec.A3, TileSpec.MAT_12X12, TileSpec.MAT_12X24, TileSpec.custom(150.0, 100.0, 5.0, 3.0))) {
            for ((w, h) in listOf(900.0 to 700.0, 333.3 to 1000.0, 1234.5 to 98.7)) {
                val plan = Tiling.plan(w, h, spec)
                // Along the top row and the left column, no gaps and the right overlap.
                for (c in 0 until plan.cols) {
                    val t = plan.tile(0, c).clip
                    if (c == 0) assertEquals(0.0, t.x0) else assertEquals(spec.overlapMm, plan.tile(0, c - 1).clip.x1 - t.x0, 1e-9)
                    if (c == plan.cols - 1) assertEquals(w, t.x1, 1e-9) else assertEquals(spec.printableWidthMm, t.width, 1e-9)
                    assertTrue(t.width > spec.overlapMm || plan.cols == 1)
                }
                for (r in 0 until plan.rows) {
                    val t = plan.tile(r, 0).clip
                    if (r == 0) assertEquals(0.0, t.y0) else assertEquals(spec.overlapMm, plan.tile(r - 1, 0).clip.y1 - t.y0, 1e-9)
                    if (r == plan.rows - 1) assertEquals(h, t.y1, 1e-9)
                    assertTrue(t.height <= spec.printableHeightMm + 1e-9)
                }
                // And one fewer tile would not cover it.
                val last = plan.tile(plan.rows - 1, plan.cols - 1).clip
                assertEquals(w, last.x1, 1e-9)
                assertEquals(h, last.y1, 1e-9)
            }
        }
    }

    @Test
    fun aDrawingThatFitsIsOneTile() {
        val plan = Tiling.plan(150.0, 100.0, TileSpec.A4)
        assertEquals(1, plan.tiles.size)
        assertEquals(RectMm(0.0, 0.0, 150.0, 100.0), plan.tiles[0].clip)
        val exact = Tiling.plan(190.0, 277.0, TileSpec.A4)
        assertEquals(1, exact.tiles.size, "exactly the printable area still fits")
        assertEquals(2, Tiling.plan(190.001 + 1e-6, 277.0, TileSpec.A4).cols)
    }

    @Test
    fun aDrawingExactlyOneStepLongerThanThePrintableAreaIsTwoTilesNotThree() {
        assertEquals(2, Tiling.plan(190.0 + 180.0, 100.0, TileSpec.A4).cols)
        assertEquals(3, Tiling.plan(190.0 + 180.0 + 0.5, 100.0, TileSpec.A4).cols)
    }

    @Test
    fun aRollHasUnlimitedLengthSoItGivesSideBySideStripsAndOneRow() {
        val spec = TileSpec.roll(600.0)
        assertTrue(spec.isRoll)
        // Printable width 580, step 570: 1500 mm across needs 1 + ceil(920 / 570) = 3 strips of the whole height.
        val plan = Tiling.plan(1500.0, 1000.0, spec)
        assertEquals(1, plan.rows)
        assertEquals(3, plan.cols)
        assertTrue(plan.tiles.all { it.clip.y0 == 0.0 && it.clip.y1 == 1000.0 })
        assertEquals(1020.0, plan.pageHeightMm(plan.tiles[0]), "strip plus margins")
        assertEquals(1, Tiling.plan(500.0, 5000.0, spec).tiles.size, "narrow and very long is still one strip")
    }

    @Test
    fun presetsHaveTheirPublishedSizes() {
        assertEquals(215.9 to 279.4, TileSpec.US_LETTER.pageWidthMm to TileSpec.US_LETTER.pageHeightMm)
        assertEquals(210.0 to 297.0, TileSpec.A4.pageWidthMm to TileSpec.A4.pageHeightMm)
        assertEquals(297.0 to 420.0, TileSpec.A3.pageWidthMm to TileSpec.A3.pageHeightMm)
        assertEquals(304.8 to 304.8, TileSpec.MAT_12X12.pageWidthMm to TileSpec.MAT_12X12.pageHeightMm)
        assertEquals(304.8 to 609.6, TileSpec.MAT_12X24.pageWidthMm to TileSpec.MAT_12X24.pageHeightMm)
        assertEquals(TileSpec(120.0, 80.0, 4.0, 2.0), TileSpec.custom(120.0, 80.0, 4.0, 2.0).copy(name = "Custom"))
    }

    @Test
    fun anImpossibleSpecIsRefused() {
        assertFailsWith<IllegalArgumentException> { TileSpec.custom(50.0, 100.0, edgeMm = 20.0, overlapMm = 10.0) }
        assertFailsWith<IllegalArgumentException> { TileSpec.custom(100.0, 100.0, edgeMm = -1.0) }
        assertFailsWith<IllegalArgumentException> { TileSpec.roll(0.0) }
        assertFailsWith<IllegalArgumentException> { Tiling.plan(0.0, 10.0, TileSpec.A4) }
        assertFailsWith<IllegalArgumentException> { Tiling.plan(10.0, 10.0, TileSpec.A4).tile(5, 5) }
    }

    @Test
    fun placementScalesUniformlyAndFlipsForYUpFormats() {
        val d = sampleDrawing()
        val p = Placement(d, sampleSize)
        assertEquals(2.0, p.mmPerPixel)
        assertEquals(120.0, p.heightMm)
        assertEquals(Point(20.0, 40.0), p.point(Point(10.0, 20.0)))
        assertEquals(120.0, p.yUp(0.0))
        assertEquals(0.0, p.yUp(60.0))
        assertEquals(80.0, p.yUp(20.0))
    }

    @Test
    fun physicalSizeFollowsTheAspectRatioAndKnowsInches() {
        val d = sampleDrawing()
        assertEquals(120.0, PhysicalSize(200.0).heightMm(d))
        val s = PhysicalSize.inches(10.0)
        assertEquals(254.0, s.widthMm, 1e-9)
        assertEquals(10.0, s.widthInches, 1e-9)
        assertEquals(6.0, s.heightInches(d), 1e-9)
        assertFailsWith<IllegalArgumentException> { PhysicalSize(-1.0) }
    }

    @Test
    fun clippingCutsAConvexRingAtTheBoundary() {
        val sq = listOf(Point(-5.0, -5.0), Point(5.0, -5.0), Point(5.0, 5.0), Point(-5.0, 5.0))
        val out = Clip.ring(sq, RectMm(0.0, 0.0, 10.0, 10.0))
        assertEquals(Ring(out, false).area, 25.0)
        assertTrue(out.all { it.x in 0.0..5.0 && it.y in 0.0..5.0 })
        assertEquals(sq, Clip.ring(sq, RectMm(-10.0, -10.0, 10.0, 10.0)), "fully inside is unchanged")
        assertTrue(Clip.ring(sq, RectMm(20.0, 20.0, 30.0, 30.0)).size < 3, "fully outside is nothing")
        val around = Clip.ring(sq, RectMm(-1.0, -1.0, 1.0, 1.0))
        assertEquals(4.0, Ring(around, false).area, 1e-9, "a ring around the rectangle becomes the rectangle")
    }

    @Test
    fun clippingAConcaveRingKeepsTheAreaAndStaysClosed() {
        // A U with two arms; the rectangle cuts across both, so the result is joined along the clip edge.
        val u = listOf(
            Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(7.0, 10.0),
            Point(7.0, 3.0), Point(3.0, 3.0), Point(3.0, 10.0), Point(0.0, 10.0),
        )
        val out = Clip.ring(u, RectMm(-1.0, -1.0, 11.0, 6.0))
        assertTrue(out.size >= 3)
        assertEquals(Ring(u, false).area - 2 * 3 * 4.0, Ring(out, false).area, 1e-9)
        assertTrue(out.all { it.y <= 6.0 + 1e-9 })
    }
}
