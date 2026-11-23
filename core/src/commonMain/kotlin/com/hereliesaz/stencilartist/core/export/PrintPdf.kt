package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.StencilPrintTips
import com.hereliesaz.stencilartist.core.PrintTip
import com.hereliesaz.stencilartist.core.PrintTipKind
import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.Vectorizer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What to print: the finished piece's physical width (its height follows the image) on [paper], turned
 * to [landscape] or portrait. The printer's unprintable border and the overlap between pages are not
 * options: see [PrintPdf.MARGIN_MM] and [PrintPdf.OVERLAP_MM]. With a [mount] the printed pages are glued on boards
 * of that size, each board tiled with pages on its own; without one the pages tile the whole piece.
 */
data class PrintOptions(
    val widthMm: Double = 203.2,
    val paper: Paper = Paper.US_LETTER,
    val landscape: Boolean = false,
    val mount: MountOptions? = null,
) {
    init {
        require(widthMm > 0.0 && widthMm.isFinite()) { "The stencil width must be a positive number of millimetres." }
        val page = paper.oriented(landscape)
        require(
            PrintPdf.printableWidthMm(page) >= PrintPdf.MIN_PRINTABLE_MM &&
                PrintPdf.printableHeightMm(page) >= PrintPdf.MIN_PRINTABLE_MM,
        ) {
            "The paper is too small: after the ${PrintPdf.MARGIN_MM} mm border each page must print at least " +
                "${PrintPdf.MIN_PRINTABLE_MM} mm each way, but this one prints " +
                "${Decimal.format(PrintPdf.printableWidthMm(page), 1)} x ${Decimal.format(PrintPdf.printableHeightMm(page), 1)} mm."
        }
    }

    /** The paper as it lies in the printer. */
    val page: Paper get() = paper.oriented(landscape)

    /** The tiling of [page]: the printer border as the unusable edge, the fixed overlap between pages. */
    val spec: TileSpec get() = PrintPdf.spec(page)
}

/** The side of a board a tab is on: boards are painted left to right, top to bottom, so tabs look back at boards already painted. */
enum class TabSide { LEFT, TOP }

/**
 * A tab: a small window cut in a board's border, in finished-piece mm, touching the cut area's edge on [side] and
 * reaching into the neighbour's cell. Inside [rect] the board is cut to the sheet's own artwork there, which is what
 * the neighbouring board cuts too. [edgeBearing] says the rectangle holds both cut and uncut artwork (an artwork edge
 * to line up to); a rectangle of all cut is used only when no edge-bearing one exists. [hidden] is the fraction of the
 * rectangle that lies under a page's blank 3 mm margin (a strip at a join where no artwork shows); positions with none
 * of it hidden are preferred.
 */
data class TabCut(val side: TabSide, val rect: RectMm, val edgeBearing: Boolean, val hidden: Double = 0.0)

/**
 * One board of one sheet: its [printed] tiles (local to the board), the [skipped] ones, whether the whole board is
 * [blank], its [tabs] and the [missingTabs] sides that face an earlier board but had no usable position for a tab.
 */
data class BoardPages(
    val board: BoardCell, val blank: Boolean, val printed: List<Tile>, val skipped: List<Tile>,
    val tabs: List<TabCut> = emptyList(), val missingTabs: List<TabSide> = emptyList(),
)

/**
 * The pages of one sheet: [printed] tiles get a page, [blank] tiles have nothing to cut (and are not the corner page A1 of a printed board) and
 * are skipped. When mounting, the tiles are those of each printed board (local to it) and [boards] lists every board.
 */
data class SheetPages(val sheet: Int, val printed: List<Tile>, val blank: List<Tile>, val boards: List<BoardPages> = emptyList())

/**
 * How a print divides up before anything is drawn: the page [grid] and, for each requested sheet, which tiles are
 * printed and which are blank. It is computed by the same code that writes the PDF, so a readout built from it is
 * the PDF's own page count (less the instructions page, the maps and any reference pages, which depend on the
 * cutting tips). Without mounting [grid] tiles the piece; when mounting it tiles one board from page A1's printable
 * corner to the far side of its cut area, and [boards] says how many boards a sheet takes.
 */
class PrintPlan(val options: PrintOptions, val grid: TilePlan, val sheets: List<SheetPages>, val boards: BoardGrid? = null) {
    /** Tiles in the grid, printed or not: the pages one sheet (or one board) would take if nothing were blank. */
    val tilesPerSheet: Int get() = grid.tiles.size

    /** Tile pages over all requested sheets. */
    val printedPages: Int get() = sheets.sumOf { it.printed.size }

    /** Tiles skipped as blank over all requested sheets (those inside boards that are printed, when mounting). */
    val blankPages: Int get() = sheets.sumOf { it.blank.size }

    /** Boards one sheet needs; 0 without mounting. */
    val boardsPerSheet: Int get() = boards?.count ?: 0

    /** Tabs over all requested sheets. */
    val tabCount: Int get() = sheets.sumOf { s -> s.boards.sumOf { it.tabs.size } }

    /** Boards whose shared left or top side has no usable position for a tab, over all requested sheets. */
    val boardsMissingTabs: Int get() = sheets.sumOf { s -> s.boards.count { it.missingTabs.isNotEmpty() } }

    /** Boards that are printed, over all requested sheets. */
    val printedBoards: Int get() = sheets.sumOf { s -> s.boards.count { !it.blank } }

    /** Boards skipped because nothing is cut on them, over all requested sheets. */
    val blankBoards: Int get() = sheets.sumOf { s -> s.boards.count { it.blank } }
}

/**
 * The print PDF: the one PDF the app writes. PDF 1.4 written by hand, true physical size, never scaled:
 * print at 100% / actual size.
 *
 * Layout. A page's printable area is the paper less a fixed [MARGIN_MM] border on every side (what a
 * printer cannot print). Nothing is trimmed: pages are laid left to right, top to bottom, each later page ON TOP of
 * the earlier ones, so a top page's unprinted margin lies over the page beneath. Paper-only, sheets advance by the
 * paper size less [PAPER_OVERLAP_MM] (the margin plus [OVERLAP_MM]), which puts the top page's paper edge [OVERLAP_MM] inside
 * the under page's printed area, where the under page prints a guide for it. The piece's origin is page A1's printed
 * corner, and every page shows the piece at its true place: the artwork is not shifted or stretched.
 *
 * The blank strip. Because an untrimmed top page hides what lies under its margin, at every join there is a strip
 * of [MARGIN_MM] of blank paper (the top page's margin) where no artwork shows. The artwork either side is exactly
 * where it belongs; cut lines are simply continued straight across the strip. The last up to
 * 2 mm of a piece that ends in the gap between two printed areas cannot be printed by any page.
 *
 * Guides. Marks exist only where two pages overlap: the UNDER page prints, just outside the area the top page will
 * cover, thin ticks and L marks along the top page's paper edges (see [guides]); the top page prints nothing in its
 * own margin. Alignment is paper edge on printed mark. A single page and the outer edges have no marks.
 *
 * Boards. On a board the pages are not stepped by a fixed amount but fitted to it: the fewest pages whose paper spans the
 * board with neighbouring sheets overlapping by at least [PAPER_OVERLAP_MM], the first paper edge on the board's edge and the
 * last on the far edge, equal overlaps within the board (paper larger than the board is refused). The top page's paper edge
 * then lands wherever the fit puts it, inside the under page's printed area, and the under page prints the guide there.
 * Because the outermost pages' paper edges lie on the board's edges, any outer page can be squared against the board.
 *
 * Pages. One instructions page for the whole file; then for each sheet an assembly map, the sheet's tile
 * pages (a tile whose net cell, the part of the piece it alone owns, has nothing to cut is skipped and
 * shown as blank on the map), and non-cutting reference pages for tips that cannot be placed. The cut
 * shapes are vectors clipped to the printable area, filled in the sheet's colour and outlined in dark
 * ink so a pale sheet stays visible.
 *
 * Cutting tips ([StencilPrintTips]) are printed only on surviving uncut material, with a cut-free
 * buffer, never across a cut. A tip that cannot be placed on its page, including one whose page is
 * blank, goes to a reference page beside a copy of its cut detail; nothing is dropped. Islands are
 * diagnosed once on the full sheet, not per tile. One sheet's masks and clearance tables are alive at
 * a time, and a count of details or pages that cannot be printed is rejected up front.
 *
 * The page label and short code ("Sheet 2 of 5 - Row 1 Col 3 (C1)") are placed like a tip: on uncut
 * material or beyond the piece's edge, never on a cut. Only if a page has no such room at all (it is
 * cut open from edge to edge) is it printed at the top-left corner regardless.
 */
object PrintPdf {
    /** The unprintable border of every page, from the paper's edge, in mm. Fixed. */
    const val MARGIN_MM = 3.0

    /**
     * How far the top page's paper edge lies inside the under page's printed area, in mm: the band the guide marks
     * are printed in. Fixed.
     */
    const val OVERLAP_MM = 1.0

    /**
     * How far neighbouring sheets of paper overlap, in mm: the top page's unprinted margin plus [OVERLAP_MM], so that its
     * paper edge falls inside the under page's printed area, where the under page can print the guide for it.
     */
    const val PAPER_OVERLAP_MM = MARGIN_MM + OVERLAP_MM

    /** Length of each arm of a guide mark, in mm. */
    const val MARK_ARM_MM = 4.0

    /** Stroke width of the guide marks, in mm. */
    const val MARK_WIDTH_MM = 0.2

    /** The smallest printable width and height a page may have, in mm, so the instructions and notes fit. */
    const val MIN_PRINTABLE_MM = 120.0

    /** Width of a tab along the board edge it is on, in mm: 1 in. */
    const val TAB_WIDTH_MM = 25.4

    /**
     * How far a tab reaches out of the cut area into the border, in mm, at most: 0.5 in, and never more than half the
     * border. A tab removes board from the border, which holds the board together, so it stays shallow; that is why
     * the depth is capped at half the border. Paint that falls through a tab is harmless because the tab is cut to
     * the sheet's own artwork where it lies, so it lands exactly where this sheet's paint goes anyway.
     */
    const val TAB_DEPTH_MM = 12.7

    /** The step, in mm, between candidate tab positions along a board edge. */
    const val TAB_STEP_MM = 5.0

    private const val MAX_PAGES = 4096
    private const val MAX_TIPS = 16384
    private const val K = 72.0 / PhysicalSize.MM_PER_INCH
    private const val INK = "0.1 0.1 0.1"
    private const val TIP_W = 146.0 / K
    private const val TIP_H = 49.0 / K
    private const val TIP_CLEARANCE = 5.0 / K
    private const val INSET = 1.5
    private const val LABEL_PT = 6.5

    /** The paper's printable width: paper less the border on both sides. */
    fun printableWidthMm(page: Paper): Double = page.widthMm - 2 * MARGIN_MM

    /** The paper's printable height. */
    fun printableHeightMm(page: Paper): Double = page.heightMm - 2 * MARGIN_MM

    /** How far one sheet of paper advances across the next, paper-only: the paper width less [PAPER_OVERLAP_MM]. */
    fun stepWidthMm(page: Paper): Double = page.widthMm - PAPER_OVERLAP_MM

    /** How far one sheet of paper advances down to the next, paper-only. */
    fun stepHeightMm(page: Paper): Double = page.heightMm - PAPER_OVERLAP_MM

    /**
     * How many pages [length] mm needs when pages of [printable] mm advance by [step] mm and the last one's printed area
     * starts inside the piece: one if the piece fits the printed area, otherwise enough steps to reach it. The last up to
     * 2 mm of a piece that ends in the blank strip between windows cannot be printed by any page.
     */
    internal fun pageCount(length: Double, printable: Double, step: Double): Int {
        if (length <= printable + 1e-9) return 1
        return maxOf(1, ceil(length / step - 1e-9).toInt())
    }

    /** [page] as the tiling spec the print uses. */
    fun spec(page: Paper): TileSpec = TileSpec(page.widthMm, page.heightMm, MARGIN_MM, OVERLAP_MM, page.name)

    /**
     * The fewest pages of [paper] mm that span [board] mm with neighbouring sheets overlapping by at least
     * [PAPER_OVERLAP_MM], the first paper edge on the board's edge and the last on the far edge. One page if the paper is
     * as long as the board.
     */
    internal fun fitCount(board: Double, paper: Double): Int {
        if (board <= paper + 1e-9) return 1
        return 1 + ceil((board - paper) / (paper - PAPER_OVERLAP_MM) - 1e-9).toInt()
    }

    /** A spreadsheet-style column name: 0 is "A", 25 is "Z", 26 is "AA". */
    fun columnName(col: Int): String {
        var n = col
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    /** A tile's short code: column letter then row number, "C1" for row 1, column 3. */
    fun tileCode(tile: Tile): String = columnName(tile.col) + (tile.row + 1)

    /**
     * The guide marks [tile] prints, each a polyline in the grid's own mm (y down): where the sheets of paper that will
     * be laid on top of this one have their edges. Pages are laid left to right, top to bottom, each later page on top of
     * the earlier ones, untrimmed, so a page is covered by the page to its right, the one below it, and the one below and to
     * the right (and, for a sliver of its corner, the one below and to the left). Each of those prints nothing on the page
     * that covers; this page prints, along each covering paper edge that crosses its printed area, a tick of
     * [MARK_ARM_MM] at each end of the crossing and an L where a covering page's paper corner lands (arms along that page's
     * two paper edges, pointing along them into the area it will cover). A page nothing is laid on (the last page of the last
     * row and column, or a single page) prints none, and nothing is printed along the piece's outer edges.
     *
     * The strokes sit just OUTSIDE the covered area, their inner edge on the covering paper's edge, so that once the page is
     * slid up against them they stay visible as a check that it is square and where it should be; centred on the edge, half
     * of each would disappear under the paper. The edge is inside this page's printed area by at least [OVERLAP_MM]
     * (paper-only: exactly that), which is the band the marks reside in.
     */
    internal fun guides(grid: TilePlan, tile: Tile): List<List<Point>> {
        val spec = grid.spec
        val p = spec.printableWidthMm
        val ph = spec.printableHeightMm
        val a = MARK_ARM_MM
        val hw = MARK_WIDTH_MM / 2
        val wx0 = tile.clip.x0
        val wy0 = tile.clip.y0
        val wx1 = wx0 + p
        val wy1 = wy0 + ph
        val right = tile.col < grid.cols - 1
        val below = tile.row < grid.rows - 1
        val left = tile.col > 0
        val out = ArrayList<List<Point>>()
        // The covering pages' paper edges, drawn just outside (before) them.
        val xg = if (right) grid.tile(tile.row, tile.col + 1).clip.x0 - MARGIN_MM - hw else 0.0
        val yg = if (below) grid.tile(tile.row + 1, tile.col).clip.y0 - MARGIN_MM - hw else 0.0
        if (right) {
            // The right-hand page's left paper edge, crossing this page's printed area from top to bottom.
            out += listOf(Point(xg, wy0), Point(xg, min(wy0 + a, wy1)))
            out += if (below) listOf(Point(xg, max(wy0, yg - a)), Point(xg, yg)) else listOf(Point(xg, max(wy0, wy1 - a)), Point(xg, wy1))
        }
        if (below) {
            // The lower page's top paper edge, crossing it from left to right.
            if (!left) out += listOf(Point(wx0, yg), Point(min(wx0 + a, wx1), yg))
            out += if (right) listOf(Point(max(wx0, xg - a), yg), Point(xg, yg)) else listOf(Point(max(wx0, wx1 - a), yg), Point(wx1, yg))
        }
        if (right && below) {
            // The paper corner of the page below and to the right: an L along its top and left paper edges.
            out += listOf(Point(min(xg + a, wx1), yg), Point(xg, yg), Point(xg, min(yg + a, wy1)))
        }
        if (below && left) {
            // The paper corner of the page below and to the left: an L along its top and right paper edges.
            val xd = grid.tile(tile.row + 1, tile.col - 1).clip.x0 + p + MARGIN_MM + hw
            out += listOf(Point(max(xd - a, wx0), yg), Point(xd, yg), Point(xd, min(yg + a, wy1)))
        }
        return out
    }

    /** The part of the piece only [tile] owns: from its window's corner to the next page's window along and down, or the grid's end. */
    internal fun net(grid: TilePlan, tile: Tile): RectMm {
        val x1 = if (tile.col == grid.cols - 1) grid.widthMm else grid.tile(tile.row, tile.col + 1).clip.x0
        val y1 = if (tile.row == grid.rows - 1) grid.heightMm else grid.tile(tile.row + 1, tile.col).clip.y0
        return RectMm(tile.clip.x0, tile.clip.y0, maxOf(x1, tile.clip.x0), maxOf(y1, tile.clip.y0))
    }

    /**
     * One piece of the print that is tiled with pages on its own: the whole piece, or one board. [grid] is local to
     * the region, whose top-left is ([ox], [oy]) in finished-piece mm (for a board, the top-left of page A1's printable
     * area: the board's corner plus [MARGIN_MM]); [art] is the part of the piece it prints and [bounds] the whole board,
     * border included, or null for the whole piece.
     */
    internal class Region(val board: BoardCell?, val ox: Double, val oy: Double, val art: RectMm, val grid: TilePlan, val bounds: RectMm?)

    /** The regions a print divides into, and the page grid they share. */
    internal class Layout(val widthMm: Double, val heightMm: Double, val options: PrintOptions) {
        val spec: TileSpec = options.spec
        val boardGrid: BoardGrid? = options.mount?.let { BoardGrid.of(widthMm, heightMm, it) }
        val regions: List<Region>
        val grid: TilePlan

        /** How far the windows advance across and down from one page to the next, in the grid's own mm (0 for a single page). */
        val stepX: Double

        /** See [stepX]. */
        val stepY: Double

        /** Where page 0's window starts in the grid's own coordinates: 0 for the piece, the margin for a board (whose paper edge is 0). */
        val offset: Double

        init {
            val bg = boardGrid
            val p = spec.printableWidthMm
            val ph = spec.printableHeightMm
            if (bg == null) {
                // Paper only: sheets advance by the paper size less the paper overlap; the piece's origin is page A1's window corner.
                stepX = spec.pageWidthMm - PAPER_OVERLAP_MM
                stepY = spec.pageHeightMm - PAPER_OVERLAP_MM
                offset = 0.0
                val cols = pageCount(widthMm, p, stepX)
                val rows = pageCount(heightMm, ph, stepY)
                require(cols.toLong() * rows <= Tiling.MAX_TILES) { "$cols x $rows pages is more than the ${Tiling.MAX_TILES} allowed; use a larger page" }
                grid = buildGrid(widthMm, heightMm, cols, rows, stepX, stepY, 0.0)
                regions = listOf(Region(null, 0.0, 0.0, RectMm(0.0, 0.0, widthMm, heightMm), grid, null))
            } else {
                val border = checkNotNull(options.mount).borderMm
                val board = checkNotNull(options.mount).board.oriented(bg.landscape)
                require(spec.pageWidthMm <= board.widthMm + 1e-9 && spec.pageHeightMm <= board.heightMm + 1e-9) {
                    "${spec.name} paper (${Decimal.format(spec.pageWidthMm, 1)} x ${Decimal.format(spec.pageHeightMm, 1)} mm) is larger than the " +
                        "${Decimal.format(board.widthMm, 1)} x ${Decimal.format(board.heightMm, 1)} mm board it would be mounted on; " +
                        "use smaller paper or a bigger board."
                }
                // Pages are fitted to the board: the first paper edge lies on the board's edge and the last on the far edge,
                // neighbouring sheets overlapping equally and by at least the paper overlap.
                val columns = fitCount(board.widthMm, spec.pageWidthMm)
                val rows = fitCount(board.heightMm, spec.pageHeightMm)
                stepX = if (columns > 1) (board.widthMm - spec.pageWidthMm) / (columns - 1) else 0.0
                stepY = if (rows > 1) (board.heightMm - spec.pageHeightMm) / (rows - 1) else 0.0
                offset = MARGIN_MM
                grid = buildGrid(board.widthMm, board.heightMm, columns, rows, stepX, stepY, MARGIN_MM)
                regions = bg.cells.map { cell ->
                    val c = cell.cut
                    Region(
                        cell, c.x0 - border, c.y0 - border,
                        RectMm(c.x0, c.y0, min(widthMm, c.x1), min(heightMm, c.y1)), grid,
                        RectMm(c.x0 - border, c.y0 - border, c.x1 + border, c.y1 + border),
                    )
                }
            }
        }

        private fun buildGrid(w: Double, h: Double, cols: Int, rows: Int, sx: Double, sy: Double, off: Double): TilePlan {
            val tiles = ArrayList<Tile>(cols * rows)
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = c * sx + off
                val y0 = r * sy + off
                tiles += Tile(r, c, RectMm(x0, y0, x0 + spec.printableWidthMm, y0 + spec.printableHeightMm))
            }
            return TilePlan(spec, w, h, rows, cols, tiles)
        }

        /** The index, along an axis with [tiles] pages, of the page that owns coordinate [v] (grid-local). */
        private fun owner(v: Double, step: Double, tiles: Int): Int =
            if (tiles == 1 || step <= 0.0) 0 else min(tiles - 1, max(0, floor((v - offset) / step).toInt()))

        /** The region and the tile within it that own the point ([x], [y]) of the piece. */
        fun locate(x: Double, y: Double): Pair<Int, Int> {
            val bg = boardGrid
            val bc = if (bg == null) 0 else min(bg.cols - 1, floor(x / bg.cutWidthMm).toInt())
            val br = if (bg == null) 0 else min(bg.rows - 1, floor(y / bg.cutHeightMm).toInt())
            val region = regions[br * (bg?.cols ?: 1) + bc]
            val col = owner(x - region.ox, stepX, grid.cols)
            val row = owner(y - region.oy, stepY, grid.rows)
            return (br * (bg?.cols ?: 1) + bc) to (row * grid.cols + col)
        }

        fun note(sheet: Int, tip: PrintTip, mmPerPx: Double): Note {
            val x = (tip.x + 0.5) * mmPerPx
            val y = (tip.y + 0.5) * mmPerPx
            val (region, tile) = locate(x, y)
            val position = "At ${x.roundToInt()},${y.roundToInt()}mm"
            val lines = when (tip.kind) {
                PrintTipKind.ISLAND -> listOf("ISLAND", position, "Add small bridges", "before cutting.")
                PrintTipKind.FINE_CUT -> listOf("FINE DETAIL", position, "Cut this part last.", "Lift waste slowly.")
            }
            val t = grid.tiles[tile]
            val board = regions[region].board?.number ?: 0
            return Note("S$sheet:${tip.kind}:${tip.x}:${tip.y}", sheet, lines, x, y, region, tile, tileCode(t), board)
        }

        /** The Lay that draws [region]'s pages. */
        fun lay(region: Int, sheetTotal: Int, tabs: List<TabCut> = emptyList()): Lay {
            val r = regions[region]
            return Lay(grid, sheetTotal, RectMm(0.0, 0.0, widthMm, heightMm), r.ox, r.oy, if (r.board == null) null else r.art, r.board,
                boardGrid?.count ?: 0, r.bounds, tabs)
        }

        /** Per region, which tiles have cut in the part of the piece they alone own. */
        fun usage(mask: BooleanArray, width: Int, height: Int): Array<BooleanArray> {
            val used = Array(regions.size) { BooleanArray(grid.tiles.size) }
            val mm = widthMm / width
            val bg = boardGrid
            // A pixel's offset in its region's own coordinates: from the piece's corner, or from its board's corner.
            val t = if (bg == null) 0.0 else checkNotNull(options.mount).borderMm
            fun axis(count: Int, cell: Double?, boards: Int, step: Double, tiles: Int): Pair<IntArray, IntArray> {
                val region = IntArray(count)
                val tile = IntArray(count)
                for (i in 0 until count) {
                    val v = (i + 0.5) * mm
                    val b = if (cell == null) 0 else min(boards - 1, floor(v / cell).toInt())
                    region[i] = b
                    tile[i] = owner(if (cell == null) v else v - b * cell + t, step, tiles)
                }
                return region to tile
            }
            val (rx, tx) = axis(width, bg?.cutWidthMm, bg?.cols ?: 1, stepX, grid.cols)
            val (ry, ty) = axis(height, bg?.cutHeightMm, bg?.rows ?: 1, stepY, grid.rows)
            val cols = bg?.cols ?: 1
            for (y in 0 until height) {
                val row = y * width
                val rowTile = ty[y] * grid.cols
                val rowRegion = ry[y] * cols
                for (x in 0 until width) if (mask[row + x]) used[rowRegion + rx[x]][rowTile + tx[x]] = true
            }
            return used
        }

        /**
         * The fraction of [rect] (finished mm) that lies under the blank 3 mm margin of a page laid on top of another: along the
         * left and top of every page that has a page to its left or above. No artwork shows there.
         */
        private fun hiddenFraction(r: Int, rect: RectMm): Double {
            val reg = regions[r]
            val p = spec.printableWidthMm
            val ph = spec.printableHeightMm
            fun overlap(x0: Double, y0: Double, x1: Double, y1: Double): Double {
                val w = min(rect.x1, x1) - max(rect.x0, x0)
                val h = min(rect.y1, y1) - max(rect.y0, y0)
                return if (w > 0 && h > 0) w * h else 0.0
            }
            var area = 0.0
            for (t in grid.tiles) {
                val x0 = reg.ox + t.clip.x0
                val y0 = reg.oy + t.clip.y0
                if (t.col > 0) area += overlap(x0 - MARGIN_MM, y0 - MARGIN_MM, x0, y0 + ph + MARGIN_MM)
                if (t.row > 0) area += overlap(x0 - MARGIN_MM, y0 - MARGIN_MM, x0 + p + MARGIN_MM, y0)
            }
            return area / (rect.width * rect.height)
        }

        /** The tabs of region [r] for the sheet [uncut] describes, and the sides that face an earlier board but got none. */
        private fun tabsFor(r: Int, uncut: UncutSpace): Pair<List<TabCut>, List<TabSide>> {
            val cell = regions[r].board ?: return emptyList<TabCut>() to emptyList()
            val border = checkNotNull(options.mount).borderMm
            val depth = min(TAB_DEPTH_MM, border / 2)
            val c = cell.cut
            val w = TAB_WIDTH_MM
            val tabs = ArrayList<TabCut>()
            val missing = ArrayList<TabSide>()
            for (side in TabSide.entries) {
                if (if (side == TabSide.LEFT) cell.col == 0 else cell.row == 0) continue
                val from = (if (side == TabSide.LEFT) c.y0 else c.x0) + w
                val to = (if (side == TabSide.LEFT) c.y1 else c.x1) - 2 * w
                val usable = ArrayList<TabCut>()
                var pos = from
                while (pos <= to + 1e-9) {
                    val rect = if (side == TabSide.LEFT) RectMm(c.x0 - depth, pos, c.x0, pos + w) else RectMm(pos, c.y0 - depth, pos + w, c.y0)
                    val (cut, total) = uncut.count(rect)
                    if (cut > 0) usable += TabCut(side, rect, cut < total, hiddenFraction(r, rect))
                    pos += TAB_STEP_MM
                }
                // Preference: an artwork edge to line up to, then not lying under a blank strip.
                val pool = usable.filter { it.edgeBearing && it.hidden < 0.01 }
                    .ifEmpty { usable.filter { it.edgeBearing } }
                    .ifEmpty { usable.filter { it.hidden < 0.01 } }
                    .ifEmpty { usable }
                if (pool.isEmpty()) { missing += side; continue }
                fun at(t: TabCut) = if (side == TabSide.LEFT) t.rect.y0 else t.rect.x0
                val first = pool.first()
                val last = pool.last()
                if (at(last) - at(first) >= w - 1e-9) { tabs += first; tabs += last } else tabs += pool[pool.size / 2]
            }
            return tabs to missing
        }

        /** What a sheet needs of each region: its cut tiles, its tabs and which tiles get a page. */
        inner class Analysis(val used: Array<BooleanArray>, val tabs: List<List<TabCut>>, val missing: List<List<TabSide>>) {
            /** Whether region [r] is skipped: nothing is cut on it. */
            fun blank(r: Int) = used[r].none { it }

            /**
             * Whether tile [t] of region [r] gets a page: it has cut in it, a tab of the board is cut on it or, on a printed
             * board, it is the corner page A1 (tile 0), which is the reference the pages are glued down from.
             */
            fun needed(r: Int, t: Int): Boolean {
                if (blank(r)) return false
                if (used[r][t] || (t == 0 && regions[r].board != null)) return true
                val reg = regions[r]
                val tile = grid.tiles[t]
                val x0 = reg.ox + tile.clip.x0
                val y0 = reg.oy + tile.clip.y0
                return tabs[r].any { it.rect.x0 < x0 + spec.printableWidthMm && it.rect.x1 > x0 && it.rect.y0 < y0 + spec.printableHeightMm && it.rect.y1 > y0 }
            }
        }

        /** The analysis of one sheet's [mask]; [uncut] is its clearance table. */
        fun analyse(mask: BooleanArray, width: Int, height: Int, uncut: UncutSpace): Analysis {
            val used = usage(mask, width, height)
            val tabs = ArrayList<List<TabCut>>()
            val missing = ArrayList<List<TabSide>>()
            for (r in regions.indices) {
                if (used[r].none { it }) { tabs += emptyList<TabCut>(); missing += emptyList<TabSide>(); continue }
                val (t, m) = tabsFor(r, uncut)
                tabs += t; missing += m
            }
            return Analysis(used, tabs, missing)
        }
    }

    private fun layoutFor(separation: Separation, options: PrintOptions, sheetCount: Int): Layout {
        require(separation.width > 0 && separation.height > 0 && separation.sheets.isNotEmpty()) { "A print needs a non-empty stencil." }
        val heightMm = options.widthMm * separation.height / separation.width
        if (options.mount != null) {
            val boards = BoardGrid.of(options.widthMm, heightMm, options.mount)
            require(boards.count <= MAX_PAGES) {
                "Printout needs too many boards: ${boards.cols} x ${boards.rows} per sheet. Make the stencil smaller or use bigger boards."
            }
        }
        val layout = Layout(options.widthMm, heightMm, options)
        val pages = sheetCount.toLong() * (layout.regions.size.toLong() * layout.grid.tiles.size + 1) + 1
        require(pages <= MAX_PAGES) {
            "Printout needs too many pages: ${layout.grid.cols} x ${layout.grid.rows} pages per " +
                (if (options.mount != null) "board" else "sheet") + " on ${layout.regions.size} " +
                (if (options.mount != null) "boards" else "sheet") + " is up to $pages in all, more than the $MAX_PAGES allowed. " +
                "Make the stencil smaller or use bigger paper."
        }
        return layout
    }

    private fun checkSheets(separation: Separation, sheets: List<Int>) {
        require(sheets.isNotEmpty()) { "No sheets to print." }
        for (s in sheets) require(s in separation.sheets.indices) { "There is no sheet ${s + 1}." }
    }

    /**
     * How the print divides into pages (and boards) for [sheets] (default all) of [separation]: the grid, and per
     * sheet the printed and blank tiles and boards. Cheap next to [make]: no outlines are traced and no tips found.
     */
    fun plan(separation: Separation, options: PrintOptions, sheets: List<Int> = separation.sheets.indices.toList()): PrintPlan {
        checkSheets(separation, sheets)
        val layout = layoutFor(separation, options, sheets.size)
        val mm = options.widthMm / separation.width
        val perSheet = sheets.map { sheet ->
            val mask = separation.mask(sheet)
            val analysis = layout.analyse(mask, separation.width, separation.height, UncutSpace(mask, separation.width, separation.height, mm))
            val boards = layout.regions.mapIndexed { r, region ->
                val blank = analysis.blank(r)
                val tiles = layout.grid.tiles
                BoardPages(
                    region.board ?: BoardCell(1, 0, 0, region.art), blank,
                    tiles.filterIndexed { t, _ -> analysis.needed(r, t) },
                    // A blank board is skipped whole and counted as a board; a blank piece's tiles are blank pages.
                    if (blank && region.board != null) emptyList() else tiles.filterIndexed { t, _ -> !analysis.needed(r, t) },
                    analysis.tabs[r], analysis.missing[r],
                )
            }
            SheetPages(sheet, boards.flatMap { it.printed }, boards.flatMap { it.skipped }, if (layout.boardGrid == null) emptyList() else boards)
        }
        return PrintPlan(options, layout.grid, perSheet, layout.boardGrid)
    }

    /** The print PDF of [sheets] (default all) of [separation], as one file. */
    fun make(separation: Separation, options: PrintOptions, sheets: List<Int> = separation.sheets.indices.toList()): ByteArray {
        checkSheets(separation, sheets)
        val layout = layoutFor(separation, options, sheets.size)
        val first = layout.lay(0, separation.sheets.size)
        val doc = PdfDoc()
        doc.addPage(first.pageW, first.pageH, Instructions.page(first, layout, sheets.size, separation.sheets.size))

        var tipsUsed = 0
        for (sheet in sheets) {
            // Everything per sheet lives in this iteration, so one sheet's mask and tables are alive at a time.
            val mask = separation.mask(sheet)
            val mmPerPx0 = options.widthMm / separation.width
            val uncut = UncutSpace(mask, separation.width, separation.height, mmPerPx0)
            val analysis = layout.analyse(mask, separation.width, separation.height, uncut)
            val tips = StencilPrintTips.detect(
                mask, separation.width, separation.height, options.widthMm, maxTips = MAX_TIPS - tipsUsed,
            )
            tipsUsed += tips.size
            val mmPerPx = options.widthMm / separation.width
            val outline = Vectorizer.vectorizeSheet(separation, sheet)
            val shapes = outline.shapes.map { Placed(it.rings.map { r -> r.points }, mmPerPx, layout.heightMm) }
            val color = outline.color

            doc.addPage(first.pageW, first.pageH, if (layout.boardGrid == null) first.mapPage(sheet, analysis.used[0], shapes, color, doc)
            else first.boardMapPage(sheet, layout, analysis, shapes, color, doc))

            val required = LinkedHashMap<String, Note>()
            val homes = HashMap<Long, MutableList<Note>>()
            for (tip in tips) {
                val note = layout.note(sheet, tip, mmPerPx)
                required[note.key] = note
                homes.getOrPut(note.region.toLong() * layout.grid.tiles.size + note.tile) { ArrayList() } += note
            }
            val placed = HashSet<String>()
            for (r in layout.regions.indices) {
                if (analysis.blank(r)) continue
                val lay = layout.lay(r, separation.sheets.size, analysis.tabs[r])
                for ((index, tile) in layout.grid.tiles.withIndex()) {
                    if (!analysis.needed(r, index)) continue
                    doc.addPage(
                        lay.pageW, lay.pageH,
                        lay.tilePage(sheet, tile, shapes, color, doc, uncut, homes[r.toLong() * layout.grid.tiles.size + index].orEmpty(), placed),
                    )
                    require(doc.pageCount <= MAX_PAGES) { "Printout needs too many pages" }
                }
            }
            for (notes in required.values.filter { it.key !in placed }.chunked(first.referenceRows)) {
                require(doc.pageCount < MAX_PAGES) { "Printout needs too many reference pages" }
                doc.addPage(first.pageW, first.pageH, first.referencePage(notes, sheet, shapes, color, doc))
            }
        }
        return doc.finish("Stencil Artist - print")
    }

    // ---- Geometry shared by all pages.

    /** A cut shape's rings as path operators in points (y up from the piece's bottom edge), with its extent in mm (y down). */
    internal class Placed(rings: List<List<Point>>, mmPerPx: Double, heightMm: Double) {
        val data: String
        val minX: Double
        val maxX: Double
        val minY: Double
        val maxY: Double

        init {
            val sb = StringBuilder()
            var x0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for ((r, ring) in rings.withIndex()) {
                for ((i, p) in ring.withIndex()) {
                    val x = p.x * mmPerPx
                    val y = p.y * mmPerPx
                    if (r == 0) {
                        x0 = min(x0, x); x1 = max(x1, x)
                        y0 = min(y0, y); y1 = max(y1, y)
                    }
                    sb.append(num(x * K)).append(' ').append(num((heightMm - y) * K)).append(if (i == 0) " m\n" else " l\n")
                }
                sb.append("h\n")
            }
            data = sb.toString()
            minX = x0; maxX = x1; minY = y0; maxY = y1
        }
    }

    /** A cutting tip's text and where its anchor is, in finished-piece mm; [tile] is the index of the tile that owns the anchor. */
    internal class Note(
        val key: String, val sheet: Int, val lines: List<String>, val x: Double, val y: Double,
        val region: Int, val tile: Int, val code: String, val board: Int,
    )

    private class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
        fun overlaps(o: Rect, gap: Double = 1.4): Boolean =
            x < o.x + o.w + gap && x + w + gap > o.x && y < o.y + o.h + gap && y + h + gap > o.y
    }

    /** Summed-area table of cut openings: a rectangle is clear only if it and a buffer around it hold no cut pixel. */
    internal class UncutSpace(mask: BooleanArray, val width: Int, val height: Int, private val mmPerPx: Double) {
        private val stride = width + 1
        private val sums = IntArray((width + 1) * (height + 1))

        init {
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) {
                    if (mask[y * width + x]) row++
                    sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row
                }
            }
        }

        /**
         * The cut pixels in [rect] and the pixels it covers in all (parts of the rectangle off the piece count as
         * uncut), counting the pixels whose centres lie in it.
         */
        fun count(rect: RectMm): Pair<Int, Int> {
            // Pixels whose centres lie in the rectangle.
            val x0 = ceil(rect.x0 / mmPerPx - 0.5).toInt()
            val x1 = ceil(rect.x1 / mmPerPx - 0.5).toInt()
            val y0 = ceil(rect.y0 / mmPerPx - 0.5).toInt()
            val y1 = ceil(rect.y1 / mmPerPx - 0.5).toInt()
            val cx0 = x0.coerceIn(0, width)
            val cx1 = x1.coerceIn(0, width)
            val cy0 = y0.coerceIn(0, height)
            val cy1 = y1.coerceIn(0, height)
            val cut = sums[cy1 * stride + cx1] - sums[cy0 * stride + cx1] - sums[cy1 * stride + cx0] + sums[cy0 * stride + cx0]
            return cut to maxOf(1, (x1 - x0) * (y1 - y0))
        }

        /** Whether the mm rectangle, widened by [buffer] mm and one pixel (the outlines are simplified), is wholly on the piece and cut-free. */
        fun clear(x: Double, y: Double, w: Double, h: Double, buffer: Double): Boolean {
            val x0 = floor((x - buffer) / mmPerPx).toInt() - 1
            val x1 = ceil((x + w + buffer) / mmPerPx).toInt() + 1
            val y0 = floor((y - buffer) / mmPerPx).toInt() - 1
            val y1 = ceil((y + h + buffer) / mmPerPx).toInt() + 1
            if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) return false
            return sums[y1 * stride + x1] - sums[y0 * stride + x1] - sums[y1 * stride + x0] + sums[y0 * stride + x0] == 0
        }
    }

    private fun num(v: Double) = Decimal.format(v, 3)

    private fun text(sb: StringBuilder, size: Double, x: Double, y: Double, s: String, bold: Boolean = false) {
        sb.append("BT\n/").append(if (bold) "F2" else "F1").append(' ').append(Decimal.format(size, 2)).append(" Tf\n")
            .append(num(x)).append(' ').append(num(y)).append(" Td\n(").append(PdfText.escape(s)).append(") Tj\nET\n")
    }

    private fun rgb(argb: Int): String =
        listOf(argb shr 16 and 255, argb shr 8 and 255, argb and 255).joinToString(" ") { Decimal.format(it / 255.0, 4) }

    private fun fillAndOutline(sb: StringBuilder, p: Placed, color: Int, doc: PdfDoc, lineWidth: Double) {
        sb.append("q\n")
        doc.alphaState(color)?.let { sb.append("/$it gs\n") }
        sb.append(rgb(color)).append(" rg\n").append(p.data).append("f*\nQ\n")
        sb.append("q\n$INK RG\n${Decimal.format(lineWidth, 4)} w\n1 j\n").append(p.data).append("S\nQ\n")
    }

    /**
     * Everything about the page and the piece that the page builders share. The tiles of [grid] are local to a
     * region whose top-left is ([ox], [oy]) in finished-piece mm; [piece] is the whole piece. When mounting, [art]
     * is the part of the piece this board prints (the cut area) and [bounds] the whole board, border included.
     */
    internal class Lay(
        val grid: TilePlan, private val sheetTotal: Int, val piece: RectMm = RectMm(0.0, 0.0, grid.widthMm, grid.heightMm),
        val ox: Double = 0.0, val oy: Double = 0.0, val art: RectMm? = null, val board: BoardCell? = null,
        private val boardTotal: Int = 0, val bounds: RectMm? = null, val tabs: List<TabCut> = emptyList(),
    ) {
        val spec = grid.spec
        val pageW = spec.pageWidthMm * K
        val pageH = spec.pageHeightMm * K
        val widthMm = piece.x1
        val heightMm = piece.y1
        val left = MARGIN_MM * K
        val right = pageW - left
        val top = pageH - left
        val bottom = left
        val winW = right - left
        val winH = top - bottom

        /** Rows of notes a reference page holds. */
        val referenceRows: Int get() = max(1, ((winH - 42.0) / 153.0).toInt())

        private fun label(sheet: Int, tile: Tile) =
            if (board == null) "Sheet ${sheet + 1} of $sheetTotal - Row ${tile.row + 1} Col ${tile.col + 1} (${tileCode(tile)})"
            else "Sheet ${sheet + 1} of $sheetTotal - Board ${board.number} of $boardTotal - ${tileCode(tile)}"

        private fun fx(tile: Tile) = ox + tile.clip.x0
        private fun fy(tile: Tile) = oy + tile.clip.y0

        /** Where the part of this page that stays visible ends across: the right-hand page's paper edge lies over the rest. */
        private fun visibleRight(tile: Tile) =
            if (tile.col < grid.cols - 1) ox + grid.tile(tile.row, tile.col + 1).clip.x0 - MARGIN_MM else fx(tile) + spec.printableWidthMm

        /** Where the part of this page that stays visible ends down: the lower page's paper edge lies over the rest. */
        private fun visibleBottom(tile: Tile) =
            if (tile.row < grid.rows - 1) oy + grid.tile(tile.row + 1, tile.col).clip.y0 - MARGIN_MM else fy(tile) + spec.printableHeightMm

        /** A point in finished mm to page points. */
        private fun px(tile: Tile, mm: Double) = (MARGIN_MM + mm - fx(tile)) * K
        private fun py(tile: Tile, mm: Double) = pageH - (MARGIN_MM + mm - fy(tile)) * K

        fun tilePage(
            sheet: Int, tile: Tile, shapes: List<Placed>, color: Int, doc: PdfDoc,
            uncut: UncutSpace, notes: List<Note>, placed: MutableSet<String>,
        ): String {
            val sb = StringBuilder()
            val x0 = fx(tile)
            val y0 = fy(tile)
            val x1 = x0 + spec.printableWidthMm
            val y1 = y0 + spec.printableHeightMm
            // The art is clipped to the printable area and, on a board, to the board's cut area.
            val cx0 = max(x0, art?.x0 ?: x0)
            val cy0 = max(y0, art?.y0 ?: y0)
            val cx1 = min(x1, art?.x1 ?: x1)
            val cy1 = min(y1, art?.y1 ?: y1)
            sb.append("q\n")
            if (cx1 <= cx0 || cy1 <= cy0) {
                // Nothing of the piece falls on this page (a corner page A1 with nothing to cut): an empty clip draws nothing.
                sb.append(num(left)).append(' ').append(num(bottom)).append(" 0 0 re W n\n")
            } else {
                sb.append(num(px(tile, cx0))).append(' ').append(num(py(tile, cy1))).append(' ').append(num((cx1 - cx0) * K)).append(' ')
                    .append(num((cy1 - cy0) * K)).append(" re W n\n")
                sb.append("1 0 0 1 ").append(num((MARGIN_MM - x0) * K)).append(' ')
                    .append(num(pageH - (MARGIN_MM - y0 + heightMm) * K)).append(" cm\n")
                for (s in shapes) {
                    if (s.maxX < cx0 || s.minX > cx1 || s.maxY < cy0 || s.minY > cy1) continue
                    fillAndOutline(sb, s, color, doc, 0.7)
                }
            }
            sb.append("Q\n")

            for (tab in tabs) drawTab(sb, tile, tab, shapes, color, doc)

            // The guides for the pages that will be laid on top: finished-piece geometry (see [guides]).
            sb.append("q\n0 G\n").append(num(MARK_WIDTH_MM * K)).append(" w\n0 J\n0 j\n")
            for (m in guides(grid, tile)) {
                for ((i, pt) in m.withIndex()) {
                    sb.append(num(px(tile, pt.x + ox))).append(' ').append(num(py(tile, pt.y + oy))).append(if (i == 0) " m " else " l ")
                }
                sb.append("S\n")
            }
            sb.append("Q\n")

            val used = ArrayList<Rect>()

            // The label first, then the tips around it.
            val labelText = label(sheet, tile)
            val lw = PdfText.width(labelText, LABEL_PT) / K + 1.0
            val lh = LABEL_PT / K + 1.0
            val labelBox = place(tile, lw, lh, 2.0, 1.0, uncut, emptyList())
                ?: place(tile, lw, lh, 1.0, 0.0, uncut, emptyList())
                ?: Rect(x0 + INSET, y0 + INSET, lw, lh)
            sb.append("0 g\n")
            text(sb, LABEL_PT, px(tile, labelBox.x) + 0.5 * K, py(tile, labelBox.y + labelBox.h) + 0.5 * K, labelText)

            used += labelBox
            if (notes.isNotEmpty()) {
                val candidates = candidates(tile, TIP_W, TIP_H, 12.0 / K, TIP_CLEARANCE, uncut, false)
                for (note in notes) {
                    if (note.lines.withIndex().any { (i, l) -> PdfText.width(l, if (i == 0) 7.4 else 7.0, i == 0) + 4.0 > TIP_W * K }) continue
                    var best: Rect? = null
                    var bestDist = Double.POSITIVE_INFINITY
                    for (r in candidates) {
                        if (used.any { it.overlaps(r) }) continue
                        val dx = r.x + r.w / 2 - note.x
                        val dy = r.y + r.h / 2 - note.y
                        val d = dx * dx + dy * dy
                        if (d < bestDist) { bestDist = d; best = r }
                    }
                    val r = best ?: continue
                    used += r
                    placed += note.key
                    // Muted words and nothing else: no boxes, leaders or arrows, so nothing can cross a cut.
                    sb.append("q 0.42 g\n")
                    for ((i, line) in note.lines.withIndex()) {
                        text(sb, if (i == 0) 7.4 else 7.0, px(tile, r.x) + 2.0, py(tile, r.y) - 11.0 - i * 11.0, line, bold = i == 0)
                    }
                    sb.append("Q\n")
                }
            }
            return sb.toString()
        }

        /**
         * A tab, a cut in the board's border: the sheet's own shapes clipped to the tab rectangle, filled and outlined like
         * the rest of the cut, inside a thin dashed frame, with a tiny "TAB" on blank paper beside it.
         */
        private fun drawTab(sb: StringBuilder, tile: Tile, tab: TabCut, shapes: List<Placed>, color: Int, doc: PdfDoc) {
            val x0 = fx(tile)
            val y0 = fy(tile)
            val x1 = x0 + spec.printableWidthMm
            val y1 = y0 + spec.printableHeightMm
            val r = tab.rect
            val cx0 = max(x0, r.x0)
            val cy0 = max(y0, r.y0)
            val cx1 = min(x1, r.x1)
            val cy1 = min(y1, r.y1)
            if (cx1 <= cx0 || cy1 <= cy0) return
            sb.append("q\n").append(num(px(tile, cx0))).append(' ').append(num(py(tile, cy1))).append(' ').append(num((cx1 - cx0) * K)).append(' ')
                .append(num((cy1 - cy0) * K)).append(" re W n\n")
            sb.append("1 0 0 1 ").append(num((MARGIN_MM - x0) * K)).append(' ').append(num(pageH - (MARGIN_MM - y0 + heightMm) * K)).append(" cm\n")
            for (s in shapes) {
                if (s.maxX < cx0 || s.minX > cx1 || s.maxY < cy0 || s.minY > cy1) continue
                fillAndOutline(sb, s, color, doc, 0.7)
            }
            sb.append("Q\n")
            sb.append("q\n0 G\n0.3 w\n[2 2] 0 d\n").append(num(px(tile, cx0))).append(' ').append(num(py(tile, cy1))).append(' ')
                .append(num((cx1 - cx0) * K)).append(' ').append(num((cy1 - cy0) * K)).append(" re S\nQ\n")
            val size = 5.0
            val w = PdfText.width("TAB", size) / K
            val h = size / K
            val lx = if (tab.side == TabSide.LEFT) r.x0 - 1.5 - w else r.x0 + 2.0
            val ly = if (tab.side == TabSide.LEFT) r.y0 + 4.0 else r.y0 - 1.5 - h
            if (lx >= x0 + 0.5 && lx + w <= x1 - 0.5 && ly >= y0 + 0.5 && ly + h <= y1 - 0.5) {
                sb.append("0 g\n")
                text(sb, size, px(tile, lx), py(tile, ly + h) + 0.4 * K, "TAB")
            }
        }

        /**
         * Boxes of [w] x [h] mm inside [tile]'s printable area, inset, that are on the piece and clear (or, if [beyondOk],
         * wholly beyond it). On a board a box on the piece must also lie within the board's cut area.
         */
        private fun candidates(tile: Tile, w: Double, h: Double, step: Double, buffer: Double, uncut: UncutSpace, beyondOk: Boolean): List<Rect> {
            val out = ArrayList<Rect>()
            val x0 = fx(tile) + INSET
            val y0 = fy(tile) + INSET
            val xMax = visibleRight(tile) - INSET - w
            val yMax = visibleBottom(tile) - INSET - h
            var y = y0
            while (y <= yMax) {
                var x = x0
                while (x <= xMax) {
                    if (ok(x, y, w, h, buffer, uncut, beyondOk)) out += Rect(x, y, w, h)
                    x += step
                }
                y += step
            }
            return out
        }

        private fun ok(x: Double, y: Double, w: Double, h: Double, buffer: Double, uncut: UncutSpace, beyondOk: Boolean): Boolean {
            bounds?.let { if (x < it.x0 || y < it.y0 || x + w > it.x1 || y + h > it.y1) return false }
            val a = art ?: piece
            if (x >= a.x0 && y >= a.y0 && x + w <= a.x1 && y + h <= a.y1) return uncut.clear(x, y, w, h, buffer)
            return beyondOk && (x >= widthMm || y >= heightMm)
        }

        private fun place(tile: Tile, w: Double, h: Double, step: Double, buffer: Double, uncut: UncutSpace, avoid: List<Rect>): Rect? {
            val x0 = fx(tile) + INSET
            val y0 = fy(tile) + INSET
            val xMax = visibleRight(tile) - INSET - w
            val yMax = visibleBottom(tile) - INSET - h
            var y = y0
            while (y <= yMax) {
                var x = x0
                while (x <= xMax) {
                    if (ok(x, y, w, h, buffer, uncut, true) && avoid.none { it.overlaps(Rect(x, y, w, h)) }) return Rect(x, y, w, h)
                    x += step
                }
                y += step
            }
            return null
        }

        fun mapPage(sheet: Int, used: BooleanArray, shapes: List<Placed>, color: Int, doc: PdfDoc): String {
            val sb = StringBuilder("0 g\n")
            var y = top - 14.0
            text(sb, 13.0, left, y, "Sheet ${sheet + 1} of $sheetTotal - assembly map", bold = true)
            val printed = used.count { it }
            val blank = used.size - printed
            val page = spec
            val lines = listOf(
                "Finished size: ${Decimal.format(widthMm, 1)} x ${Decimal.format(heightMm, 1)} mm (${Decimal.format(widthMm / 25.4, 2)} x ${Decimal.format(heightMm / 25.4, 2)} in)",
                "Pages: ${grid.cols} across x ${grid.rows} down = ${used.size}; $printed printed, $blank skipped as blank (nothing to cut)",
                "Paper: ${page.name} ${Decimal.format(page.pageWidthMm, 1)} x ${Decimal.format(page.pageHeightMm, 1)} mm, ${Decimal.format(MARGIN_MM, 0)} mm unprintable border; sheets overlap ${Decimal.format(PAPER_OVERLAP_MM, 0)} mm, leaving a ${Decimal.format(MARGIN_MM, 0)} mm blank strip at every join",
                "Print at 100%. Work left to right, top to bottom; lay each later page on top and put its paper edge on the guide marks.",
            )
            for ((i, l) in lines.withIndex()) {
                for (w in PdfText.wrap(l, winW, if (i == 0) 10.0 else 8.5)) {
                    y -= 13.0
                    text(sb, if (i == 0) 10.0 else 8.5, left, y, w)
                }
            }
            val boxTop = y - 14.0
            val boxH = boxTop - bottom
            val k = min(winW / widthMm, boxH / heightMm)
            val c = k / K
            val oy = boxTop - heightMm * k
            sb.append("q\n1 0 0 1 ").append(num(left)).append(' ').append(num(oy)).append(" cm\n")
            sb.append(Decimal.format(c, 6)).append(" 0 0 ").append(Decimal.format(c, 6)).append(" 0 0 cm\n")
            for (s in shapes) fillAndOutline(sb, s, color, doc, 0.5 / c)
            sb.append("Q\n")
            sb.append("q\n0.5 w\n0.45 G\n")
            sb.append(num(left)).append(' ').append(num(oy)).append(' ').append(num(widthMm * k)).append(' ').append(num(heightMm * k)).append(" re S\n")
            sb.append("[2 2] 0 d\n")
            for (t in grid.tiles) {
                val r = net(grid, t)
                sb.append(num(left + r.x0 * k)).append(' ').append(num(boxTop - r.y1 * k)).append(' ')
                    .append(num(r.width * k)).append(' ').append(num(r.height * k)).append(" re S\n")
            }
            sb.append("Q\n")
            for ((i, t) in grid.tiles.withIndex()) {
                val r = net(grid, t)
                val cw = r.width * k
                val ch = r.height * k
                if (!used[i]) {
                    // Hatched: skipped, nothing to cut on this page.
                    sb.append("q\n").append(num(left + r.x0 * k)).append(' ').append(num(boxTop - r.y1 * k)).append(' ')
                        .append(num(cw)).append(' ').append(num(ch)).append(" re W n\n0.6 G\n0.3 w\n")
                    val gap = max(4.0, (cw + ch) / 60)
                    var d = 0.0
                    while (d < cw + ch) {
                        val xa = left + r.x0 * k + d
                        val ya = boxTop - r.y1 * k
                        sb.append(num(xa)).append(' ').append(num(ya)).append(" m ")
                            .append(num(xa - ch)).append(' ').append(num(ya + ch)).append(" l S\n")
                        d += gap
                    }
                    sb.append("Q\n")
                }
                val fs = (min(cw, ch) / 5).coerceIn(5.0, 12.0)
                // A white plate keeps the code legible over a black shape; the map is not a cutting template.
                val code = tileCode(t) + if (used[i]) "" else " blank"
                val lx = left + r.x0 * k + 3.0
                val ly = boxTop - r.y0 * k - fs - 2.0
                sb.append("q\n1 g\n").append(num(lx - 1.5)).append(' ').append(num(ly - 2.0)).append(' ')
                    .append(num(PdfText.width(code, fs, true) + 3.0)).append(' ').append(num(fs + 4.0)).append(" re f\nQ\n0.1 g\n")
                text(sb, fs, lx, ly, code, bold = true)
            }
            return sb.toString()
        }

        /** The assembly map when mounting: every board (border and cut area), blank ones hatched, and the pages on each. */
        fun boardMapPage(sheet: Int, layout: Layout, analysis: Layout.Analysis, shapes: List<Placed>, color: Int, doc: PdfDoc): String {
            val bg = checkNotNull(layout.boardGrid)
            val border = checkNotNull(layout.options.mount).borderMm
            val sb = StringBuilder("0 g\n")
            var y = top - 14.0
            text(sb, 13.0, left, y, "Sheet ${sheet + 1} of $sheetTotal - assembly map", bold = true)
            val blankBoards = layout.regions.indices.count { analysis.blank(it) }
            val pagesPrinted = layout.regions.indices.sumOf { r -> layout.grid.tiles.indices.count { analysis.needed(r, it) } }
            val b = checkNotNull(layout.options.mount).board
            val lines = listOf(
                "Finished size: ${Decimal.format(widthMm, 1)} x ${Decimal.format(heightMm, 1)} mm (${Decimal.format(widthMm / 25.4, 2)} x ${Decimal.format(heightMm / 25.4, 2)} in)",
                "Boards: ${bg.count} (${bg.cols} across x ${bg.rows} down, ${if (bg.landscape) "landscape" else "portrait"}) of ${b.name}, " +
                    "${Decimal.format(border / 25.4, 2)} in (${Decimal.format(border, 1)} mm) uncut border on every side; ${bg.count - blankBoards} printed, $blankBoards skipped as blank",
                "Pages: $pagesPrinted on this sheet, ${spec.name} ${Decimal.format(spec.pageWidthMm, 1)} x ${Decimal.format(spec.pageHeightMm, 1)} mm. Each board's pages never cross a board edge.",
                "Print at 100%. Assemble each board's pages from A1 and glue A1's top and left paper edges flush with the board's top and left edges.",
            )
            for ((i, l) in lines.withIndex()) {
                for (w in PdfText.wrap(l, winW, if (i == 0) 10.0 else 8.5)) {
                    y -= 13.0
                    text(sb, if (i == 0) 10.0 else 8.5, left, y, w)
                }
            }
            val boxTop = y - 14.0
            val boxH = boxTop - bottom
            val extW = bg.cols * bg.cutWidthMm + 2 * border
            val extH = bg.rows * bg.cutHeightMm + 2 * border
            val k = min(winW / extW, boxH / extH)
            val c = k / K
            val ox0 = left + border * k // map position of finished x = 0
            val oyTop = boxTop - border * k // map position of finished y = 0
            sb.append("q\n1 0 0 1 ").append(num(ox0)).append(' ').append(num(oyTop - heightMm * k)).append(" cm\n")
            sb.append(Decimal.format(c, 6)).append(" 0 0 ").append(Decimal.format(c, 6)).append(" 0 0 cm\n")
            for (s in shapes) fillAndOutline(sb, s, color, doc, 0.5 / c)
            sb.append("Q\n")
            for ((r, region) in layout.regions.withIndex()) {
                val cell = checkNotNull(region.board)
                val cut = cell.cut
                val blank = analysis.blank(r)
                // Whole board, border included, light and dashed; its cut area solid.
                sb.append("q\n0.4 w\n0.7 G\n[3 3] 0 d\n").append(num(ox0 + (cut.x0 - border) * k)).append(' ')
                    .append(num(oyTop - (cut.y1 + border) * k)).append(' ').append(num((cut.width + 2 * border) * k)).append(' ')
                    .append(num((cut.height + 2 * border) * k)).append(" re S\nQ\n")
                sb.append("q\n0.8 w\n0.3 G\n").append(num(ox0 + cut.x0 * k)).append(' ').append(num(oyTop - cut.y1 * k)).append(' ')
                    .append(num(cut.width * k)).append(' ').append(num(cut.height * k)).append(" re S\nQ\n")
                if (blank) {
                    val cw = cut.width * k
                    val ch = cut.height * k
                    sb.append("q\n").append(num(ox0 + cut.x0 * k)).append(' ').append(num(oyTop - cut.y1 * k)).append(' ')
                        .append(num(cw)).append(' ').append(num(ch)).append(" re W n\n0.6 G\n0.3 w\n")
                    val gap = max(4.0, (cw + ch) / 60)
                    var d = 0.0
                    while (d < cw + ch) {
                        val xa = ox0 + cut.x0 * k + d
                        val ya = oyTop - cut.y1 * k
                        sb.append(num(xa)).append(' ').append(num(ya)).append(" m ").append(num(xa - ch)).append(' ').append(num(ya + ch)).append(" l S\n")
                        d += gap
                    }
                    sb.append("Q\n")
                } else {
                    // The fitted pages: each printed page's printable area, overlapping its neighbours.
                    sb.append("q\n0.25 w\n0.55 G\n[1.5 1.5] 0 d\n")
                    for ((t, tile) in layout.grid.tiles.withIndex()) {
                        if (!analysis.needed(r, t)) continue
                        val wx0 = region.ox + tile.clip.x0
                        val wy0 = region.oy + tile.clip.y0
                        sb.append(num(ox0 + wx0 * k)).append(' ').append(num(oyTop - (wy0 + spec.printableHeightMm) * k)).append(' ')
                            .append(num(spec.printableWidthMm * k)).append(' ').append(num(spec.printableHeightMm * k)).append(" re S\n")
                    }
                    sb.append("Q\n")
                    // Tabs: small cut windows in the border, drawn solid.
                    sb.append("q\n0.2 g\n0 G\n0.4 w\n")
                    for (tab in analysis.tabs[r]) {
                        val t = tab.rect
                        sb.append(num(ox0 + t.x0 * k)).append(' ').append(num(oyTop - t.y1 * k)).append(' ')
                            .append(num(max(t.width * k, 1.5))).append(' ').append(num(max(t.height * k, 1.5))).append(" re B\n")
                    }
                    sb.append("Q\n")
                    for ((i, side) in analysis.missing[r].withIndex()) {
                        val note = "no ${side.name.lowercase()} tab"
                        val lx = ox0 + cut.x0 * k + 4.0
                        val ly = oyTop - cut.y1 * k + 5.0 + i * 8.0
                        sb.append("q\n1 g\n").append(num(lx - 1.0)).append(' ').append(num(ly - 1.5)).append(' ').append(num(PdfText.width(note, 6.0) + 2.0)).append(" 8 re f\nQ\n0.1 g\n")
                        text(sb, 6.0, lx, ly, note)
                    }
                }
                val big = (min(cut.width, cut.height) * k / 4).coerceIn(8.0, 18.0)
                val label = "Board ${cell.number}" + if (blank) " blank" else ""
                sb.append("q\n1 g\n").append(num(ox0 + cut.x0 * k + 2.0)).append(' ').append(num(oyTop - cut.y0 * k - big - 4.0)).append(' ')
                    .append(num(PdfText.width(label, big, true) + 4.0)).append(' ').append(num(big + 5.0)).append(" re f\nQ\n0.1 g\n")
                text(sb, big, ox0 + cut.x0 * k + 4.0, oyTop - cut.y0 * k - big - 1.0, label, bold = true)
            }
            return sb.toString()
        }

        fun referencePage(notes: List<Note>, sheet: Int, shapes: List<Placed>, color: Int, doc: PdfDoc): String {
            val sb = StringBuilder("0 g\n")
            text(sb, 12.0, left, top - 14.0, "CONTEXTUAL CUTTING NOTES", bold = true)
            text(sb, 9.0, left, top - 32.0, "REFERENCE ONLY - DO NOT CUT FROM THIS PAGE", bold = true)
            text(sb, 8.0, left, top - 46.0, "Details shown at actual scale. Coordinates are mm from the stencil's top-left.")
            for ((row, note) in notes.withIndex()) {
                val refX = left + 8.0
                val refTop = top - 84.0 - row * 153.0
                val cropW = min(120.0 / K, widthMm)
                val cropH = min(105.0 / K, heightMm)
                val cx = (note.x - cropW / 2).coerceIn(0.0, widthMm - cropW)
                val cy = (note.y - cropH / 2).coerceIn(0.0, heightMm - cropH)
                // The excerpt and the words are separate, so no glyph can touch the cut geometry.
                sb.append("q\n").append(num(refX)).append(' ').append(num(refTop - cropH * K)).append(' ')
                    .append(num(cropW * K)).append(' ').append(num(cropH * K)).append(" re W n\n")
                sb.append("1 0 0 1 ").append(num(refX - cx * K)).append(' ')
                    .append(num(refTop - cropH * K - (heightMm - (cy + cropH)) * K)).append(" cm\n")
                for (s in shapes) {
                    if (s.maxX < cx || s.minX > cx + cropW || s.maxY < cy || s.minY > cy + cropH) continue
                    fillAndOutline(sb, s, color, doc, 0.7)
                }
                sb.append("Q\n0 g\n")
                val labelX = left + 148.0
                text(sb, 8.5, labelX, refTop - 11.0, "SHEET ${note.sheet + 1} - LOCAL DETAIL", bold = true)
                for ((i, line) in note.lines.withIndex()) {
                    text(sb, if (i == 0) 9.5 else 9.0, labelX, refTop - 30.0 - i * 17.0, line, bold = i == 0)
                }
                text(sb, 7.5, labelX, refTop - 98.0, (if (note.board > 0) "Board ${note.board}, tile ${note.code}" else "Tile ${note.code}") + ". NOT A CUTTING TEMPLATE")
            }
            return sb.toString()
        }
    }

    // ---- The instructions page.

    internal object Instructions {
        private const val LEAD = 12.5
        private const val SIZE = 9.5

        fun page(lay: Lay, plan: Layout, sheetsInFile: Int, sheetTotal: Int): String {
            val landscape = lay.winW >= lay.winH * 1.2
            val vw = if (landscape) 780.0 else 540.0
            val dry = StringBuilder()
            val used = draw(dry, lay, plan, vw, landscape, sheetsInFile)
            val barReserve = 34.0
            val scale = min(min(lay.winW / vw, (lay.winH - barReserve) / used), 1.5)
            val sb = StringBuilder("0 g\n")
            sb.append("q\n").append(Decimal.format(scale, 5)).append(" 0 0 ").append(Decimal.format(scale, 5)).append(' ')
                .append(num(lay.left)).append(' ').append(num(lay.top)).append(" cm\n")
            draw(sb, lay, plan, vw, landscape, sheetsInFile)
            sb.append("Q\n")
            // True-size check bar at the foot of the page, never scaled.
            val bar = if (lay.winW >= 110.0 * K) 100.0 else 50.0
            val by = lay.bottom + 22.0
            sb.append("q\n0 G\n0.8 w\n")
            sb.append(num(lay.left)).append(' ').append(num(by)).append(" m ").append(num(lay.left + bar * K)).append(' ').append(num(by)).append(" l S\n")
            for (x in listOf(lay.left, lay.left + bar * K)) {
                sb.append(num(x)).append(' ').append(num(by - 4)).append(" m ").append(num(x)).append(' ').append(num(by + 4)).append(" l S\n")
            }
            sb.append("Q\n")
            text(sb, 8.0, lay.left, by - 14.0, "This bar must measure exactly ${bar.roundToInt()} mm. If it does not, the page was scaled: print again at 100%.")
            return sb.toString()
        }

        private fun inches(mm: Double) = Decimal.format(mm / PhysicalSize.MM_PER_INCH, 2)

        private fun steps(sheetsInFile: Int, plan: Layout): List<Pair<String, String>> {
            val mount = plan.options.mount
            val print = "PRINT AT 100%" to "In the print dialog choose \"Actual size\" or \"Scale 100%\". Never choose \"Fit to page\", " +
                "\"Shrink to fit\" or \"Scale to printable area\": they change the size of your stencil. Check the bar at the foot of this page."
            val lay = "LAY THE PAGES" to "Nothing is cut. Work left to right, top to bottom, starting at A1" + (if (mount != null) " of one board, then do the same for each board" else "") +
                ". Lay each later page ON TOP of the pages already in place, as it is. The page underneath prints thin guide lines and corner marks " +
                "showing exactly where the top page's paper edges go: slide the top page until each of its paper edges touches the guide beside it. " +
                "The guides stay visible along the edge as a check. Marks appear only where two pages overlap."
            val tape = "TAPE" to "When every paper edge is on its guide, tape the join."
            val strip = "THE ${Decimal.format(MARGIN_MM, 0)} MM STRIP" to "At every join there is a ${Decimal.format(MARGIN_MM, 0)} mm strip of blank paper: it is the " +
                "top page's unprinted margin lying over the page beneath. The artwork on both sides is exactly where it belongs; nothing is shifted " +
                "or stretched to hide the strip. Continue each cut line straight across it" + (if (mount != null) ", and any tab too" else "") + "."
            val repeat = "REPEAT FOR EVERY SHEET" to if (sheetsInFile > 1) "This file holds $sheetsInFile sheets. Each is its own stencil with its own map; " +
                "assemble each one separately and do not mix pages from different sheets."
            else "Each sheet of a stencil is its own file section; if you print more sheets later, assemble each one separately."
            if (mount == null) return listOf(
                print,
                "FIND YOUR PAGES" to "Each sheet starts with an assembly map: the grid of pages, each with a short code (column letter, " +
                    "row number: A1, B1, A2 ...). Pages with nothing to cut are left out and marked blank. Every page has its sheet and code " +
                    "printed small on uncut material.",
                lay, tape, strip, repeat,
                "CUT" to "The dark outline is the cut. Grey notes (ISLAND, FINE DETAIL) sit only on material that stays. Pages marked REFERENCE ONLY " +
                    "show details too close to a cut for a note; they are not cutting templates.",
            )
            val bg = checkNotNull(plan.boardGrid)
            val border = mount.borderMm
            return listOf(
                print,
                "GET THE BOARD" to "Mount each sheet on ${mount.board.name} (${inches(mount.board.widthMm)} x ${inches(mount.board.heightMm)} in): " +
                    "heavyweight poster board, or 4-ply or 6-ply railroad board. Thin poster board tears. Each sheet needs ${bg.count} boards " +
                    "(${bg.cols} across x ${bg.rows} down, ${if (bg.landscape) "landscape" else "portrait"}); a board with nothing to cut is marked blank on the " +
                    "map and needs no board. Every board keeps ${inches(border)} in (${Decimal.format(border, 1)} mm) of uncut border on all four sides.",
                "FIND YOUR PAGES" to "Each sheet starts with an assembly map showing its boards and the pages on each. Every page says its sheet, its board " +
                    "and its code (column letter, row number within the board: A1, B1 ...). A1 is the board's top-left corner page and is always " +
                    "printed. Pages are fitted to the board, overlapping equally. A page never crosses a board edge, and guides only join pages " +
                    "of the same board.",
                lay, tape, strip,
                "GLUE" to "Lay the taped pages on the board and glue them down. The pages are spread across the board so the paper edges of the " +
                    "outermost pages lie on the board's edges on every side: square any outer page, not only A1, against the board's corner or edge " +
                    "(square corners make that easy to see by eye). The stencil starts ${inches(border)} in (${Decimal.format(border, 1)} mm) in from the " +
                    "board's edges; the rest of each page is blank paper over the border. Nothing overhangs the board.",
                "CUT" to "Cut through the paper and the board together along the dark outline. Grey notes (ISLAND, FINE DETAIL) sit only on " +
                    "material that stays. Pages marked REFERENCE ONLY show details too close to a cut for a note; they are not cutting templates.",
                "PAINT BOARD BY BOARD" to "Paint left to right, top to bottom. Spray board 1 first. Each later board has small cut-out tabs (marked TAB) " +
                    "in its left and top border, cut to the same artwork the neighbouring board cuts there. Hold the board so the paint already on the " +
                    "wall shows exactly through its tabs, tape it, then spray. Paint that lands through a tab falls where that colour belongs anyway, " +
                    "so the overlap does not matter. A board with no tab on a side is noted on the map.",
                repeat,
            )
        }

        /** Draws the page at 1:1 in a [vw]-wide box whose top-left is the origin (y down), returns the height used. */
        private fun draw(sb: StringBuilder, lay: Lay, plan: Layout, vw: Double, twoCol: Boolean, sheetsInFile: Int): Double {
            var y = 18.0
            text(sb, 16.0, 0.0, -y, "How to put the pages together", bold = true)
            y += 18.0
            val page = lay.spec
            val bg = plan.boardGrid
            val summary = if (bg == null) "${lay.grid.cols} x ${lay.grid.rows} pages per sheet on ${page.name} " +
                "${Decimal.format(page.pageWidthMm, 1)} x ${Decimal.format(page.pageHeightMm, 1)} mm. These steps apply to every sheet in this file."
            else "${bg.count} boards per sheet, each up to ${lay.grid.cols} x ${lay.grid.rows} pages on ${page.name} " +
                "${Decimal.format(page.pageWidthMm, 1)} x ${Decimal.format(page.pageHeightMm, 1)} mm. These steps apply to every sheet in this file."
            val tw = if (twoCol) vw * 0.56 else vw
            for (l in PdfText.wrap(summary, tw, SIZE)) { text(sb, SIZE, 0.0, -y, l); y += LEAD }
            y += 6.0
            for ((n, step) in steps(sheetsInFile, plan).withIndex()) {
                text(sb, SIZE, 0.0, -y, "${n + 1}.", bold = true)
                text(sb, SIZE, 16.0, -y, step.first, bold = true)
                y += LEAD
                for (l in PdfText.wrap(step.second, tw - 16.0, SIZE)) { text(sb, SIZE, 16.0, -y, l); y += LEAD }
                y += 4.0
            }
            val textEnd = y
            if (bg == null) {
                val dw = if (twoCol) vw - tw - 24.0 else min(vw, 420.0)
                val dx = if (twoCol) tw + 24.0 else 0.0
                val dTop = if (twoCol) 30.0 else y + 6.0
                val dh = diagram(sb, dx, dTop, dw)
                return max(textEnd, dTop + dh) + 4.0
            }
            val mount = checkNotNull(plan.options.mount)
            if (twoCol) {
                val dw = vw - tw - 24.0
                val dx = tw + 24.0
                val h1 = diagram(sb, dx, 30.0, dw)
                val h2 = boardDiagram(sb, dx, 30.0 + h1 + 24.0, dw * 0.55, mount, bg.landscape, lay.spec)
                return max(textEnd, 30.0 + h1 + 24.0 + h2) + 4.0
            }
            val half = (vw - 24.0) / 2
            val dTop = y + 6.0
            val h1 = diagram(sb, 0.0, dTop, half)
            val h2 = boardDiagram(sb, half + 24.0, dTop, half * 0.8, mount, bg.landscape, lay.spec)
            return dTop + max(h1, h2) + 4.0
        }

        /** One board, schematic: page A1 flush in its top-left corner, the unprinted strip, the border and the cut area. Returns its height. */
        private fun boardDiagram(sb: StringBuilder, x: Double, top: Double, w: Double, mount: MountOptions, landscape: Boolean, spec: TileSpec): Double {
            val b = mount.board.oriented(landscape)
            val scale = w / b.widthMm
            val h = b.heightMm * scale
            val inset = mount.borderMm * scale
            val pw = spec.pageWidthMm * scale
            val ph = spec.pageHeightMm * scale
            val strip = max(2.0, MARGIN_MM * scale) // exaggerated when it would be a hairline
            fun yy(v: Double) = -(top + v)
            sb.append("q\n0.8 w\n0 G\n").append(num(x)).append(' ').append(num(yy(h))).append(' ').append(num(w)).append(' ').append(num(h)).append(" re S\nQ\n")
            sb.append("q\n0.5 w\n0.4 G\n[3 2] 0 d\n").append(num(x + inset)).append(' ').append(num(yy(h - inset))).append(' ')
                .append(num(w - 2 * inset)).append(' ').append(num(h - 2 * inset)).append(" re S\nQ\n")
            // Page A1: its paper edges on the board's top and left edges; its unprinted strip inside them.
            sb.append("q\n0.7 w\n0.15 G\n").append(num(x)).append(' ').append(num(yy(min(ph, h)))).append(' ').append(num(min(pw, w))).append(' ')
                .append(num(min(ph, h))).append(" re S\nQ\n")
            sb.append("q\n0.3 w\n0.55 G\n[1.5 1.5] 0 d\n").append(num(x + strip)).append(' ').append(num(yy(min(ph, h) - strip))).append(' ')
                .append(num(min(pw, w) - strip)).append(' ').append(num(min(ph, h) - strip)).append(" re S\nQ\n0 g\n")
            // Tabs: cut windows in the border along the left and top of the cut area.
            val td = max(3.0, min(TAB_DEPTH_MM, mount.borderMm / 2) * scale)
            val tw = max(6.0, TAB_WIDTH_MM * scale)
            sb.append("q\n0.5 w\n0 G\n0.3 g\n")
            for (f in listOf(0.38, 0.72)) {
                sb.append(num(x + inset - td)).append(' ').append(num(yy(inset + (h - 2 * inset) * f + tw))).append(' ').append(num(td)).append(' ').append(num(tw)).append(" re B\n")
                sb.append(num(x + inset + (w - 2 * inset) * f)).append(' ').append(num(yy(inset))).append(' ').append(num(tw)).append(' ').append(num(td)).append(" re B\n")
            }
            sb.append("Q\n0 g\n")
            text(sb, 6.0, x + inset + 6.0, yy(h / 2 + 10.0), "tabs on the left and top")
            text(sb, 7.0, x + inset + 6.0, yy(h / 2), "cut area (the stencil)")
            text(sb, 7.0, x + 4.0, yy(ph * 0.5), "page A1", bold = true)
            text(sb, 6.0, x + pw + 4.0, yy(ph * 0.25), "paper flush with the")
            text(sb, 6.0, x + pw + 4.0, yy(ph * 0.25 + 8.0), "board's top and left edges")
            text(sb, 7.0, x + 3.0, yy(h - inset / 2 + 2.5), "border ${inches(mount.borderMm)} in")
            text(sb, 7.5, x, yy(h + 11.0), "One board: page A1, unprinted strip, border, cut area, tabs", bold = true)
            text(sb, 7.0, x, yy(h + 21.0), "The strip is ${Decimal.format(MARGIN_MM, 0)} mm. Not to scale.")
            return h + 24.0
        }

        /**
         * Two neighbouring pages, schematic: A underneath, printing guide marks; B on top with its paper edge on them, and
         * B's blank margin labelled as the strip. Returns its height.
         */
        private fun diagram(sb: StringBuilder, x: Double, top: Double, w: Double): Double {
            val h = w * 0.62
            val m = 12.0 // the unprinted margin, exaggerated
            val depth = 6.0 // how far B's paper edge lies inside A's printed area, exaggerated
            val bWide = w * 0.5
            val aWide = w - bWide + m + depth
            val ph = h - 34.0
            fun yy(v: Double) = -(top + v)
            val bLeft = x + aWide - m - depth // B's paper edge
            // A, underneath: paper, printed area (dashed).
            sb.append("q\n0.5 w\n0.4 G\n1 g\n").append(num(x)).append(' ').append(num(yy(ph))).append(' ').append(num(aWide)).append(' ')
                .append(num(ph)).append(" re B\nQ\n")
            sb.append("q\n0.3 w\n0.6 G\n[2 2] 0 d\n").append(num(x + m)).append(' ').append(num(yy(ph - m))).append(' ')
                .append(num(aWide - 2 * m)).append(' ').append(num(ph - 2 * m)).append(" re S\nQ\n")
            // A's guides: ticks just outside B's paper edge, at the top and bottom of A's printed area.
            val gx = bLeft - 1.2
            val arm = 22.0
            sb.append("q\n0 G\n1.6 w\n")
            for (y in listOf(m to 1, ph - m to -1)) {
                sb.append(num(gx)).append(' ').append(num(yy(y.first))).append(" m ").append(num(gx)).append(' ')
                    .append(num(yy(y.first + y.second * arm))).append(" l S\n")
            }
            sb.append("Q\n")
            // B, on top: opaque paper whose left edge sits on the guides; its printed area (dashed) starts after its margin.
            sb.append("q\n0.8 w\n0 G\n1 g\n").append(num(bLeft)).append(' ').append(num(yy(ph))).append(' ').append(num(bWide)).append(' ')
                .append(num(ph)).append(" re B\nQ\n")
            sb.append("q\n0.3 w\n0.6 G\n[2 2] 0 d\n").append(num(bLeft + m)).append(' ').append(num(yy(ph - m))).append(' ')
                .append(num(bWide - 2 * m)).append(' ').append(num(ph - 2 * m)).append(" re S\nQ\n")
            // The blank strip: B's margin over A, shaded.
            sb.append("q\n0.85 g\n").append(num(bLeft)).append(' ').append(num(yy(ph - m))).append(' ').append(num(m)).append(' ')
                .append(num(ph - 2 * m)).append(" re f\nQ\n0 g\n")
            text(sb, 6.5, bLeft + 1.0, yy(ph / 2), "3 mm", bold = true)
            text(sb, 6.5, bLeft + 1.0, yy(ph / 2 + 8.0), "blank")
            text(sb, 7.5, x, yy(ph + 11.0), "A1: underneath, prints the guides", bold = true)
            text(sb, 7.5, bLeft + 4.0, yy(ph + 22.0), "B1: on top, paper edge on the guides", bold = true)
            text(sb, 7.0, x, yy(ph + 32.0), "Not to scale. The shaded strip is B's blank margin lying over A; continue cut lines across it.")
            return h
        }
    }
}
