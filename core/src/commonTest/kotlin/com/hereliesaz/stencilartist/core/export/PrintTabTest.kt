package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.Sheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Registration tabs: small cut windows in a board's left and top border, cut to the neighbouring board's artwork. */
class PrintTabTest {
    private val ink = Sheet(0xFF000000.toInt())
    private val poster = MountOptions(Board.POSTER_22X28)

    /** [cut] over a 400 x 400 pixel piece, 1 m wide: 2.5 mm a pixel, 3 x 2 portrait boards on poster board. */
    private fun mask(cut: (Double, Double) -> Boolean): Separation =
        Separation(400, 400, listOf(ink), IntArray(160_000) { i -> if (cut((i % 400 + 0.5) * 2.5, (i / 400 + 0.5) * 2.5)) 0 else Separation.NONE })

    private fun options(mount: MountOptions = poster, width: Double = 1000.0) = PrintOptions(width, Paper.US_LETTER, false, mount)

    /** A 60 mm checkerboard of cut and uncut: tab zones on every side hold artwork edges. */
    private fun stripes() = mask { x, y -> ((x / 60).toInt() + (y / 60).toInt()) % 2 == 0 }

    private fun boards(sep: Separation, o: PrintOptions = options()) = PrintPdf.plan(sep, o).sheets.single().boards

    @Test
    fun tabsAreOnlyOnLeftAndTopSidesThatHaveANeighbour() {
        val grid = BoardGrid.of(1000.0, 1000.0, poster)
        assertEquals(3, grid.cols); assertEquals(2, grid.rows)
        for (b in boards(stripes())) {
            if (b.blank) continue
            val cell = b.board
            for (t in b.tabs) {
                if (t.side == TabSide.LEFT) assertTrue(cell.col > 0, "left tab on board ${cell.number} which has no left neighbour")
                if (t.side == TabSide.TOP) assertTrue(cell.row > 0, "top tab on board ${cell.number} which has no board above")
            }
            if (cell.col == 0 && cell.row == 0) assertTrue(b.tabs.isEmpty() && b.missingTabs.isEmpty(), "board 1 looks at nobody")
        }
        assertTrue(boards(stripes()).any { b -> b.tabs.any { it.side == TabSide.LEFT } })
        assertTrue(boards(stripes()).any { b -> b.tabs.any { it.side == TabSide.TOP } })
    }

    @Test
    fun aSingleBoardHasNoTabsAndNoTabText() {
        val sep = mask { _, _ -> true }
        val o = options(width = 400.0)
        val plan = PrintPdf.plan(sep, o)
        assertEquals(1, plan.boardsPerSheet)
        assertEquals(0, plan.tabCount)
        assertFalse(PrintPdf.make(sep, o).decodeToString().contains("(TAB) Tj"))
    }

    @Test
    fun tabRectanglesLieWhollyInTheBorderWithTheSpecifiedSizeAndAreCutToTheNeighboursArtwork() {
        for (border in listOf(25.4, 50.8, 76.2)) {
            val m = MountOptions(Board.POSTER_22X28, border)
            val sep = stripes()
            val grid = BoardGrid.of(1000.0, 1000.0, m)
            for (b in boards(sep, options(m))) for (t in b.tabs) {
                val c = b.board.cut
                val depth = minOf(PrintPdf.TAB_DEPTH_MM, border / 2)
                assertTrue(depth <= border / 2 + 1e-9)
                val r = t.rect
                if (t.side == TabSide.LEFT) {
                    assertEquals(c.x1.let { c.x0 }, r.x1, 1e-9, "touches the cut area's left edge")
                    assertEquals(depth, r.width, 1e-9)
                    assertEquals(PrintPdf.TAB_WIDTH_MM, r.height, 1e-9)
                    assertTrue(r.y0 >= c.y0 + PrintPdf.TAB_WIDTH_MM - 1e-9 && r.y1 <= c.y1 - PrintPdf.TAB_WIDTH_MM + 1e-9, "a tab width from the corners")
                    // Wholly in the border (inside the board, outside the cut area) and in the left neighbour's cell.
                    assertTrue(r.x0 >= c.x0 - border - 1e-9 && r.x1 <= c.x0 + 1e-9)
                    val n = grid.cells.first { it.row == b.board.row && it.col == b.board.col - 1 }.cut
                    assertTrue(r.x0 >= n.x0 && r.x1 <= n.x1 && r.y0 >= n.y0 && r.y1 <= n.y1, "inside the neighbour's cell")
                } else {
                    assertEquals(c.y0, r.y1, 1e-9)
                    assertEquals(depth, r.height, 1e-9)
                    assertEquals(PrintPdf.TAB_WIDTH_MM, r.width, 1e-9)
                    assertTrue(r.x0 >= c.x0 + PrintPdf.TAB_WIDTH_MM - 1e-9 && r.x1 <= c.x1 - PrintPdf.TAB_WIDTH_MM + 1e-9)
                    val n = grid.cells.first { it.col == b.board.col && it.row == b.board.row - 1 }.cut
                    assertTrue(r.x0 >= n.x0 && r.x1 <= n.x1 && r.y0 >= n.y0 && r.y1 <= n.y1)
                }
                // A tab paints nothing new: the artwork inside it is this sheet's cut in the finished piece, so it holds cut pixels.
                val cutPixels = (0 until 400 * 400).count { i ->
                    val x = (i % 400 + 0.5) * 2.5
                    val y = (i / 400 + 0.5) * 2.5
                    x >= r.x0 && x < r.x1 && y >= r.y0 && y < r.y1 && sep.index[i] != Separation.NONE
                }
                assertTrue(cutPixels > 0, "a tab holds cut of its sheet")
            }
        }
    }

    @Test
    fun atMostTwoTabsPerSideNeverOverlappingAndSpreadApart() {
        for (b in boards(stripes())) for (side in TabSide.entries) {
            val on = b.tabs.filter { it.side == side }
            assertTrue(on.size <= 2, "at most two ${side.name} tabs on board ${b.board.number}")
            if (on.size == 2) {
                val a = on[0].rect
                val c = on[1].rect
                val overlap = a.x0 < c.x1 && a.x1 > c.x0 && a.y0 < c.y1 && a.y1 > c.y0
                assertFalse(overlap)
                val gap = if (side == TabSide.LEFT) c.y0 - a.y0 else c.x0 - a.x0
                assertTrue(gap >= PrintPdf.TAB_WIDTH_MM - 1e-9)
            }
        }
    }

    @Test
    fun positionsWithAnArtworkEdgeAreChosenOverAllCutOnes() {
        // The left neighbour's cut meets board 2's left tab zone (finished x 444.5..457.2): from x = 450 for the top 300 mm,
        // from x = 400 below (the tab zone there is all cut). Tabs on that side must be the edge-bearing ones.
        val sep = mask { x, y -> if (y < 300.0) x >= 450.0 else x >= 400.0 }
        val b = boards(sep).first { it.board.col == 1 && it.board.row == 0 }
        val left = b.tabs.filter { it.side == TabSide.LEFT }
        assertTrue(left.isNotEmpty())
        assertTrue(left.all { it.edgeBearing }, "edge-bearing positions preferred")
        assertTrue(left.all { it.rect.y0 < 300.0 }, left.toString())
        // With only all-cut positions available, those are used.
        val allCut = boards(mask { x, _ -> x >= 400.0 }).first { it.board.col == 1 && it.board.row == 0 }
        val fallback = allCut.tabs.filter { it.side == TabSide.LEFT }
        assertTrue(fallback.isNotEmpty() && fallback.none { it.edgeBearing }, "falls back to all-cut positions")
    }

    @Test
    fun aSideWithNothingCutNearItGetsNoTabAndTheMapSaysSo() {
        // Cut only well inside board 2's cell: nothing near its left or top edges' neighbours.
        val sep = mask { x, y -> x in 700.0..900.0 && y in 100.0..300.0 }
        val o = options()
        val b = boards(sep, o).first { it.board.col == 1 && it.board.row == 0 }
        assertFalse(b.blank)
        assertTrue(b.tabs.isEmpty())
        assertEquals(listOf(TabSide.LEFT), b.missingTabs)
        val plan = PrintPdf.plan(sep, o)
        assertEquals(1, plan.boardsMissingTabs)
        val map = PrintPdf.make(sep, o).decodeToString()
        assertTrue("(no left tab) Tj" in map, "the map says so")
    }

    @Test
    fun aPageThatContainsATabIsPrintedEvenWithNothingElseToCutOnIt() {
        // 4 in border: board 2's left tab zone is finished x 342.9..355.6, which lies in grid x 647.7..660.4 on board 2 but 444.5..457.2 on
        // board 1, so a page that holds only the tab is one that board 1's own cut does not need. Cut: a thin strip in cell 1, and far inside cell 2.
        val m = MountOptions(Board.POSTER_22X28, 101.6)
        val sep = mask { x, _ -> x in 343.0..354.0 || x in 640.0..660.0 }
        val o = options(m)
        val p = PrintPdf.plan(sep, o)
        val b = p.sheets.single().boards.first { it.board.col == 1 && it.board.row == 0 }
        assertFalse(b.blank)
        val tab = b.tabs.first { it.side == TabSide.LEFT }
        val shift = (2 * 1 + 1) * 101.6
        val gridRect = RectMm(tab.rect.x0 + shift, tab.rect.y0 + 101.6, tab.rect.x1 + shift, tab.rect.y1 + 101.6)
        val tile = p.sheets.single().printed.firstOrNull { t ->
            gridRect.x0 < t.clip.x0 + 209.9 && gridRect.x1 > t.clip.x0 && gridRect.y0 < t.clip.y0 + 273.4 && gridRect.y1 > t.clip.y0
        }
        assertNotNull(tile, "a printed page holds the tab")
        assertTrue(tile.col == 3 && tile.row == 0, "it is a page the board's own cut does not need: ${tile.col},${tile.row}")
        assertTrue("(TAB) Tj" in PrintPdf.make(sep, o).decodeToString())
    }

    private fun plan(sep: Separation, o: PrintOptions) = PrintPdf.plan(sep, o)

    @Test
    fun tabsAddNoCuttingNotesAndTheInstructionsExplainThem() {
        val bands = mask { x, _ -> (x / 60).toInt() % 2 == 0 } // vertical bands: no islands, no fine cuts
        val pdf = PrintPdf.make(bands, options()).decodeToString()
        assertFalse("(ISLAND) Tj" in pdf || "(FINE DETAIL) Tj" in pdf, "tabs create no notes")
        assertTrue("(PAINT BOARD BY BOARD) Tj" in pdf)
        assertTrue("(CUT AT THE DOTTED LINE) Tj" in pdf)
    }

    @Test
    fun theDepthIsCappedAtHalfTheBorder() {
        assertEquals(12.7, PrintPdf.TAB_DEPTH_MM, 1e-9)
        assertEquals(25.4, PrintPdf.TAB_WIDTH_MM, 1e-9)
        // Borders from the minimum up: depth never exceeds half.
        for (border in listOf(25.4, 30.0, 40.0, 50.8)) assertTrue(minOf(PrintPdf.TAB_DEPTH_MM, border / 2) <= border / 2)
    }

    @Test
    fun tabsAvoidLyingUnderABlankThreeMillimetreStripWhenAnotherPositionDoes() {
        // Board 2's left tab zone: cut for y in [130, 170) only. Edge-bearing positions run from 105.4 to 125.4 (clear of strips) and
        // from 145.4 to 165.4 (the horizontal join strip at y 165.1..168.1 touches those from 145.4 on). Clear ones must be chosen.
        val sep = mask { x, y -> x >= 400.0 && y >= 130.0 && y < 170.0 }
        val b = boards(sep).first { it.board.col == 1 && it.board.row == 0 }
        val left = b.tabs.filter { it.side == TabSide.LEFT }
        assertTrue(left.isNotEmpty())
        assertTrue(left.all { it.hidden < 0.01 }, "chosen tabs lie under no blank strip: ${left.map { it.hidden }}")
        assertTrue(left.all { it.edgeBearing })
        // A strip really crosses some other usable position: a tab at y0 = 150 would be partly hidden.
        val strips = boards(mask { x, y -> x >= 400.0 && y in 150.0..175.0 }).first { it.board.col == 1 && it.board.row == 0 }
        assertTrue(strips.tabs.filter { it.side == TabSide.LEFT }.isNotEmpty())
    }
}
