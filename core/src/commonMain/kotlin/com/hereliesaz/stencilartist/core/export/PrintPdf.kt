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

/** One board of one sheet: its [printed] tiles (local to the board), the [skipped] ones and whether the whole board is [blank]. */
data class BoardPages(val board: BoardCell, val blank: Boolean, val printed: List<Tile>, val skipped: List<Tile>)

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
 * printer cannot print). Each printable area is one tile, and tiles advance by their printable size less
 * [OVERLAP_MM], so neighbours share a 1 mm band that BOTH print, artwork included. Tile i along an axis
 * starts at i * (printable - overlap) in finished-piece millimetres; the last tile is cut off at the
 * piece's edge.
 *
 * Marks. Marks exist only where two pages meet, inside the shared 1 mm bands, drawn on each band's
 * centre line (0.5 mm in from the printable edge, so 3.5 mm from the paper's edge) with a stroke of
 * [MARK_WIDTH_MM], thin enough to lie wholly inside the band. Where a vertical and a horizontal band cross
 * the page prints an L with arms of [MARK_ARM_MM] running inward along the two bands; where a band runs
 * out to the piece's outer edge it prints a straight arm of the same length. A page edge with no
 * neighbour has no mark, and a single page has none at all. A mark is defined in finished-piece
 * coordinates, so the two pages that share a band print the identical segments at the identical place:
 * the arms along the band coincide when one page is laid over the other, and an L's corner is the same
 * point on all the pages that meet there.
 *
 * Convention (the instructions page says the same). On every page with a neighbour to its left and/or
 * above, trim the 3 mm unprinted strip off that left and/or top side, cutting along the printed edge.
 * Then lay that page over its neighbour so the two bands coincide and each L sits exactly on the L
 * beneath; the trimmed page covers only the neighbour's blank right or bottom strip and its identical
 * band. Tape; work left to right, top to bottom.
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

    /** How far neighbouring pages overlap, in mm: the band the L marks sit in. Fixed. */
    const val OVERLAP_MM = 1.0

    /** Length of each arm of an L mark, in mm. */
    const val MARK_ARM_MM = 4.0

    /** Stroke width of the L marks, in mm; under [OVERLAP_MM] so a mark lies inside its band. */
    const val MARK_WIDTH_MM = 0.2

    /** The smallest printable width and height a page may have, in mm, so the instructions and notes fit. */
    const val MIN_PRINTABLE_MM = 120.0

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

    /** How far a tile advances across: printable width less the overlap. */
    fun stepWidthMm(page: Paper): Double = printableWidthMm(page) - OVERLAP_MM

    /** How far a tile advances down. */
    fun stepHeightMm(page: Paper): Double = printableHeightMm(page) - OVERLAP_MM

    /** [page] as the tiling spec the print uses. */
    fun spec(page: Paper): TileSpec = TileSpec(page.widthMm, page.heightMm, MARGIN_MM, OVERLAP_MM, page.name)

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
     * The marks [tile] prints, each a polyline in finished-piece mm (y down): an L of three points at
     * every crossing of two shared bands, and a straight tick of two points where a shared band runs out
     * to the piece's outer edge. Marks lie only on the centre lines of shared bands, 0.5 mm in from the
     * page's printable edge; a side with no neighbour has none, and a single page has none at all.
     */
    internal fun marks(grid: TilePlan, tile: Tile, bounded: Boolean = false): List<List<Point>> {
        val spec = grid.spec
        val x0 = tile.clip.x0
        val y0 = tile.clip.y0
        val h = OVERLAP_MM / 2
        val a = MARK_ARM_MM
        val xl = x0 + h
        val xr = x0 + spec.printableWidthMm - h
        val yt = y0 + h
        val yb = y0 + spec.printableHeightMm - h
        val left = tile.col > 0
        val right = tile.col < grid.cols - 1
        val top = tile.row > 0
        val bottom = tile.row < grid.rows - 1
        // Where a band runs out at the edge of the tiled region (a board's), its arm stops there, not at the page's edge.
        val xe = if (bounded && !right) min(xr, grid.widthMm - h) else xr
        val ye = if (bounded && !bottom) min(yb, grid.heightMm - h) else yb
        val out = ArrayList<List<Point>>()
        // Vertical bands: an arm from each end, an L where a horizontal band crosses there.
        for ((x, dx, present) in listOf(Triple(xl, 1, left), Triple(xr, -1, right))) {
            if (!present) continue
            for ((y, dy, crossed) in listOf(Triple(yt, 1, top), Triple(ye, -1, bottom))) {
                val av = min(a, ye - yt)
                val ah = min(a, xe - xl)
                out += if (crossed) listOf(Point(x + dx * ah, y), Point(x, y), Point(x, y + dy * av))
                else listOf(Point(x, y), Point(x, y + dy * av))
            }
        }
        // Horizontal bands: an arm from each end that no vertical band covers.
        for ((y, present) in listOf(yt to top, yb to bottom)) {
            if (!present) continue
            val ah = min(a, xe - xl)
            if (!left) out += listOf(Point(xl, y), Point(xl + ah, y))
            if (!right) out += listOf(Point(xe, y), Point(xe - ah, y))
        }
        return out
    }

    /** The part of the piece only [tile] owns: its tile less the overlap shared with the next tile along and down. */
    internal fun net(grid: TilePlan, tile: Tile): RectMm {
        val x1 = if (tile.col == grid.cols - 1) grid.widthMm else tile.clip.x0 + grid.spec.stepWidthMm
        val y1 = if (tile.row == grid.rows - 1) grid.heightMm else tile.clip.y0 + grid.spec.stepHeightMm
        return RectMm(tile.clip.x0, tile.clip.y0, x1, y1)
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

        init {
            val bg = boardGrid
            if (bg == null) {
                grid = Tiling.plan(widthMm, heightMm, spec)
                regions = listOf(Region(null, 0.0, 0.0, RectMm(0.0, 0.0, widthMm, heightMm), grid, null))
            } else {
                val border = checkNotNull(options.mount).borderMm
                // Page A1's paper lies on the board's top-left corner; pages run until they cover the cut area's far side.
                grid = Tiling.plan(bg.cutWidthMm + border - MARGIN_MM, bg.cutHeightMm + border - MARGIN_MM, spec)
                regions = bg.cells.map { cell ->
                    val c = cell.cut
                    Region(
                        cell, c.x0 - border + MARGIN_MM, c.y0 - border + MARGIN_MM,
                        RectMm(c.x0, c.y0, min(widthMm, c.x1), min(heightMm, c.y1)), grid,
                        RectMm(c.x0 - border, c.y0 - border, c.x1 + border, c.y1 + border),
                    )
                }
            }
        }

        /** The region and the tile within it that own the point ([x], [y]) of the piece. */
        fun locate(x: Double, y: Double): Pair<Int, Int> {
            val bg = boardGrid
            val bc = if (bg == null) 0 else min(bg.cols - 1, floor(x / bg.cutWidthMm).toInt())
            val br = if (bg == null) 0 else min(bg.rows - 1, floor(y / bg.cutHeightMm).toInt())
            val region = regions[br * (bg?.cols ?: 1) + bc]
            val col = min(grid.cols - 1, floor((x - region.ox) / spec.stepWidthMm).toInt())
            val row = min(grid.rows - 1, floor((y - region.oy) / spec.stepHeightMm).toInt())
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
        fun lay(region: Int, sheetTotal: Int): Lay {
            val r = regions[region]
            return Lay(grid, sheetTotal, RectMm(0.0, 0.0, widthMm, heightMm), r.ox, r.oy, if (r.board == null) null else r.art, r.board,
                boardGrid?.count ?: 0, r.bounds)
        }

        /** Per region, which tiles have cut in the part of the piece they alone own. */
        fun usage(mask: BooleanArray, width: Int, height: Int): Array<BooleanArray> {
            val used = Array(regions.size) { BooleanArray(grid.tiles.size) }
            val mm = widthMm / width
            val bg = boardGrid
            val t = if (bg == null) 0.0 else checkNotNull(options.mount).borderMm - MARGIN_MM
            fun axis(count: Int, cell: Double?, boards: Int, step: Double, tiles: Int): Pair<IntArray, IntArray> {
                val region = IntArray(count)
                val tile = IntArray(count)
                for (i in 0 until count) {
                    val v = (i + 0.5) * mm
                    val b = if (cell == null) 0 else min(boards - 1, floor(v / cell).toInt())
                    val local = if (cell == null) v else v - b * cell + t
                    region[i] = b
                    tile[i] = min(tiles - 1, floor(local / step).toInt())
                }
                return region to tile
            }
            val (rx, tx) = axis(width, bg?.cutWidthMm, bg?.cols ?: 1, spec.stepWidthMm, grid.cols)
            val (ry, ty) = axis(height, bg?.cutHeightMm, bg?.rows ?: 1, spec.stepHeightMm, grid.rows)
            val cols = bg?.cols ?: 1
            for (y in 0 until height) {
                val row = y * width
                val rowTile = ty[y] * grid.cols
                val rowRegion = ry[y] * cols
                for (x in 0 until width) if (mask[row + x]) used[rowRegion + rx[x]][rowTile + tx[x]] = true
            }
            return used
        }

        /** Whether region [r] is skipped: nothing is cut on it. */
        fun blank(used: Array<BooleanArray>, r: Int) = used[r].none { it }

        /**
         * Whether tile [t] of region [r] gets a page: it has cut in it or, on a printed board, it is the corner page A1
         * (tile 0), which is the reference the pages are glued down from and so is always printed.
         */
        fun needed(used: Array<BooleanArray>, r: Int, t: Int) = !blank(used, r) && (used[r][t] || (t == 0 && regions[r].board != null))
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
        val perSheet = sheets.map { sheet ->
            val used = layout.usage(separation.mask(sheet), separation.width, separation.height)
            val boards = layout.regions.mapIndexed { r, region ->
                val blank = layout.blank(used, r)
                val tiles = layout.grid.tiles
                BoardPages(
                    region.board ?: BoardCell(1, 0, 0, region.art), blank,
                    tiles.filterIndexed { t, _ -> layout.needed(used, r, t) },
                    // A blank board is skipped whole and counted as a board; a blank piece's tiles are blank pages.
                    if (blank && region.board != null) emptyList() else tiles.filterIndexed { t, _ -> !layout.needed(used, r, t) },
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
            val used = layout.usage(mask, separation.width, separation.height)
            val tips = StencilPrintTips.detect(
                mask, separation.width, separation.height, options.widthMm, maxTips = MAX_TIPS - tipsUsed,
            )
            tipsUsed += tips.size
            val mmPerPx = options.widthMm / separation.width
            val uncut = UncutSpace(mask, separation.width, separation.height, mmPerPx)
            val outline = Vectorizer.vectorizeSheet(separation, sheet)
            val shapes = outline.shapes.map { Placed(it.rings.map { r -> r.points }, mmPerPx, layout.heightMm) }
            val color = outline.color

            doc.addPage(first.pageW, first.pageH, if (layout.boardGrid == null) first.mapPage(sheet, used[0], shapes, color, doc)
            else first.boardMapPage(sheet, layout, used, shapes, color, doc))

            val required = LinkedHashMap<String, Note>()
            val homes = HashMap<Long, MutableList<Note>>()
            for (tip in tips) {
                val note = layout.note(sheet, tip, mmPerPx)
                required[note.key] = note
                homes.getOrPut(note.region.toLong() * layout.grid.tiles.size + note.tile) { ArrayList() } += note
            }
            val placed = HashSet<String>()
            for (r in layout.regions.indices) {
                if (layout.blank(used, r)) continue
                val lay = layout.lay(r, separation.sheets.size)
                for ((index, tile) in layout.grid.tiles.withIndex()) {
                    if (!layout.needed(used, r, index)) continue
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
        private val boardTotal: Int = 0, val bounds: RectMm? = null,
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

            // The marks: finished-piece geometry, so neighbours print the same ones.
            sb.append("q\n0 G\n").append(num(MARK_WIDTH_MM * K)).append(" w\n0 J\n0 j\n")
            for (m in marks(grid, tile, board != null)) {
                for ((i, pt) in m.withIndex()) {
                    sb.append(num(px(tile, pt.x + ox))).append(' ').append(num(py(tile, pt.y + oy))).append(if (i == 0) " m " else " l ")
                }
                sb.append("S\n")
            }
            sb.append("Q\n")

            trimLines(sb, tile)

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
         * On a board, where this page runs past the board's right or bottom edge a dashed line marks the edge, with a
         * caption on the blank paper beyond it: trim the overhang along it. Nothing else is printed past the board.
         */
        private fun trimLines(sb: StringBuilder, tile: Tile) {
            val bd = bounds ?: return
            val x0 = fx(tile)
            val y0 = fy(tile)
            val x1 = x0 + spec.printableWidthMm
            val y1 = y0 + spec.printableHeightMm
            sb.append("q\n0 G\n0 g\n0.35 w\n[3 2] 0 d\n")
            val caption = "TRIM TO BOARD EDGE"
            val size = 5.0
            val cw = PdfText.width(caption, size) / K
            if (bd.x1 > x0 && bd.x1 < x1 - 0.2) {
                val yEnd = min(y1, bd.y1)
                sb.append(num(px(tile, bd.x1))).append(' ').append(num(py(tile, y0))).append(" m ")
                    .append(num(px(tile, bd.x1))).append(' ').append(num(py(tile, yEnd))).append(" l S\n")
                if (bd.x1 + 1.5 + cw <= x1 - 0.5) text(sb, size, px(tile, bd.x1 + 1.5), py(tile, y0 + 4.0), caption)
            }
            if (bd.y1 > y0 && bd.y1 < y1 - 0.2) {
                val xEnd = min(x1, bd.x1)
                sb.append(num(px(tile, x0))).append(' ').append(num(py(tile, bd.y1))).append(" m ")
                    .append(num(px(tile, xEnd))).append(' ').append(num(py(tile, bd.y1))).append(" l S\n")
                if (x0 + 6.0 + cw <= x1 - 0.5) text(sb, size, px(tile, x0 + 6.0), py(tile, bd.y1 + 4.0), caption)
            }
            sb.append("Q\n")
        }

        /**
         * Boxes of [w] x [h] mm inside [tile]'s printable area, inset, that are on the piece and clear (or, if [beyondOk],
         * wholly beyond it). On a board a box on the piece must also lie within the board's cut area.
         */
        private fun candidates(tile: Tile, w: Double, h: Double, step: Double, buffer: Double, uncut: UncutSpace, beyondOk: Boolean): List<Rect> {
            val out = ArrayList<Rect>()
            val x0 = fx(tile) + INSET
            val y0 = fy(tile) + INSET
            val xMax = fx(tile) + spec.printableWidthMm - INSET - w
            val yMax = fy(tile) + spec.printableHeightMm - INSET - h
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
            val xMax = fx(tile) + spec.printableWidthMm - INSET - w
            val yMax = fy(tile) + spec.printableHeightMm - INSET - h
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
                "Paper: ${page.name} ${Decimal.format(page.pageWidthMm, 1)} x ${Decimal.format(page.pageHeightMm, 1)} mm, ${Decimal.format(MARGIN_MM, 0)} mm unprintable border, ${Decimal.format(OVERLAP_MM, 0)} mm overlap",
                "Print at 100%. Work left to right, top to bottom; trim, lay each page over the one before it, match the marks.",
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
        fun boardMapPage(sheet: Int, layout: Layout, used: Array<BooleanArray>, shapes: List<Placed>, color: Int, doc: PdfDoc): String {
            val bg = checkNotNull(layout.boardGrid)
            val border = checkNotNull(layout.options.mount).borderMm
            val sb = StringBuilder("0 g\n")
            var y = top - 14.0
            text(sb, 13.0, left, y, "Sheet ${sheet + 1} of $sheetTotal - assembly map", bold = true)
            val blankBoards = layout.regions.indices.count { layout.blank(used, it) }
            val pagesPrinted = layout.regions.indices.sumOf { r -> layout.grid.tiles.indices.count { layout.needed(used, r, it) } }
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
                val blank = layout.blank(used, r)
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
                    sb.append("q\n0.25 w\n0.55 G\n[1.5 1.5] 0 d\n")
                    for ((t, tile) in layout.grid.tiles.withIndex()) {
                        if (!layout.needed(used, r, t)) continue
                        val n = net(layout.grid, tile)
                        val nx0 = max(cut.x0, region.ox + n.x0)
                        val ny0 = max(cut.y0, region.oy + n.y0)
                        val nx1 = min(cut.x1, region.ox + n.x1)
                        val ny1 = min(cut.y1, region.oy + n.y1)
                        if (nx1 <= nx0 || ny1 <= ny0) continue
                        sb.append(num(ox0 + nx0 * k)).append(' ').append(num(oyTop - ny1 * k)).append(' ').append(num((nx1 - nx0) * k)).append(' ')
                            .append(num((ny1 - ny0) * k)).append(" re S\n")
                    }
                    sb.append("Q\n")
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
            val trim = "TRIM" to "Your printer leaves a ${Decimal.format(MARGIN_MM, 0)} mm strip around every page unprinted. On each page that has a " +
                "neighbour to its LEFT, cut off that strip along the left edge; on each page that has a neighbour ABOVE, cut off the strip " +
                "along the top edge. Cut exactly along the edge of the printing. Leave the right and bottom edges as printed."
            val align = "ALIGN" to "Work left to right, top to bottom, starting at A1. Lay each new page OVER the page to its left and the page above it, " +
                "so the trimmed edge covers that neighbour's blank strip and the ${Decimal.format(OVERLAP_MM, 0)} mm band that both pages print " +
                "coincides. Slide it until each mark on it sits exactly on the mark beneath. Marks appear only where two pages meet. " +
                "Hold the join up to a window or lamp: the marks show through."
            val tape = "TAPE" to "When the marks coincide, tape the join on the back. Do not trim anything else."
            val repeat = "REPEAT FOR EVERY SHEET" to if (sheetsInFile > 1) "This file holds $sheetsInFile sheets. Each is its own stencil with its own map; " +
                "assemble each one separately and do not mix pages from different sheets."
            else "Each sheet of a stencil is its own file section; if you print more sheets later, assemble each one separately."
            if (mount == null) return listOf(
                print,
                "FIND YOUR PAGES" to "Each sheet starts with an assembly map: the grid of pages, each with a short code (column letter, " +
                    "row number: A1, B1, A2 ...). Pages with nothing to cut are left out and marked blank. Every page has its sheet and code " +
                    "printed small on uncut material.",
                trim, align, tape, repeat,
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
                    "printed. A page never crosses a board edge, and marks only join pages of the same board.",
                trim, align.copy(second = align.second.replace("starting at A1.", "starting at A1 of one board, then do the same for each board.")), tape,
                "GLUE" to "Lay the taped pages on the board and glue them down with page A1's top and left paper edges flush with the board's " +
                    "top and left edges: the square corners make that easy to see by eye. The stencil starts ${inches(border)} in " +
                    "(${Decimal.format(border, 1)} mm) in from the board's edges; the rest of each page is blank paper over the border. " +
                    "Where a page runs past the board's right or bottom edge, a dashed line marks the board edge: trim the overhang along it.",
                "CUT" to "Cut through the paper and the board together along the dark outline. Grey notes (ISLAND, FINE DETAIL) sit only on " +
                    "material that stays. Pages marked REFERENCE ONLY show details too close to a cut for a note; they are not cutting templates.",
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
            text(sb, 7.0, x + inset + 6.0, yy(h / 2), "cut area (the stencil)")
            text(sb, 7.0, x + pw * 0.2, yy(ph * 0.12), "page A1", bold = true)
            text(sb, 6.0, x + pw * 0.2, yy(ph * 0.12 + 8.0), "paper flush with the board's")
            text(sb, 6.0, x + pw * 0.2, yy(ph * 0.12 + 15.0), "top and left edges")
            text(sb, 7.0, x + 3.0, yy(h - inset / 2 + 2.5), "border ${inches(mount.borderMm)} in")
            text(sb, 7.5, x, yy(h + 11.0), "One board: page A1, unprinted strip, border, cut area", bold = true)
            text(sb, 7.0, x, yy(h + 21.0), "The strip is ${Decimal.format(MARGIN_MM, 0)} mm. Not to scale.")
            return h + 24.0
        }

        /** Two neighbouring pages, schematic: the right page trimmed and laid over the left one, marks meeting. Returns its height. */
        private fun diagram(sb: StringBuilder, x: Double, top: Double, w: Double): Double {
            val h = w * 0.62
            val m = 12.0 // the unprinted border, exaggerated
            val band = 6.0 // the shared band, exaggerated
            val bWide = w * 0.5
            val aWide = w - bWide + m + band
            val ph = h - 28.0
            fun yy(v: Double) = -(top + v)
            val barLeft = x + aWide - m - band // trimmed left edge of B
            val winTop = m
            val winBottom = ph - m
            sb.append("q\n0.5 w\n0.4 G\n1 g\n").append(num(x)).append(' ').append(num(yy(ph))).append(' ').append(num(aWide)).append(' ')
                .append(num(ph)).append(" re B\nQ\n")
            sb.append("q\n0.3 w\n0.6 G\n[2 2] 0 d\n").append(num(x + m)).append(' ').append(num(yy(winBottom))).append(' ')
                .append(num(aWide - 2 * m)).append(' ').append(num(winBottom - winTop)).append(" re S\nQ\n")
            // The shared band, shaded.
            sb.append("q\n0.82 g\n").append(num(barLeft)).append(' ').append(num(yy(winBottom))).append(' ').append(num(band)).append(' ')
                .append(num(winBottom - winTop)).append(" re f\nQ\n")
            val cx = barLeft + band / 2
            val ya = top + winTop + band / 2
            val yb = top + winBottom - band / 2
            val arm = 22.0
            // Marks on page A, beneath, heavy grey: an arm from each end of the band.
            sb.append("q\n0.55 G\n2.2 w\n")
            for ((yTop, dir) in listOf(ya to 1, yb to -1)) {
                sb.append(num(cx)).append(' ').append(num(-yTop)).append(" m ").append(num(cx)).append(' ')
                    .append(num(-(yTop + dir * arm))).append(" l S\n")
            }
            sb.append("Q\n")
            // Page B, trimmed, over A: outline only, so A's marks show; its own marks lie exactly on them.
            sb.append("q\n0.8 w\n0 G\n").append(num(barLeft)).append(' ').append(num(yy(ph))).append(' ').append(num(bWide))
                .append(' ').append(num(ph)).append(" re S\nQ\n")
            sb.append("q\n0 G\n0.9 w\n")
            for ((yTop, dir) in listOf(ya to 1, yb to -1)) {
                sb.append(num(cx)).append(' ').append(num(-yTop)).append(" m ").append(num(cx)).append(' ')
                    .append(num(-(yTop + dir * arm))).append(" l S\n")
            }
            sb.append("Q\n0 g\n")
            text(sb, 7.5, x, yy(ph + 11.0), "A1: already in place", bold = true)
            text(sb, 7.5, barLeft + 6.0, yy(ph + 11.0), "B1: trimmed at its left edge, laid over A1", bold = true)
            text(sb, 7.0, x, yy(ph + 22.0), "Not to scale. The bands coincide and the marks lie exactly on each other.")
            return h
        }
    }
}
