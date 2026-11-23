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
 *
 * [shopping] is the paint each layer needs, one item per layer in paint order, to be listed on the
 * instructions page; empty (the default) lists nothing, as for a stencil whose layers are not tied to
 * real cans, and then the file is byte for byte what it was before the list existed. Items are taken as
 * already limited to the sheets exported and in paint order (the first layer is the base, sprayed first);
 * a long list continues on extra instruction pages rather than shrinking its text (see [PrintPdf]).
 */
data class PrintOptions(
    val widthMm: Double = 203.2,
    val paper: Paper = Paper.US_LETTER,
    val landscape: Boolean = false,
    val mount: MountOptions? = null,
    val shopping: List<com.hereliesaz.stencilartist.core.paint.ShoppingItem> = emptyList(),
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
 * One board of one sheet: the [printed] pages and [skipped] (blank) pages of the sheet's single page grid that touch it, whether
 * the whole board is [blank] (nothing to cut on it), its [tabs] and the [missingTabs] sides that face an earlier board but had no
 * usable position for a tab.
 */
data class BoardPages(
    val board: BoardCell, val blank: Boolean, val printed: List<Tile>, val skipped: List<Tile>,
    val tabs: List<TabCut> = emptyList(), val missingTabs: List<TabSide> = emptyList(),
)

/**
 * The pages of one sheet: [printed] tiles get a page, [blank] tiles have nothing to cut (no cut and no tab on any board they touch,
 * and not the top-left page when something is printed) and are skipped. When mounting there is ONE grid of pages over the sheet's
 * boards laid edge to edge, and [boards] lists every board with the pages that touch it.
 */
data class SheetPages(val sheet: Int, val printed: List<Tile>, val blank: List<Tile>, val boards: List<BoardPages> = emptyList())

/**
 * How a print divides up before anything is drawn: the page [grid] and, for each requested sheet, which tiles are
 * printed and which are blank. It is computed by the same code that writes the PDF, so a readout built from it is
 * the PDF's own page count (less the instructions page, the maps and any reference pages, which depend on the
 * cutting tips). Without mounting [grid] tiles the piece; when mounting it tiles the board rectangle (the sheet's boards edge to
 * edge) from board 1's top-left corner, and [boards] says how many boards a sheet takes. [instructionPages] is how many pages the
 * instructions take at the front of the file: one, plus one for each page the paint list of [PrintOptions.shopping] spills onto.
 * The file's pages are [instructionPages] + per sheet a map, [printedPages] tiles and any reference pages.
 */
class PrintPlan(
    val options: PrintOptions, val grid: TilePlan, val sheets: List<SheetPages>, val boards: BoardGrid? = null,
    val instructionPages: Int = 1,
) {
    /** Tiles in the grid, printed or not: the pages one sheet would take if nothing were blank. */
    val tilesPerSheet: Int get() = grid.tiles.size

    /** Tile pages over all requested sheets. */
    val printedPages: Int get() = sheets.sumOf { it.printed.size }

    /** Tiles skipped as blank over all requested sheets. */
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
 * Boards. When mounting, a sheet's boards are thought of as placed edge to edge in their grid, forming one rectangle; each shows what it
 * shows on the wall (its border, its cut area with that cell's artwork, its tabs) in its own place in it. ONE page grid, with the normal
 * step, covers the rectangle from board 1's top-left corner, page A1's paper edges on the board's top and left edges. A page that crosses a
 * board's right or bottom edge carries, beyond that edge, the start of the neighbouring board's content, and a DOTTED cut line is
 * printed exactly on the edge across the page: the user glues the page down, cuts the paper along the line and uses the offcut to start
 * the next board. That is the only place paper is cut. The dots are dark, and white inside the sheet's cut shapes when the sheet colour
 * is dark, so every dot contrasts with what lies under it ([cutLineOps]). A line that falls in a 3 mm blank strip is printed on the visible
 * side of the strip with a note of how far past it to cut. At the outer right and bottom edges of the rectangle the offcut is waste. On the
 * wall the boards still overlap by their borders; nothing here changes cut areas, borders, tabs or the board count.
 *
 * The paint list. With [PrintOptions.shopping] the instructions gain a "Paint" block: one row per layer in paint order (a swatch
 * in the can's colour with a dark outline so pale cans show, the layer number, brand, line and name, the maker's code, the barcode
 * digits, how near the match is in words, and "also:" up to 3 cans of the same colour). As many rows as fit stay on page 1 while
 * its scale stays at 0.9 or more; the rest continue on extra pages titled "Paint, continued" drawn at 1.1. No list text is drawn
 * smaller than 7 pt (8 pt at the 0.9 floor; at least 8.8 pt on the extra pages). Without a list nothing here is drawn.
 *
 * Pages. One instructions page for the whole file (more when the paint list continues); then for each sheet an assembly map, the sheet's tile
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
     * One board of a mounted sheet, placed edge to edge with the others in the board rectangle. [rect] is where it lies in
     * rectangle mm; finished-piece mm map into it by adding ([shiftX], [shiftY]); [art] is the part of the piece it shows
     * (its cell less what lies off the piece), in finished mm; [border] is its uncut border.
     */
    internal class MBoard(val cell: BoardCell, val rect: RectMm, val shiftX: Double, val shiftY: Double, val art: RectMm, val border: Double) {
        /** The part of the piece this board shows, in rectangle mm. */
        val artRect = RectMm(art.x0 + shiftX, art.y0 + shiftY, art.x1 + shiftX, art.y1 + shiftY)

        /** The board's whole cut area in rectangle mm (the board less its border). */
        val cutRect = RectMm(rect.x0 + border, rect.y0 + border, rect.x1 - border, rect.y1 - border)

        fun toRect(r: RectMm) = RectMm(r.x0 + shiftX, r.y0 + shiftY, r.x1 + shiftX, r.y1 + shiftY)
    }

    /**
     * The geometry of a print: the piece alone, or the sheet's boards placed edge to edge in one rectangle. One page grid
     * covers it, starting at its top-left corner with the normal step (paper size less [PAPER_OVERLAP_MM]). Paper-only,
     * grid coordinates are the piece's, with page A1's printed corner at the origin; mounted, they are the board rectangle's, with
     * A1's paper edge on the origin (the top and left edges of board 1).
     */
    internal class Layout(val widthMm: Double, val heightMm: Double, val options: PrintOptions) {
        val spec: TileSpec = options.spec
        val boardGrid: BoardGrid? = options.mount?.let { BoardGrid.of(widthMm, heightMm, it) }
        val boards: List<MBoard>
        val grid: TilePlan

        /** How far the windows advance across and down from one page to the next. */
        val stepX: Double = spec.pageWidthMm - PAPER_OVERLAP_MM

        /** See [stepX]. */
        val stepY: Double = spec.pageHeightMm - PAPER_OVERLAP_MM

        /** Where page 0's window starts in grid coordinates: 0 for the piece, the margin for the board rectangle. */
        val offset: Double

        init {
            val bg = boardGrid
            val p = spec.printableWidthMm
            val ph = spec.printableHeightMm
            val rectW: Double
            val rectH: Double
            if (bg == null) {
                offset = 0.0
                rectW = widthMm
                rectH = heightMm
                boards = emptyList()
            } else {
                val mount = checkNotNull(options.mount)
                val b = mount.borderMm
                val board = mount.board.oriented(bg.landscape)
                offset = MARGIN_MM
                rectW = bg.cols * board.widthMm
                rectH = bg.rows * board.heightMm
                boards = bg.cells.map { cell ->
                    val c = cell.cut
                    val bx = cell.col * board.widthMm
                    val by = cell.row * board.heightMm
                    MBoard(
                        cell, RectMm(bx, by, bx + board.widthMm, by + board.heightMm), bx - (c.x0 - b), by - (c.y0 - b),
                        RectMm(c.x0, c.y0, min(widthMm, c.x1), min(heightMm, c.y1)), b,
                    )
                }
            }
            val cols = pageCount(rectW - offset, p, stepX)
            val rows = pageCount(rectH - offset, ph, stepY)
            require(cols.toLong() * rows <= Tiling.MAX_TILES) { "$cols x $rows pages is more than the ${Tiling.MAX_TILES} allowed; use a larger page" }
            val tiles = ArrayList<Tile>(cols * rows)
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = c * stepX + offset
                val y0 = r * stepY + offset
                tiles += Tile(r, c, RectMm(x0, y0, x0 + p, y0 + ph))
            }
            grid = TilePlan(spec, rectW, rectH, rows, cols, tiles)
        }

        /** The index, along an axis with [tiles] pages, of the page that owns coordinate [v] (grid coordinates). */
        private fun owner(v: Double, step: Double, tiles: Int): Int =
            if (tiles == 1 || step <= 0.0) 0 else min(tiles - 1, max(0, floor((v - offset) / step).toInt()))

        /** The index of the board that owns the finished-piece point ([x], [y]); 0 paper-only. */
        fun boardAt(x: Double, y: Double): Int {
            val bg = boardGrid ?: return 0
            val bc = min(bg.cols - 1, max(0, floor(x / bg.cutWidthMm).toInt()))
            val br = min(bg.rows - 1, max(0, floor(y / bg.cutHeightMm).toInt()))
            return br * bg.cols + bc
        }

        private fun shiftX(b: Int) = if (boards.isEmpty()) 0.0 else boards[b].shiftX
        private fun shiftY(b: Int) = if (boards.isEmpty()) 0.0 else boards[b].shiftY

        /** The tile that owns the finished-piece point ([x], [y]) of the piece. */
        fun locate(x: Double, y: Double): Int {
            val b = boardAt(x, y)
            val col = owner(x + shiftX(b), stepX, grid.cols)
            val row = owner(y + shiftY(b), stepY, grid.rows)
            return row * grid.cols + col
        }

        fun note(sheet: Int, tip: PrintTip, mmPerPx: Double): Note {
            val x = (tip.x + 0.5) * mmPerPx
            val y = (tip.y + 0.5) * mmPerPx
            val b = boardAt(x, y)
            val tile = locate(x, y)
            val position = "At ${x.roundToInt()},${y.roundToInt()}mm"
            val lines = when (tip.kind) {
                PrintTipKind.ISLAND -> listOf("ISLAND", position, "Add small bridges", "before cutting.")
                PrintTipKind.FINE_CUT -> listOf("FINE DETAIL", position, "Cut this part last.", "Lift waste slowly.")
                PrintTipKind.SOFT_GRADIENT -> listOf(
                    "SOFT GRADIENT", position,
                    "Shim the ${tip.direction?.name?.lowercase() ?: "uncut"} edge.",
                    "Spray lightly. Test first.",
                )
            }
            return Note(
                "S$sheet:${tip.kind}:${tip.x}:${tip.y}", sheet, lines, x, y, x + shiftX(b), y + shiftY(b), tile, tileCode(grid.tiles[tile]),
                if (boards.isEmpty()) 0 else boards[b].cell.number,
            )
        }

        /** The Lay that draws this sheet's pages; [tabs] are each board's tabs (finished mm). */
        fun lay(sheetTotal: Int, tabs: List<List<TabCut>> = emptyList()): Lay =
            Lay(grid, sheetTotal, RectMm(0.0, 0.0, widthMm, heightMm), boards, tabs, boardGrid?.cols ?: 1, boardGrid?.rows ?: 1)

        /** Which tiles have cut in the part of the piece they alone own, and which boards have any cut. */
        private fun usage(mask: BooleanArray, width: Int, height: Int): Pair<BooleanArray, BooleanArray> {
            val used = BooleanArray(grid.tiles.size)
            val boardUsed = BooleanArray(max(1, boards.size))
            val mm = widthMm / width
            val bg = boardGrid
            fun axis(count: Int, cell: Double?, nBoards: Int, shifts: DoubleArray, step: Double, tiles: Int): Triple<IntArray, IntArray, IntArray> {
                val board = IntArray(count)
                val tile = IntArray(count)
                for (i in 0 until count) {
                    val v = (i + 0.5) * mm
                    val k = if (cell == null) 0 else min(nBoards - 1, floor(v / cell).toInt())
                    board[i] = k
                    tile[i] = owner(v + shifts[k], step, tiles)
                }
                return Triple(board, tile, IntArray(0))
            }
            val sx = DoubleArray(bg?.cols ?: 1) { c -> if (bg == null) 0.0 else boards[c].shiftX }
            val sy = DoubleArray(bg?.rows ?: 1) { r -> if (bg == null) 0.0 else boards[r * bg.cols].shiftY }
            val (bx, tx, _) = axis(width, bg?.cutWidthMm, bg?.cols ?: 1, sx, stepX, grid.cols)
            val (by, ty, _) = axis(height, bg?.cutHeightMm, bg?.rows ?: 1, sy, stepY, grid.rows)
            val cols = bg?.cols ?: 1
            for (y in 0 until height) {
                val row = y * width
                val rowTile = ty[y] * grid.cols
                val rowBoard = by[y] * cols
                for (x in 0 until width) if (mask[row + x]) {
                    used[rowTile + tx[x]] = true
                    boardUsed[rowBoard + bx[x]] = true
                }
            }
            return used to boardUsed
        }

        /**
         * The fraction of [rect] (rectangle mm) that lies under the blank 3 mm margin of a page laid on top of another: along the
         * left and top of every page that has a page to its left or above. No artwork shows there.
         */
        private fun hiddenFraction(rect: RectMm): Double {
            val p = spec.printableWidthMm
            val ph = spec.printableHeightMm
            fun overlap(x0: Double, y0: Double, x1: Double, y1: Double): Double {
                val w = min(rect.x1, x1) - max(rect.x0, x0)
                val h = min(rect.y1, y1) - max(rect.y0, y0)
                return if (w > 0 && h > 0) w * h else 0.0
            }
            var area = 0.0
            for (t in grid.tiles) {
                val x0 = t.clip.x0
                val y0 = t.clip.y0
                if (t.col > 0) area += overlap(x0 - MARGIN_MM, y0 - MARGIN_MM, x0, y0 + ph + MARGIN_MM)
                if (t.row > 0) area += overlap(x0 - MARGIN_MM, y0 - MARGIN_MM, x0 + p + MARGIN_MM, y0)
            }
            return area / (rect.width * rect.height)
        }

        /** The tabs of board [r] for the sheet [uncut] describes, and the sides that face an earlier board but got none. */
        private fun tabsFor(r: Int, uncut: UncutSpace): Pair<List<TabCut>, List<TabSide>> {
            if (boards.isEmpty()) return emptyList<TabCut>() to emptyList()
            val cell = boards[r].cell
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
                    if (cut > 0) usable += TabCut(side, rect, cut < total, hiddenFraction(boards[r].toRect(rect)))
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

        /** What a sheet needs: its cut tiles, each board's tabs and which tiles get a page. */
        inner class Analysis(val used: BooleanArray, val boardUsed: BooleanArray, val tabs: List<List<TabCut>>, val missing: List<List<TabSide>>) {
            private val anyUsed = used.any { it }

            /** Whether board [b] is skipped: nothing is cut on it. Paper-only there is one "board", the piece. */
            fun blank(b: Int) = !boardUsed[b]

            /**
             * Whether tile [t] gets a page: it has cut in it, a tab is cut on it or, mounted, it is the corner page A1 (tile 0), which
             * is the reference the pages are laid from. Nothing is printed if nothing is cut at all.
             */
            fun needed(t: Int): Boolean {
                if (!anyUsed) return false
                if (used[t] || (boardGrid != null && t == 0)) return true
                val tile = grid.tiles[t]
                val x0 = tile.clip.x0
                val y0 = tile.clip.y0
                return tabs.withIndex().any { (b, list) ->
                    list.any { tab ->
                        val r = boards[b].toRect(tab.rect)
                        r.x0 < x0 + spec.printableWidthMm && r.x1 > x0 && r.y0 < y0 + spec.printableHeightMm && r.y1 > y0
                    }
                }
            }
        }

        /** The analysis of one sheet's [mask]; [uncut] is its clearance table. */
        fun analyse(mask: BooleanArray, width: Int, height: Int, uncut: UncutSpace): Analysis {
            val (used, boardUsed) = usage(mask, width, height)
            val tabs = ArrayList<List<TabCut>>()
            val missing = ArrayList<List<TabSide>>()
            for (r in boards.indices) {
                if (!boardUsed[r]) { tabs += emptyList<TabCut>(); missing += emptyList<TabSide>(); continue }
                val (t, m) = tabsFor(r, uncut)
                tabs += t; missing += m
            }
            return Analysis(used, boardUsed, tabs, missing)
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
        val pages = sheetCount.toLong() * (layout.grid.tiles.size.toLong() + 1) + 1
        require(pages <= MAX_PAGES) {
            "Printout needs too many pages: ${layout.grid.cols} x ${layout.grid.rows} pages per sheet is up to $pages in all, more than the " +
                "$MAX_PAGES allowed. Make the stencil smaller or use bigger paper."
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
            val tiles = layout.grid.tiles
            val printed = tiles.filterIndexed { t, _ -> analysis.needed(t) }
            val blank = tiles.filterIndexed { t, _ -> !analysis.needed(t) }
            val boards = layout.boards.mapIndexed { b, mb ->
                fun touches(t: Tile): Boolean {
                    val x0 = t.clip.x0; val y0 = t.clip.y0
                    return mb.rect.x0 < x0 + layout.spec.printableWidthMm && mb.rect.x1 > x0 && mb.rect.y0 < y0 + layout.spec.printableHeightMm && mb.rect.y1 > y0
                }
                BoardPages(mb.cell, analysis.blank(b), printed.filter { touches(it) }, blank.filter { touches(it) }, analysis.tabs[b], analysis.missing[b])
            }
            SheetPages(sheet, printed, blank, boards)
        }
        return PrintPlan(options, layout.grid, perSheet, layout.boardGrid, Instructions.pageCount(layout.lay(separation.sheets.size), layout, sheets.size))
    }

    /** The print PDF of [sheets] (default all) of [separation], as one file. */
    fun make(separation: Separation, options: PrintOptions, sheets: List<Int> = separation.sheets.indices.toList()): ByteArray {
        checkSheets(separation, sheets)
        val layout = layoutFor(separation, options, sheets.size)
        val first = layout.lay(separation.sheets.size)
        val doc = PdfDoc()
        for (content in Instructions.pages(first, layout, sheets.size)) doc.addPage(first.pageW, first.pageH, content)

        var tipsUsed = 0
        for (sheet in sheets) {
            // Everything per sheet lives in this iteration, so one sheet's mask and tables are alive at a time.
            val mask = separation.mask(sheet)
            val mmPerPx = options.widthMm / separation.width
            val uncut = UncutSpace(mask, separation.width, separation.height, mmPerPx)
            val analysis = layout.analyse(mask, separation.width, separation.height, uncut)
            val cuttingTips = StencilPrintTips.detect(
                mask, separation.width, separation.height, options.widthMm, maxTips = MAX_TIPS - tipsUsed,
            )
            val gradientTips = separation.liftZones.asSequence().filter { it.sheet == sheet }.map {
                PrintTip(PrintTipKind.SOFT_GRADIENT, it.x, it.y, it.towards)
            }.toList()
            require(gradientTips.size <= MAX_TIPS - tipsUsed - cuttingTips.size) {
                "Too many stencil details for a printable PDF; simplify the image or reduce the sheet count."
            }
            val tips = cuttingTips + gradientTips
            tipsUsed += tips.size
            val outline = Vectorizer.vectorizeSheet(separation, sheet)
            val shapes = outline.shapes.map { Placed(it.rings.map { r -> r.points }, mmPerPx, layout.heightMm) }
            val color = outline.color
            val lay = layout.lay(separation.sheets.size, analysis.tabs)

            doc.addPage(first.pageW, first.pageH, if (layout.boardGrid == null) lay.mapPage(sheet, analysis.used, shapes, color, doc)
            else lay.boardMapPage(sheet, layout, analysis, shapes, color, doc))

            val required = LinkedHashMap<String, Note>()
            val homes = HashMap<Int, MutableList<Note>>()
            for (tip in tips) {
                val note = layout.note(sheet, tip, mmPerPx)
                required[note.key] = note
                homes.getOrPut(note.tile) { ArrayList() } += note
            }
            val placed = HashSet<String>()
            for ((index, tile) in layout.grid.tiles.withIndex()) {
                if (!analysis.needed(index)) continue
                doc.addPage(first.pageW, first.pageH, lay.tilePage(sheet, tile, shapes, color, doc, uncut, homes[index].orEmpty(), placed))
                require(doc.pageCount <= MAX_PAGES) { "Printout needs too many pages" }
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

    /** A cutting tip's text and where its anchor is, in finished-piece mm ([x], [y]) and in grid mm ([rx], [ry]); [tile] is the index of the tile that owns it. */
    internal class Note(
        val key: String, val sheet: Int, val lines: List<String>, val x: Double, val y: Double,
        val rx: Double, val ry: Double, val tile: Int, val code: String, val board: Int,
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
     * The operators that draw one dotted cut line from ([x1], [y1]) to ([x2], [y2]) (page points) over a cut filled in [fill]: round
     * dots, dark; and, over a dark fill, the same dots in white drawn again inside the clip [shapeClip] (operators that leave a clip
     * to the sheet's cut shapes in force, in page coordinates), so that each dot contrasts with what lies under it. [shapeClip] null
     * means nothing is filled there. Over a light fill the dark dots already contrast.
     */
    internal fun cutLineOps(x1: Double, y1: Double, x2: Double, y2: Double, fill: Int, shapeClip: String?): String {
        fun dots(color: String) = "[0.01 3.6] 0 d\n1 J\n1.7 w\n$color RG\n${num(x1)} ${num(y1)} m ${num(x2)} ${num(y2)} l S\n"
        val sb = StringBuilder("q\n").append(dots("0.1 0.1 0.1")).append("Q\n")
        if (shapeClip != null && luminance(fill) < 0.5) sb.append("q\n").append(shapeClip).append(dots("1 1 1")).append("Q\n")
        return sb.toString()
    }

    /** The luminance of [argb]'s colour, 0 (black) to 1 (white), ignoring alpha. */
    internal fun luminance(argb: Int): Double =
        (0.299 * (argb shr 16 and 255) + 0.587 * (argb shr 8 and 255) + 0.114 * (argb and 255)) / 255.0

    /**
     * Everything about the page and the piece that the page builders share. Tiles of [grid] are in grid mm: the piece's own
     * (paper-only, [boards] empty) or the board rectangle's. Mounted, [boards] are the sheet's boards edge to edge in the
     * rectangle, [tabs] are each board's tabs (finished mm) and [boardCols] x [boardRows] is how they are arranged.
     */
    internal class Lay(
        val grid: TilePlan, private val sheetTotal: Int, val piece: RectMm = RectMm(0.0, 0.0, grid.widthMm, grid.heightMm),
        val boards: List<MBoard> = emptyList(), val tabs: List<List<TabCut>> = emptyList(),
        private val boardCols: Int = 1, private val boardRows: Int = 1,
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

        /** The boards whose rectangle [tile]'s printable area overlaps. */
        private fun touching(tile: Tile): List<MBoard> {
            val x0 = tile.clip.x0
            val y0 = tile.clip.y0
            return boards.filter { it.rect.x0 < x0 + spec.printableWidthMm && it.rect.x1 > x0 && it.rect.y0 < y0 + spec.printableHeightMm && it.rect.y1 > y0 }
        }

        private fun label(sheet: Int, tile: Tile): String {
            if (boards.isEmpty()) return "Sheet ${sheet + 1} of $sheetTotal - Row ${tile.row + 1} Col ${tile.col + 1} (${tileCode(tile)})"
            val ids = touching(tile).map { it.cell.number }.sorted()
            val which = if (ids.size <= 1) "Board ${ids.firstOrNull() ?: 0} of ${boards.size}" else "Boards " + ids.joinToString("+")
            return "Sheet ${sheet + 1} of $sheetTotal - $which - ${tileCode(tile)}"
        }

        /** Where the part of this page that stays visible ends across: the right-hand page's paper edge lies over the rest. */
        private fun visibleRight(tile: Tile) =
            if (tile.col < grid.cols - 1) grid.tile(tile.row, tile.col + 1).clip.x0 - MARGIN_MM else tile.clip.x0 + spec.printableWidthMm

        /** Where the part of this page that stays visible ends down: the lower page's paper edge lies over the rest. */
        private fun visibleBottom(tile: Tile) =
            if (tile.row < grid.rows - 1) grid.tile(tile.row + 1, tile.col).clip.y0 - MARGIN_MM else tile.clip.y0 + spec.printableHeightMm

        /** A point in grid mm to page points. */
        private fun px(tile: Tile, mm: Double) = (MARGIN_MM + mm - tile.clip.x0) * K
        private fun py(tile: Tile, mm: Double) = pageH - (MARGIN_MM + mm - tile.clip.y0) * K

        /** The clip rectangle and the translation of finished-piece art shifted by ([shx], [shy]), then [shapes] clipped to [clip] (grid mm). */
        private fun artBlock(sb: StringBuilder, tile: Tile, clip: RectMm, shx: Double, shy: Double, shapes: List<Placed>, color: Int, doc: PdfDoc) {
            sb.append(num(px(tile, clip.x0))).append(' ').append(num(py(tile, clip.y1))).append(' ').append(num(clip.width * K)).append(' ')
                .append(num(clip.height * K)).append(" re W n\n")
            sb.append("1 0 0 1 ").append(num(translateX(tile, shx))).append(' ').append(num(translateY(tile, shy))).append(" cm\n")
            for (s in shapes) {
                if (s.maxX < clip.x0 - shx || s.minX > clip.x1 - shx || s.maxY < clip.y0 - shy || s.minY > clip.y1 - shy) continue
                fillAndOutline(sb, s, color, doc, 0.7)
            }
        }

        private fun translateX(tile: Tile, shx: Double) = (MARGIN_MM - tile.clip.x0 + shx) * K
        private fun translateY(tile: Tile, shy: Double) = pageH - (MARGIN_MM - tile.clip.y0 + shy + heightMm) * K

        private fun intersect(a: RectMm, b: RectMm): RectMm? {
            val x0 = max(a.x0, b.x0); val y0 = max(a.y0, b.y0); val x1 = min(a.x1, b.x1); val y1 = min(a.y1, b.y1)
            return if (x1 > x0 && y1 > y0) RectMm(x0, y0, x1, y1) else null
        }

        fun tilePage(
            sheet: Int, tile: Tile, shapes: List<Placed>, color: Int, doc: PdfDoc,
            uncut: UncutSpace, notes: List<Note>, placed: MutableSet<String>,
        ): String {
            val sb = StringBuilder()
            val x0 = tile.clip.x0
            val y0 = tile.clip.y0
            val x1 = x0 + spec.printableWidthMm
            val y1 = y0 + spec.printableHeightMm
            val window = RectMm(x0, y0, x1, y1)
            val here = touching(tile)
            if (boards.isEmpty()) {
                // Paper-only: the art is clipped to the printable area.
                sb.append("q\n")
                artBlock(sb, tile, window, 0.0, 0.0, shapes, color, doc)
                sb.append("Q\n")
            } else {
                // Each board's share of the page: its art (its cut area less what lies off the piece), under its own shift.
                var any = false
                for (b in here) {
                    val clip = intersect(window, b.artRect) ?: continue
                    any = true
                    sb.append("q\n")
                    artBlock(sb, tile, clip, b.shiftX, b.shiftY, shapes, color, doc)
                    sb.append("Q\n")
                }
                // Nothing of the piece falls on this page (a corner page A1 with nothing to cut): an empty clip draws nothing.
                if (!any) sb.append("q\n").append(num(left)).append(' ').append(num(bottom)).append(" 0 0 re W n\nQ\n")
                for (b in here) for (tab in tabs.getOrNull(b.cell.number - 1).orEmpty()) drawTab(sb, tile, b, tab, shapes, color, doc)
            }

            val used = ArrayList<Rect>()
            used += cutLines(sb, tile, shapes, color, uncut)

            // The guides for the pages that will be laid on top (see [guides]).
            sb.append("q\n0 G\n").append(num(MARK_WIDTH_MM * K)).append(" w\n0 J\n0 j\n")
            for (m in guides(grid, tile)) {
                for ((i, pt) in m.withIndex()) {
                    sb.append(num(px(tile, pt.x))).append(' ').append(num(py(tile, pt.y))).append(if (i == 0) " m " else " l ")
                }
                sb.append("S\n")
            }
            sb.append("Q\n")

            // The label first, then the tips around it.
            val labelText = label(sheet, tile)
            val lw = PdfText.width(labelText, LABEL_PT) / K + 1.0
            val lh = LABEL_PT / K + 1.0
            val labelBox = place(tile, lw, lh, 2.0, 1.0, uncut, used)
                ?: place(tile, lw, lh, 1.0, 0.0, uncut, used)
                ?: Rect(x0 + INSET, y0 + INSET, lw, lh)
            sb.append("0 g\n")
            text(sb, LABEL_PT, px(tile, labelBox.x) + 0.5 * K, py(tile, labelBox.y + labelBox.h) + 0.5 * K, labelText)

            used += labelBox
            if (notes.isNotEmpty()) {
                val candidates = candidates(tile, TIP_W, TIP_H, 12.0 / K, TIP_CLEARANCE, uncut)
                for (note in notes) {
                    if (note.lines.withIndex().any { (i, l) -> PdfText.width(l, if (i == 0) 7.4 else 7.0, i == 0) + 4.0 > TIP_W * K }) continue
                    var best: Rect? = null
                    var bestDist = Double.POSITIVE_INFINITY
                    for (r in candidates) {
                        if (used.any { it.overlaps(r) }) continue
                        val dx = r.x + r.w / 2 - note.rx
                        val dy = r.y + r.h / 2 - note.ry
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
         * The dotted cut lines on this page: one exactly on every board edge that crosses its printed area, across that area (within the
         * board rectangle), each labelled once. Where an edge falls in the 3 mm blank strip under the next page the line is printed on
         * the visible side of the strip instead, with a note of how far past it the edge lies. Returns the label boxes it used.
         */
        private fun cutLines(sb: StringBuilder, tile: Tile, shapes: List<Placed>, color: Int, uncut: UncutSpace): List<Rect> {
            val reserved = ArrayList<Rect>()
            if (boards.isEmpty()) return reserved
            val bw = boards[0].rect.width
            val bh = boards[0].rect.height
            val p = spec.printableWidthMm
            val ph = spec.printableHeightMm
            val wl = tile.clip.x0
            val wt = tile.clip.y0
            val here = touching(tile)
            val rw = grid.widthMm
            val rh = grid.heightMm
            val size = 5.5
            // The clip of one board's cut shapes on this page, in page coordinates, or null if none lie under the line.
            fun shapeClip(b: MBoard): String? {
                val clip = intersect(RectMm(wl, wt, wl + p, wt + ph), b.artRect) ?: return null
                val relevant = shapes.filter { !(it.maxX < clip.x0 - b.shiftX || it.minX > clip.x1 - b.shiftX || it.maxY < clip.y0 - b.shiftY || it.minY > clip.y1 - b.shiftY) }
                if (relevant.isEmpty()) return null
                val tx = translateX(tile, b.shiftX)
                val ty = translateY(tile, b.shiftY)
                val c = StringBuilder()
                c.append(num(px(tile, clip.x0))).append(' ').append(num(py(tile, clip.y1))).append(' ').append(num(clip.width * K)).append(' ')
                    .append(num(clip.height * K)).append(" re W n\n")
                c.append("1 0 0 1 ").append(num(tx)).append(' ').append(num(ty)).append(" cm\n")
                for (s in relevant) c.append(s.data)
                c.append("W* n\n1 0 0 1 ").append(num(-tx)).append(' ').append(num(-ty)).append(" cm\n")
                return c.toString()
            }
            fun emit(x1: Double, y1: Double, x2: Double, y2: Double) {
                // Dark dots first; over each board's dark cut region, white dots again inside the cut shapes.
                sb.append(cutLineOps(px(tile, x1), py(tile, y1), px(tile, x2), py(tile, y2), color, null))
                if (luminance(color) < 0.5) for (b in here) {
                    val clip = shapeClip(b) ?: continue
                    sb.append("q\n").append(clip)
                    sb.append("[0.01 3.6] 0 d\n1 J\n1.7 w\n1 1 1 RG\n").append(num(px(tile, x1))).append(' ').append(num(py(tile, y1))).append(" m ")
                        .append(num(px(tile, x2))).append(' ').append(num(py(tile, y2))).append(" l S\nQ\n")
                }
            }
            fun caption(text: String, candidates: List<Pair<Double, Double>>) {
                val w = PdfText.width(text, size) / K
                val h = size / K
                for ((cx, cy) in candidates) {
                    val r = Rect(cx, cy, w, h)
                    if (cx < wl + INSET || cx + w > visibleRight(tile) - INSET || cy < wt + INSET || cy + h > visibleBottom(tile) - INSET) continue
                    if (reserved.any { it.overlaps(r) } || !ok(cx, cy, w, h, 0.0, uncut, true)) continue
                    reserved += r
                    sb.append("0 g\n")
                    text(sb, size, px(tile, cx), py(tile, cy + h) + 0.4 * K, text)
                    return
                }
            }
            for (k in 1..boardCols) {
                val x = k * bw
                if (x <= wl + 0.7 || x > wl + p) continue
                val nextLeft = if (tile.col < grid.cols - 1) grid.tile(tile.row, tile.col + 1).clip.x0 - MARGIN_MM else Double.NaN
                val inStrip = !nextLeft.isNaN() && x >= nextLeft && x <= nextLeft + MARGIN_MM + 1e-9
                val xd = if (inStrip) nextLeft - 0.3 else x
                val y1 = wt
                val y2 = min(wt + ph, rh)
                emit(xd, y1, xd, y2)
                val text = if (inStrip) "cut ${Decimal.format(x - xd, 1)} mm past this line - board edge" else "cut here - board edge"
                val w = PdfText.width(text, size) / K
                caption(text, (0..8).flatMap { i -> val y = wt + 6 + i * 25.0; listOf((xd + 2.0) to y, (xd - 2.0 - w) to y) })
            }
            for (k in 1..boardRows) {
                val y = k * bh
                if (y <= wt + 0.7 || y > wt + ph) continue
                val nextTop = if (tile.row < grid.rows - 1) grid.tile(tile.row + 1, tile.col).clip.y0 - MARGIN_MM else Double.NaN
                val inStrip = !nextTop.isNaN() && y >= nextTop && y <= nextTop + MARGIN_MM + 1e-9
                val yd = if (inStrip) nextTop - 0.3 else y
                val x1 = wl
                val x2 = min(wl + p, rw)
                emit(x1, yd, x2, yd)
                val text = if (inStrip) "cut ${Decimal.format(y - yd, 1)} mm past this line - board edge" else "cut here - board edge"
                val h = size / K
                caption(text, (0..4).flatMap { i -> val cx = wl + 6 + i * 45.0; listOf(cx to (yd - 2.0 - h), cx to (yd + 2.0)) })
            }
            return reserved
        }

        /**
         * A tab, a cut in the board's border: the sheet's own shapes clipped to the tab rectangle, filled and outlined like
         * the rest of the cut, inside a thin dashed frame, with a tiny "TAB" on blank paper beside it.
         */
        private fun drawTab(sb: StringBuilder, tile: Tile, board: MBoard, tab: TabCut, shapes: List<Placed>, color: Int, doc: PdfDoc) {
            val x0 = tile.clip.x0
            val y0 = tile.clip.y0
            val x1 = x0 + spec.printableWidthMm
            val y1 = y0 + spec.printableHeightMm
            val r = board.toRect(tab.rect)
            val clip = intersect(RectMm(x0, y0, x1, y1), r) ?: return
            sb.append("q\n")
            artBlock(sb, tile, clip, board.shiftX, board.shiftY, shapes, color, doc)
            sb.append("Q\n")
            sb.append("q\n0 G\n0.3 w\n[2 2] 0 d\n").append(num(px(tile, clip.x0))).append(' ').append(num(py(tile, clip.y1))).append(' ')
                .append(num(clip.width * K)).append(' ').append(num(clip.height * K)).append(" re S\nQ\n")
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

        /** Boxes of [w] x [h] mm inside [tile]'s visible printable area, inset, that lie on the piece (within one board's cut area) and are clear. */
        private fun candidates(tile: Tile, w: Double, h: Double, step: Double, buffer: Double, uncut: UncutSpace): List<Rect> {
            val out = ArrayList<Rect>()
            val x0 = tile.clip.x0 + INSET
            val y0 = tile.clip.y0 + INSET
            val xMax = visibleRight(tile) - INSET - w
            val yMax = visibleBottom(tile) - INSET - h
            var y = y0
            while (y <= yMax) {
                var x = x0
                while (x <= xMax) {
                    if (ok(x, y, w, h, buffer, uncut, false)) out += Rect(x, y, w, h)
                    x += step
                }
                y += step
            }
            return out
        }

        /**
         * Whether the box can hold tip or label text. Paper-only: wholly on the piece and clear, or (for a label) wholly beyond it.
         * Mounted: it must keep 1.5 mm off every board edge (the dotted lines), then either lie wholly within a board's art and
         * be clear, or (a label only) lie wholly on blank paper: beyond the piece, or in a board's border clear of its tabs.
         */
        private fun ok(x: Double, y: Double, w: Double, h: Double, buffer: Double, uncut: UncutSpace, labelMode: Boolean): Boolean {
            if (boards.isEmpty()) {
                if (x >= piece.x0 && y >= piece.y0 && x + w <= piece.x1 && y + h <= piece.y1) return uncut.clear(x, y, w, h, buffer)
                return labelMode && (x >= widthMm || y >= heightMm)
            }
            val bw = boards[0].rect.width
            val bh = boards[0].rect.height
            for (k in 1..boardCols) { val e = k * bw; if (x - 1.5 < e && x + w + 1.5 > e) return false }
            for (k in 1..boardRows) { val e = k * bh; if (y - 1.5 < e && y + h + 1.5 > e) return false }
            val bx = min(boardCols - 1, max(0, floor((x + w / 2) / bw).toInt()))
            val by = min(boardRows - 1, max(0, floor((y + h / 2) / bh).toInt()))
            val b = boards.getOrNull(by * boardCols + bx) ?: return false
            if (x < b.rect.x0 || y < b.rect.y0 || x + w > b.rect.x1 || y + h > b.rect.y1) return false
            val ar = b.artRect
            if (x >= ar.x0 && y >= ar.y0 && x + w <= ar.x1 && y + h <= ar.y1) return uncut.clear(x - b.shiftX, y - b.shiftY, w, h, buffer)
            if (!labelMode) return false
            if (x - b.shiftX >= widthMm || y - b.shiftY >= heightMm) return true
            val cut = b.cutRect
            if (x < cut.x1 && x + w > cut.x0 && y < cut.y1 && y + h > cut.y0) return false
            for (tab in tabs.getOrNull(b.cell.number - 1).orEmpty()) {
                val r = b.toRect(tab.rect)
                if (x < r.x1 + 2.5 && x + w > r.x0 - 2.5 && y < r.y1 + 2.5 && y + h > r.y0 - 2.5) return false
            }
            return true
        }

        private fun place(tile: Tile, w: Double, h: Double, step: Double, buffer: Double, uncut: UncutSpace, avoid: List<Rect>): Rect? {
            val x0 = tile.clip.x0 + INSET
            val y0 = tile.clip.y0 + INSET
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

        /**
         * The assembly map when mounting: the boards edge to edge as one rectangle (border and cut area of each, blank boards hatched, tabs
         * solid), the single page grid over it, and the dotted cut lines where pages cross board edges.
         */
        fun boardMapPage(sheet: Int, layout: Layout, analysis: Layout.Analysis, shapes: List<Placed>, color: Int, doc: PdfDoc): String {
            val bg = checkNotNull(layout.boardGrid)
            val border = checkNotNull(layout.options.mount).borderMm
            val sb = StringBuilder("0 g\n")
            var y = top - 14.0
            text(sb, 13.0, left, y, "Sheet ${sheet + 1} of $sheetTotal - assembly map", bold = true)
            val blankBoards = boards.indices.count { analysis.blank(it) }
            val pagesPrinted = grid.tiles.indices.count { analysis.needed(it) }
            val b = checkNotNull(layout.options.mount).board
            val lines = listOf(
                "Finished size: ${Decimal.format(widthMm, 1)} x ${Decimal.format(heightMm, 1)} mm (${Decimal.format(widthMm / 25.4, 2)} x ${Decimal.format(heightMm / 25.4, 2)} in)",
                "Boards: ${bg.count} (${bg.cols} across x ${bg.rows} down, ${if (bg.landscape) "landscape" else "portrait"}) of ${b.name}, " +
                    "${Decimal.format(border / 25.4, 2)} in (${Decimal.format(border, 1)} mm) uncut border on every side; ${bg.count - blankBoards} with something to cut, $blankBoards blank",
                "Pages: $pagesPrinted on this sheet in one grid over the boards laid edge to edge, ${spec.name} ${Decimal.format(spec.pageWidthMm, 1)} x ${Decimal.format(spec.pageHeightMm, 1)} mm. Dotted lines are board edges: cut the paper there.",
                "Print at 100%. Start at board 1's top-left corner with A1's paper edges on its edges; cut each page that crosses a board edge on the dotted line and keep the offcut for the next board.",
            )
            for ((i, l) in lines.withIndex()) {
                for (w in PdfText.wrap(l, winW, if (i == 0) 10.0 else 8.5)) {
                    y -= 13.0
                    text(sb, if (i == 0) 10.0 else 8.5, left, y, w)
                }
            }
            val boxTop = y - 14.0
            val boxH = boxTop - bottom
            val rw = grid.widthMm
            val rh = grid.heightMm
            val k = min(winW / rw, boxH / rh)
            val c = k / K
            val ox0 = left
            val oyTop = boxTop
            // Each board's art: its cut area's share of the piece, in its place in the rectangle.
            for (mb in boards) {
                val ar = mb.artRect
                sb.append("q\n").append(num(ox0 + ar.x0 * k)).append(' ').append(num(oyTop - ar.y1 * k)).append(' ').append(num(ar.width * k)).append(' ')
                    .append(num(ar.height * k)).append(" re W n\n")
                sb.append("1 0 0 1 ").append(num(ox0 + mb.shiftX * k)).append(' ').append(num(oyTop - (mb.shiftY + heightMm) * k)).append(" cm\n")
                sb.append(Decimal.format(c, 6)).append(" 0 0 ").append(Decimal.format(c, 6)).append(" 0 0 cm\n")
                for (s in shapes) fillAndOutline(sb, s, color, doc, 0.5 / c)
                sb.append("Q\n")
            }
            for ((bi, mb) in boards.withIndex()) {
                val r = mb.rect
                val cut = mb.cutRect
                val blank = analysis.blank(bi)
                sb.append("q\n0.4 w\n0.55 G\n").append(num(ox0 + r.x0 * k)).append(' ').append(num(oyTop - r.y1 * k)).append(' ').append(num(r.width * k)).append(' ')
                    .append(num(r.height * k)).append(" re S\nQ\n")
                sb.append("q\n0.3 w\n0.3 G\n[2 2] 0 d\n").append(num(ox0 + cut.x0 * k)).append(' ').append(num(oyTop - cut.y1 * k)).append(' ')
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
                    sb.append("q\n0.2 g\n0 G\n0.4 w\n")
                    for (tab in analysis.tabs[bi]) {
                        val t = mb.toRect(tab.rect)
                        sb.append(num(ox0 + t.x0 * k)).append(' ').append(num(oyTop - t.y1 * k)).append(' ')
                            .append(num(max(t.width * k, 1.5))).append(' ').append(num(max(t.height * k, 1.5))).append(" re B\n")
                    }
                    sb.append("Q\n")
                    for ((i, side) in analysis.missing[bi].withIndex()) {
                        val note = "no ${side.name.lowercase()} tab"
                        val lx = ox0 + cut.x0 * k + 4.0
                        val ly = oyTop - cut.y1 * k + 5.0 + i * 8.0
                        sb.append("q\n1 g\n").append(num(lx - 1.0)).append(' ').append(num(ly - 1.5)).append(' ').append(num(PdfText.width(note, 6.0) + 2.0)).append(" 8 re f\nQ\n0.1 g\n")
                        text(sb, 6.0, lx, ly, note)
                    }
                }
                val big = (min(cut.width, cut.height) * k / 4).coerceIn(8.0, 18.0)
                val label = "Board ${mb.cell.number}" + if (blank) " blank" else ""
                sb.append("q\n1 g\n").append(num(ox0 + cut.x0 * k + 2.0)).append(' ').append(num(oyTop - cut.y0 * k - big - 4.0)).append(' ')
                    .append(num(PdfText.width(label, big, true) + 4.0)).append(' ').append(num(big + 5.0)).append(" re f\nQ\n0.1 g\n")
                text(sb, big, ox0 + cut.x0 * k + 4.0, oyTop - cut.y0 * k - big - 1.0, label, bold = true)
            }
            // The single page grid over the rectangle: each printed page's printable area.
            sb.append("q\n0.25 w\n0.55 G\n[1.5 1.5] 0 d\n")
            for ((t, tile) in grid.tiles.withIndex()) {
                if (!analysis.needed(t)) continue
                val wx0 = tile.clip.x0
                val wy0 = tile.clip.y0
                val wx1 = min(wx0 + spec.printableWidthMm, rw)
                val wy1 = min(wy0 + spec.printableHeightMm, rh)
                sb.append(num(ox0 + wx0 * k)).append(' ').append(num(oyTop - wy1 * k)).append(' ').append(num((wx1 - wx0) * k)).append(' ')
                    .append(num((wy1 - wy0) * k)).append(" re S\n")
            }
            sb.append("Q\n")
            // The dotted cut lines at every board edge past the first, round dots.
            val bw = boards[0].rect.width
            val bh = boards[0].rect.height
            sb.append("q\n[0.01 3.0] 0 d\n1 J\n1.4 w\n0 G\n")
            for (kx in 1..bg.cols) {
                val x = ox0 + kx * bw * k
                sb.append(num(x)).append(' ').append(num(oyTop)).append(" m ").append(num(x)).append(' ').append(num(oyTop - rh * k)).append(" l S\n")
            }
            for (ky in 1..bg.rows) {
                val yy = oyTop - ky * bh * k
                sb.append(num(ox0)).append(' ').append(num(yy)).append(" m ").append(num(ox0 + rw * k)).append(' ').append(num(yy)).append(" l S\n")
            }
            sb.append("Q\n")
            // Page codes, small, on the grid.
            for ((t, tile) in grid.tiles.withIndex()) {
                if (!analysis.needed(t)) continue
                val code = tileCode(tile)
                val lx = ox0 + (tile.clip.x0 + 2.0) * k
                val ly = oyTop - (tile.clip.y0 + 2.0) * k - 6.0
                sb.append("q\n1 g\n").append(num(lx - 0.5)).append(' ').append(num(ly - 1.0)).append(' ').append(num(PdfText.width(code, 6.0, true) + 1.0)).append(" 7.5 re f\nQ\n0.1 g\n")
                text(sb, 6.0, lx, ly, code, bold = true)
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

        /** The smallest size, in points as printed, of any text in the paint list. */
        const val MIN_LIST_PT = 7.0
        private const val NAME_PT = 8.5
        private const val DETAIL_PT = 8.0
        private const val NAME_LEAD = 10.5
        private const val DETAIL_LEAD = 9.5
        private const val ROW_GAP = 6.0
        private const val SWATCH = 11.0
        private const val FIRST_HEAD = 34.0
        private const val MORE_HEAD = 26.0
        private const val BLOCK_GAP = 10.0
        private const val BAR_RESERVE = 34.0

        /** Page 1 takes list rows only while its scale stays at or above this: [DETAIL_PT] x 0.9 = 7.2 pt. */
        const val MIN_SCALE = 0.9

        /** The scale of the extra paint pages; their virtual width is the printable width over this. */
        const val LIST_SCALE = 1.1

        /** One row of the paint list, wrapped for a given width: [head] lines, then [detail] and [also] in muted ink. */
        internal class Row(val item: com.hereliesaz.stencilartist.core.paint.ShoppingItem, val label: String, val textX: Double,
            val head: List<String>, val detail: List<String>, val also: List<String>) {
            val height: Double = head.size * NAME_LEAD + (detail.size + also.size) * DETAIL_LEAD + ROW_GAP
        }

        /** Where the paint list goes: [first] rows on page 1, then one list per extra page. */
        internal class ListPages(val first: List<Row>, val more: List<List<Row>>)

        private fun quality(item: com.hereliesaz.stencilartist.core.paint.ShoppingItem): String {
            val q = when (item.quality) {
                com.hereliesaz.stencilartist.core.paint.MatchQuality.EXACT -> "exact match"
                com.hereliesaz.stencilartist.core.paint.MatchQuality.CLOSE -> "close match"
                com.hereliesaz.stencilartist.core.paint.MatchQuality.APPROXIMATE -> "approximate match"
            }
            return when {
                item.userColour -> "$q (user-entered colour, approximate)"
                item.sampled -> "$q (colour sampled, approximate)"
                else -> q
            }
        }

        private fun canName(can: com.hereliesaz.stencilartist.core.paint.Can): String =
            "${can.brand} ${can.line} ${can.name}" + (can.code?.let { " ($it)" } ?: "")

        private fun row(item: com.hereliesaz.stencilartist.core.paint.ShoppingItem, width: Double, labelW: Double): Row {
            val textX = SWATCH + 7.0 + labelW
            val w = width - textX
            val parts = ArrayList<String>()
            parts += item.code?.let { "code $it" } ?: "no maker code"
            item.gtin?.let { parts += "barcode $it" }
            parts += quality(item)
            val also = if (item.equivalents.isEmpty()) emptyList() else {
                val named = item.equivalents.take(3).joinToString(", ") { canName(it) }
                val more = item.equivalents.size - 3
                PdfText.wrap("also: $named" + (if (more > 0) ", and $more more" else ""), w, DETAIL_PT)
            }
            return Row(
                item, "Layer ${item.layerNumber}", textX,
                PdfText.wrap("${item.brand} ${item.line} - ${item.name}", w, NAME_PT),
                PdfText.wrap(parts.joinToString(", "), w, DETAIL_PT), also,
            )
        }

        private fun isLandscape(lay: Lay) = lay.winW >= lay.winH * 1.2
        private fun virtualWidth(lay: Lay) = if (isLandscape(lay)) 780.0 else 540.0

        private fun labelWidth(plan: Layout): Double =
            PdfText.width("Layer ${plan.options.shopping.maxOf { it.layerNumber }}", NAME_PT, true) + 6.0

        /** Splits the paint list between page 1 and the extra pages; empty when there is no list. */
        internal fun listPages(lay: Lay, plan: Layout, sheetsInFile: Int): ListPages {
            val items = plan.options.shopping
            if (items.isEmpty()) return ListPages(emptyList(), emptyList())
            val vw = virtualWidth(lay)
            val labelW = labelWidth(plan)
            val first = ArrayList<Row>()
            if (lay.winW / vw >= MIN_SCALE) {
                val used = draw(StringBuilder(), lay, plan, vw, isLandscape(lay), sheetsInFile, emptyList())
                var h = used + BLOCK_GAP + FIRST_HEAD
                for (item in items) {
                    val r = row(item, vw, labelW)
                    h += r.height
                    if (min(min(lay.winW / vw, (lay.winH - BAR_RESERVE) / h), 1.5) < MIN_SCALE) break
                    first += r
                }
            }
            val vwc = lay.winW / LIST_SCALE
            val avail = lay.winH / LIST_SCALE - 18.0 - 12.0 - MORE_HEAD
            val more = ArrayList<List<Row>>()
            var cur = ArrayList<Row>()
            var h = 0.0
            for (item in items.drop(first.size)) {
                val r = row(item, vwc, labelW)
                if (cur.isNotEmpty() && h + r.height > avail) { more += cur; cur = ArrayList(); h = 0.0 }
                cur += r
                h += r.height
            }
            if (cur.isNotEmpty()) more += cur
            return ListPages(first, more)
        }

        /** How many instruction pages there are: page 1 and the extra paint pages. */
        fun pageCount(lay: Lay, plan: Layout, sheetsInFile: Int): Int = 1 + listPages(lay, plan, sheetsInFile).more.size

        /** The instruction pages' content streams, page 1 first. */
        fun pages(lay: Lay, plan: Layout, sheetsInFile: Int): List<String> {
            val lp = listPages(lay, plan, sheetsInFile)
            val out = ArrayList<String>()
            out += page(lay, plan, sheetsInFile, lp.first, plan.options.shopping.isNotEmpty())
            for ((i, rows) in lp.more.withIndex()) {
                val scale = LIST_SCALE
                val sb = StringBuilder("0 g\nq\n").append(Decimal.format(scale, 5)).append(" 0 0 ").append(Decimal.format(scale, 5)).append(' ')
                    .append(num(lay.left)).append(' ').append(num(lay.top)).append(" cm\n")
                val firstBlock = lp.first.isEmpty() && i == 0
                block(sb, 18.0, rows, if (firstBlock) "Paint" else "Paint, continued", firstBlock, 14.0)
                sb.append("Q\n")
                out += sb.toString()
            }
            return out
        }

        /** Draws the paint block with its title baseline [y] below the box's top; returns the y after its last row. */
        private fun block(sb: StringBuilder, y: Double, rows: List<Row>, title: String, intro: Boolean, titlePt: Double): Double {
            text(sb, titlePt, 0.0, -y, title, bold = true)
            var cy = y + (if (intro) 13.0 else 8.0)
            if (intro) {
                text(sb, NAME_PT, 0.0, -cy, "Spray in this order, base first.")
                cy += 9.0
            }
            for (r in rows) {
                val top = cy
                sb.append("q\n")
                r.item.can.rgb?.let { sb.append(rgb(it)).append(" rg\n") } ?: sb.append("1 g\n")
                sb.append("$INK RG\n0.6 w\n").append(num(0.3)).append(' ').append(num(-(top + 1.0 + SWATCH))).append(' ')
                    .append(num(SWATCH)).append(' ').append(num(SWATCH)).append(" re B\nQ\n")
                text(sb, NAME_PT, SWATCH + 7.0, -(top + 8.0), r.label, bold = true)
                var ly = top + 8.0
                for (l in r.head) { text(sb, NAME_PT, r.textX, -ly, l); ly += NAME_LEAD }
                ly += DETAIL_LEAD - NAME_LEAD
                sb.append("q\n0.3 g\n")
                for (l in r.detail + r.also) { text(sb, DETAIL_PT, r.textX, -ly, l); ly += DETAIL_LEAD }
                sb.append("Q\n")
                cy += r.height
            }
            return cy
        }

        fun page(lay: Lay, plan: Layout, sheetsInFile: Int, sheetTotal: Int): String =
            page(lay, plan, sheetsInFile, emptyList(), false)

        private fun page(lay: Lay, plan: Layout, sheetsInFile: Int, rows: List<Row>, paint: Boolean): String {
            val landscape = isLandscape(lay)
            val vw = virtualWidth(lay)
            val dry = StringBuilder()
            val used = draw(dry, lay, plan, vw, landscape, sheetsInFile, rows, paint)
            val barReserve = BAR_RESERVE
            val scale = min(min(lay.winW / vw, (lay.winH - barReserve) / used), 1.5)
            val sb = StringBuilder("0 g\n")
            sb.append("q\n").append(Decimal.format(scale, 5)).append(" 0 0 ").append(Decimal.format(scale, 5)).append(' ')
                .append(num(lay.left)).append(' ').append(num(lay.top)).append(" cm\n")
            draw(sb, lay, plan, vw, landscape, sheetsInFile, rows, paint)
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

        private fun steps(sheetsInFile: Int, plan: Layout, paint: Boolean = false): List<Pair<String, String>> {
            val all = baseSteps(sheetsInFile, plan)
            return if (!paint) all else all + ("CHECK THE COLOURS" to "The Paint list names a can for each layer. Colours on a screen are only " +
                "approximate, and a swatch here is how a screen shows the can, not the paint. Before you buy, hold the can's cap or a paint swatch card " +
                "next to your screen and compare. Spray the layers in the order listed: the first layer is the base and goes on first.")
        }

        private fun baseSteps(sheetsInFile: Int, plan: Layout): List<Pair<String, String>> {
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
                "FIND YOUR PAGES" to "Each sheet starts with an assembly map: its boards laid edge to edge as one rectangle with one grid of pages over it. " +
                    "A page's code is its column letter and row number in that grid (A1, B1, A2 ...); A1 is the top-left corner page and is always " +
                    "printed. A page that runs across a board edge names every board it touches (for example Boards 1+2).",
                "LAY THE PAGES" to "Nothing is cut yet. Start at board 1's top-left corner with page A1's paper edges on the board's top and left edges. " +
                    "Work left to right, top to bottom. Lay each later page ON TOP of the pages already in place, as it is. The page underneath prints thin " +
                    "guide lines and corner marks showing exactly where the top page's paper edges go: slide the top page until each of its paper edges " +
                    "touches the guide beside it. Marks appear only where two pages overlap.",
                "TAPE" to "When every paper edge is on its guide, tape the join.",
                "THE ${Decimal.format(MARGIN_MM, 0)} MM STRIP" to "At every join there is a ${Decimal.format(MARGIN_MM, 0)} mm strip of blank paper: it is the " +
                    "top page's unprinted margin lying over the page beneath. The artwork on both sides is exactly where it belongs; nothing is shifted " +
                    "or stretched to hide the strip. Continue each cut line straight across it, and any tab too.",
                "CUT AT THE DOTTED LINE" to "Where a page runs past the board's right or bottom edge, a dotted line marks that edge. Glue the page down " +
                    "on this board, cut the paper along the dotted line, and keep the offcut: it is the first piece of the next board (to the right, or below), " +
                    "butted against that board's left or top edge. A page at a corner is cut along both lines and gives pieces for up to three other boards. " +
                    "The dotted line is the only place paper is cut; over a dark cut region its dots turn white. At the far right and bottom of the last boards " +
                    "the offcut is waste. If a line would fall inside a 3 mm strip, it is printed beside the strip with a note of how far past it to cut.",
                "GLUE" to "Glue each board's pages down. The stencil starts ${inches(border)} in (${Decimal.format(border, 1)} mm) in from the board's edges; " +
                    "the rest of each page is blank paper over the border. Where a board's first piece is an offcut, butt it square against the board's " +
                    "left or top edge (square corners make that easy to see by eye).",
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
        private fun draw(
            sb: StringBuilder, lay: Lay, plan: Layout, vw: Double, twoCol: Boolean, sheetsInFile: Int,
            rows: List<Row>, paint: Boolean = rows.isNotEmpty(),
        ): Double {
            val end = body(sb, lay, plan, vw, twoCol, sheetsInFile, paint || plan.options.shopping.isNotEmpty())
            if (rows.isEmpty()) return end
            return block(sb, end + BLOCK_GAP + 12.0, rows, "Paint", true, 12.0)
        }

        private fun body(sb: StringBuilder, lay: Lay, plan: Layout, vw: Double, twoCol: Boolean, sheetsInFile: Int, paint: Boolean): Double {
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
            for ((n, step) in steps(sheetsInFile, plan, paint).withIndex()) {
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
                val h2 = boardDiagram(sb, dx, 30.0 + h1 + 24.0, dw * 0.9, mount, bg.landscape, lay.spec)
                return max(textEnd, 30.0 + h1 + 24.0 + h2) + 4.0
            }
            val half = (vw - 24.0) / 2
            val dTop = y + 6.0
            val h1 = diagram(sb, 0.0, dTop, half)
            val h2 = boardDiagram(sb, half + 24.0, dTop, half * 0.95, mount, bg.landscape, lay.spec)
            return dTop + max(h1, h2) + 4.0
        }

        /**
         * Two boards edge to edge, schematic: page A1 on the first board's corner, and a page running across the edge between the boards, a
         * dotted line on the edge, and the offcut that starts the next board. Returns its height.
         */
        private fun boardDiagram(sb: StringBuilder, x: Double, top: Double, w: Double, mount: MountOptions, landscape: Boolean, spec: TileSpec): Double {
            val b = mount.board.oriented(landscape)
            val scale = w / (2 * b.widthMm)
            val bw = b.widthMm * scale
            val h = b.heightMm * scale
            val inset = mount.borderMm * scale
            val pw = spec.pageWidthMm * scale
            val ph = spec.pageHeightMm * scale
            fun yy(v: Double) = -(top + v)
            // The two boards.
            for (i in 0..1) {
                sb.append("q\n0.8 w\n0 G\n").append(num(x + i * bw)).append(' ').append(num(yy(h))).append(' ').append(num(bw)).append(' ').append(num(h)).append(" re S\nQ\n")
                sb.append("q\n0.5 w\n0.4 G\n[3 2] 0 d\n").append(num(x + i * bw + inset)).append(' ').append(num(yy(h - inset))).append(' ')
                    .append(num(bw - 2 * inset)).append(' ').append(num(h - 2 * inset)).append(" re S\nQ\n")
            }
            // Page A1 on board 1's corner; a page across the edge between the boards.
            sb.append("q\n0.7 w\n0.15 G\n").append(num(x)).append(' ').append(num(yy(min(ph, h)))).append(' ').append(num(min(pw, bw))).append(' ')
                .append(num(min(ph, h))).append(" re S\nQ\n")
            val px0 = x + bw - pw * 0.4
            val py0 = h * 0.45
            sb.append("q\n0.7 w\n0.15 G\n1 g\n").append(num(px0)).append(' ').append(num(yy(py0 + min(ph, h * 0.5)))).append(' ').append(num(pw)).append(' ')
                .append(num(min(ph, h * 0.5))).append(" re B\nQ\n")
            // The dotted cut line on the edge, across the page.
            sb.append("q\n[0.01 3.0] 0 d\n1 J\n1.4 w\n0 G\n").append(num(x + bw)).append(' ').append(num(yy(py0 - 4.0))).append(" m ")
                .append(num(x + bw)).append(' ').append(num(yy(py0 + min(ph, h * 0.5) + 4.0))).append(" l S\nQ\n0 g\n")
            text(sb, 6.5, x + 4.0, yy(ph * 0.5), "page A1", bold = true)
            text(sb, 6.0, x + 4.0, yy(ph * 0.5 + 8.0), "paper edges on the board's edges")
            text(sb, 6.0, x + bw + 3.0, yy(py0 - 8.0), "dotted line: cut here", bold = true)
            text(sb, 6.0, x + bw + 3.0, yy(py0 + min(ph, h * 0.5) * 0.5), "offcut starts board 2")
            text(sb, 7.5, x, yy(h + 11.0), "Two boards edge to edge: cut on the dotted line", bold = true)
            text(sb, 7.0, x, yy(h + 21.0), "The offcut butts against board 2's left edge. Not to scale.")
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
