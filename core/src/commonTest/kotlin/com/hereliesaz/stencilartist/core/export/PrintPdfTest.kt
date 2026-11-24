package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.LiftZone
import com.hereliesaz.stencilartist.core.LiftDirection
import com.hereliesaz.stencilartist.core.Sheet
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrintPdfTest {
    private val k = 72.0 / 25.4
    private val ink = Sheet(0xFF000000.toInt())

    private fun stencil(width: Int, height: Int, cut: (Int, Int) -> Boolean): Separation =
        Separation(width, height, listOf(ink), IntArray(width * height) { i ->
            if (cut(i % width, i / width)) 0 else Separation.NONE
        })

    /** Vertical stripes of cut and uncut material, 55 mm wide at 450 mm: every page of a 3 x 2 Letter grid has cut on it. */
    private fun stripes() = stencil(300, 300) { x, _ -> (x / 40) % 2 == 0 }

    // ---- A small PDF reader: structure and content streams.

    private class Pdf(val s: String) {
        val pages = ArrayList<Pair<Double, Double>>()
        val contents = ArrayList<String>()
    }

    private fun parse(bytes: ByteArray): Pdf {
        val s = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        assertTrue(bytes.all { it >= 0 }, "7-bit ASCII only, so string index = byte offset")
        assertTrue(s.startsWith("%PDF-1.4\n"))
        assertTrue(s.endsWith("%%EOF\n"))
        val pdf = Pdf(s)
        val startxref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref\n", startxref), "startxref points at xref")
        val header = Regex("xref\n0 (\\d+)\n").find(s, startxref)!!
        val count = header.groupValues[1].toInt()
        val entriesStart = header.range.last + 1
        val entries = s.substring(entriesStart, entriesStart + count * 20)
        assertEquals("0000000000 65535 f \n", entries.substring(0, 20))
        for (i in 1 until count) {
            val e = entries.substring(i * 20, i * 20 + 20)
            assertTrue(Regex("\\d{10} 00000 n \n").matches(e), "entry $i is exactly 20 bytes: '$e'")
            assertTrue(s.startsWith("$i 0 obj\n", e.substring(0, 10).toInt()), "object $i at its offset")
        }
        assertTrue(Regex("trailer\n<< /Size $count /Root 1 0 R").containsMatchIn(s))
        for (m in Regex("<< /Length (\\d+) >>\nstream\n").findAll(s)) {
            val from = m.range.last + 1
            val end = s.indexOf("\nendstream", from)
            assertEquals(m.groupValues[1].toInt(), end - from, "stream length")
            pdf.contents.add(s.substring(from, end))
        }
        for (m in Regex("/Type /Page /Parent \\d+ 0 R /MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").findAll(s)) {
            pdf.pages.add(m.groupValues[1].toDouble() to m.groupValues[2].toDouble())
        }
        assertEquals(pdf.pages.size, Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt())
        assertEquals(pdf.pages.size, pdf.contents.size)
        return pdf
    }

    private class Poly(val pts: List<Pair<Double, Double>>, val width: Double)
    private class Word(val x: Double, val y: Double, val size: Double, val text: String, val bold: Boolean)

    /** What a content stream draws outside any clip path, in page points; clip rectangles are listed apart. */
    private class Drawn(val strokes: List<Poly>, val points: List<Pair<Double, Double>>, val clips: List<DoubleArray>, val words: List<Word>)

    private fun tokens(c: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' -> {
                    val sb = StringBuilder()
                    i++
                    while (c[i] != ')') {
                        if (c[i] == '\\') i++
                        sb.append(c[i]); i++
                    }
                    i++
                    out += "(" + sb
                }
                else -> {
                    val j = i
                    while (i < c.length && !c[i].isWhitespace()) i++
                    out += c.substring(j, i)
                }
            }
        }
        return out
    }

    private fun interpret(content: String): Drawn {
        class Gs(val m: DoubleArray, val clipped: Boolean, val width: Double, val size: Double, val bold: Boolean)
        var gs = Gs(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), false, 1.0, 0.0, false)
        val stack = ArrayList<Gs>()
        val nums = ArrayList<Double>()
        val strokes = ArrayList<Poly>()
        val points = ArrayList<Pair<Double, Double>>()
        val clips = ArrayList<DoubleArray>()
        val words = ArrayList<Word>()
        var path = ArrayList<ArrayList<Pair<Double, Double>>>()
        var lastRect: DoubleArray? = null
        var pendingClip = false
        var tx = 0.0
        var ty = 0.0
        fun apply(x: Double, y: Double) = (gs.m[0] * x + gs.m[2] * y + gs.m[4]) to (gs.m[1] * x + gs.m[3] * y + gs.m[5])
        var fontName = false
        var size = 0.0
        for (t in tokens(content)) {
            val n = t.toDoubleOrNull()
            if (n != null) { nums += n; continue }
            when {
                t.startsWith("(") -> {
                    if (!gs.clipped) words += Word(tx, ty, size * abs(gs.m[3]), t.substring(1), fontName)
                    continue
                }
                t.startsWith("/") -> { nums.clear(); if (t == "/F2") fontName = true else if (t == "/F1") fontName = false }
                t == "q" -> stack += gs
                t == "Q" -> gs = stack.removeAt(stack.size - 1)
                t == "cm" -> {
                    val (a, b, c, d, e, f) = nums.takeLast(6)
                    val m = gs.m
                    gs = Gs(doubleArrayOf(
                        a * m[0] + b * m[2], a * m[1] + b * m[3], c * m[0] + d * m[2], c * m[1] + d * m[3],
                        e * m[0] + f * m[2] + m[4], e * m[1] + f * m[3] + m[5],
                    ), gs.clipped, gs.width, gs.size, gs.bold)
                }
                t == "w" -> gs = Gs(gs.m, gs.clipped, nums.last() * abs(gs.m[0]), gs.size, gs.bold)
                t == "m" -> { path.add(arrayListOf(nums[nums.size - 2] to nums.last())) }
                t == "l" -> path.last().add(nums[nums.size - 2] to nums.last())
                t == "re" -> {
                    val (x, y, w, h) = nums.takeLast(4)
                    path.add(arrayListOf(x to y, x + w to y, x + w to y + h, x to y + h))
                    lastRect = doubleArrayOf(x, y, w, h)
                }
                t == "W" -> pendingClip = true
                t == "h" -> {}
                t == "n" || t == "S" || t == "f" || t == "f*" || t == "B" -> {
                    if (t == "n" && pendingClip) {
                        val r = lastRect!!
                        val (x0, y0) = apply(r[0], r[1])
                        val (x1, y1) = apply(r[0] + r[2], r[1] + r[3])
                        clips += doubleArrayOf(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0))
                        gs = Gs(gs.m, true, gs.width, gs.size, gs.bold)
                    }
                    if (!gs.clipped) for (sub in path) {
                        val dev = sub.map { apply(it.first, it.second) }
                        points += dev
                        if (t == "S" || t == "B") strokes += Poly(dev, gs.width)
                    }
                    path = ArrayList()
                    pendingClip = false
                }
                t == "Tf" -> size = nums.last()
                t == "Td" -> { tx = apply(nums[nums.size - 2], nums.last()).first; ty = apply(nums[nums.size - 2], nums.last()).second }
                else -> {}
            }
            nums.clear()
        }
        return Drawn(strokes, points, clips, words)
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun pageKind(c: String) = when {
        "How to put the pages together" in c -> "instructions"
        "assembly map" in c -> "map"
        "REFERENCE ONLY" in c -> "reference"
        else -> "tile"
    }

    private fun options(widthMm: Double, paper: Paper = Paper.US_LETTER, landscape: Boolean = false) = PrintOptions(widthMm, paper, landscape)

    private fun tileContent(pdf: Pdf, sheet: Int, total: Int, tile: Tile): String? {
        val label = "(Sheet ${sheet + 1} of $total - Row ${tile.row + 1} Col ${tile.col + 1} \\("
        return pdf.contents.firstOrNull { label in it }
    }

    /** The marks of [content], as polylines in finished-piece mm for [tile] of [grid]. */
    private fun marksOf(content: String, grid: TilePlan, tile: Tile, pageHeightPt: Double): List<List<Pair<Double, Double>>> =
        interpret(content).strokes.map { p ->
            p.pts.map { (x, y) -> (x / k - 3.0 + tile.clip.x0) to ((pageHeightPt - y) / k - 3.0 + tile.clip.y0) }
        }

    @Test
    fun gradientLiftTipIsPrintedWithoutChangingCutCoordinates() {
        val original = stencil(100, 100) { x, _ -> x < 50 }
        val marked = original.withLiftZones(listOf(LiftZone(0, 49, 50, LiftDirection.RIGHT)))
        assertTrue(original.liftZones.isEmpty())
        assertTrue(original.mask(0).contentEquals(marked.mask(0)))
        val printed = parse(PrintPdf.make(marked, options(100.0)))
        assertTrue(printed.contents.any { "(SOFT GRADIENT)" in it })
        assertTrue(printed.contents.any { "(Shim the right edge.)" in it })
        assertTrue(printed.contents.any { "(Spray lightly. Test first.)" in it })
        val plain = parse(PrintPdf.make(original, options(100.0)))
        assertFalse(plain.contents.any { "(SOFT GRADIENT)" in it })
    }

    // ---- Structure, paper and counts.

    @Test
    fun theFileIsWellFormedAndHasAnInstructionsPageThenOneMapPerSheet() {
        val pdf = parse(PrintPdf.make(stripes(), options(450.0)))
        assertEquals("instructions", pageKind(pdf.contents[0]))
        assertEquals(1, pdf.contents.count { pageKind(it) == "instructions" })
        assertEquals(1, pdf.contents.count { pageKind(it) == "map" })
        assertTrue("(Sheet 1 of 1 - assembly map)" in pdf.contents[1])
    }

    @Test
    fun pageCountIsInstructionsPlusMapPlusPrintedTilesPlusReferencePages() {
        val s = Separation(100, 100, List(12) { Sheet(0xFF334455.toInt()) }, IntArray(10000) { it / 1000 })
        val opts = options(100.0)
        val plan = PrintPdf.plan(s, opts)
        val pdf = parse(PrintPdf.make(s, opts))
        val references = pdf.contents.count { pageKind(it) == "reference" }
        assertEquals(1 + 12 + plan.printedPages + references, pdf.pages.size)
        for (sheet in 1..12) assertTrue(pdf.contents.any { "(Sheet $sheet of 12 - assembly map)" in it }, "map for sheet $sheet")
        // Sheets 10 and 11 (0-based) are never painted: their only tile is blank and skipped.
        assertEquals(2, plan.blankPages)
        assertEquals(10, pdf.contents.count { pageKind(it) == "tile" })
    }

    @Test
    fun aTileWithNothingToCutIsSkippedAndSaidSoOnTheMap() {
        // Cut only in the left 40% of a 3 x 2 piece: the right column is blank, the middle one has a little.
        val s = stencil(300, 300) { x, _ -> x < 120 }
        val opts = options(450.0)
        val plan = PrintPdf.plan(s, opts)
        assertEquals(3, plan.grid.cols)
        assertEquals(2, plan.grid.rows)
        val sheet = plan.sheets.single()
        assertEquals(listOf(0 to 0, 0 to 1), sheet.printed.map { it.col to it.row })
        assertEquals(4, sheet.blank.size)
        val pdf = parse(PrintPdf.make(s, opts))
        assertEquals(1 + 1 + 2, pdf.pages.size)
        val map = pdf.contents[1]
        assertTrue("(C1 blank)" in map && "(B2 blank)" in map && "(A1)" in map && "(A2)" in map, map)
        assertTrue("4 skipped as blank" in map)
        // Labels and map agree: exactly the printed tiles have a page.
        for (t in plan.grid.tiles) {
            val printed = sheet.printed.contains(t)
            assertEquals(printed, tileContent(pdf, 0, 1, t) != null, "tile ${t.row},${t.col}")
        }
    }

    @Test
    fun paperSizesOrientationAndCustomPaperSetTheMediaBox() {
        val s = stencil(40, 40) { _, _ -> true }
        fun box(o: PrintOptions) = parse(PrintPdf.make(s, o)).pages.toSet().single()
        val letter = box(options(100.0))
        assertEquals(612.0, letter.first, 0.01); assertEquals(792.0, letter.second, 0.01)
        val landscape = box(options(100.0, landscape = true))
        assertEquals(792.0, landscape.first, 0.01); assertEquals(612.0, landscape.second, 0.01)
        val a4 = box(options(100.0, Paper.A4))
        assertEquals(210 * k, a4.first, 0.01); assertEquals(297 * k, a4.second, 0.01)
        val custom = box(options(100.0, Paper.custom(300.0, 200.0)))
        assertEquals(200 * k, custom.first, 0.01); assertEquals(300 * k, custom.second, 0.01, "custom is portrait by default")
        val customLandscape = box(options(100.0, Paper.custom(200.0, 300.0), landscape = true))
        assertEquals(300 * k, customLandscape.first, 0.01); assertEquals(200 * k, customLandscape.second, 0.01)
    }

    @Test
    fun tinyPaperAndBadWidthsAreRefusedWithAReadableMessage() {
        val e = assertFailsWith<IllegalArgumentException> { PrintOptions(100.0, Paper.custom(100.0, 100.0)) }
        assertTrue("too small" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { PrintOptions(0.0) }
        val s = stencil(40, 40) { _, _ -> true }
        assertFailsWith<IllegalArgumentException> { PrintPdf.make(s, options(100.0), listOf(3)) }
        val huge = assertFailsWith<IllegalArgumentException> { PrintPdf.make(s, options(1_000_000.0)) }
        assertTrue(huge.message.orEmpty().isNotEmpty())
        assertFailsWith<IllegalArgumentException> { PrintPdf.make(stencil(40, 40) { _, _ -> true }, options(30000.0)) }
    }

    // ---- Pages, overlap and guides.

    private fun segments(poly: List<Pair<Double, Double>>) = poly.zipWithNext()

    private fun inside(p: Pair<Double, Double>, x0: Double, y0: Double, x1: Double, y1: Double, eps: Double = 0.02) =
        p.first >= x0 - eps && p.first <= x1 + eps && p.second >= y0 - eps && p.second <= y1 + eps

    /** The guide marks of every page of a 3 x 3 grid of [paper], keyed by (row, col), in finished-piece mm. */
    private fun gridMarks(paper: Paper, landscape: Boolean): Triple<TilePlan, Map<Pair<Int, Int>, List<List<Pair<Double, Double>>>>, Double> {
        val page = paper.oriented(landscape)
        val width = PrintSizing.widthMmForSheetsWide(2.5, page)
        val aspect = (1.5 * PrintPdf.stepHeightMm(page) + PrintPdf.printableHeightMm(page)) / width
        val sep = stencil(100, (100 * aspect).roundToInt()) { _, _ -> true }
        val o = PrintOptions(width, paper, landscape)
        val pdf = parse(PrintPdf.make(sep, o))
        val plan = PrintPdf.plan(sep, o).grid
        assertTrue(plan.cols >= 3 && plan.rows >= 3, "a grid with interior pages: ${plan.cols} x ${plan.rows}")
        val out = HashMap<Pair<Int, Int>, List<List<Pair<Double, Double>>>>()
        for (t in plan.tiles) out[t.row to t.col] = marksOf(assertNotNull(tileContent(pdf, 0, 1, t)), plan, t, page.heightMm * k)
        return Triple(plan, out, page.heightMm * k)
    }

    @Test
    fun sheetsAdvanceByThePaperSizeLessFourMillimetresSoTheTopPagesPaperEdgeIsOneMillimetreInsideTheUnderPagesPrintedArea() {
        assertEquals(4.0, PrintPdf.PAPER_OVERLAP_MM, 0.0)
        for (paper in Paper.PRESETS + Paper.custom(250.0, 180.0)) for (landscape in listOf(false, true)) {
            val page = paper.oriented(landscape)
            val width = PrintSizing.widthMmForSheetsWide(3.0, page)
            val aspect = 0.4
            val sep = stencil(100, 40) { _, _ -> true }
            val grid = PrintPdf.plan(sep, PrintOptions(width, paper, landscape)).grid
            assertEquals(3, grid.cols)
            val p = PrintPdf.printableWidthMm(page)
            for (c in 1 until grid.cols) {
                assertEquals(page.widthMm - 4.0, grid.tile(0, c).clip.x0 - grid.tile(0, c - 1).clip.x0, 1e-9, "step is the paper width less 4 mm")
                // The top page's paper edge (its window less the 3 mm margin) lies exactly 1 mm inside the under page's printed edge.
                val paperEdge = grid.tile(0, c).clip.x0 - 3.0
                assertEquals(1.0, grid.tile(0, c - 1).clip.x0 + p - paperEdge, 1e-9)
            }
            assertTrue(aspect > 0)
        }
    }

    @Test
    fun guideMarksLieOnTheTopPagesPaperEdgesInsideTheUnderPagesPrintedArea() {
        val (plan, marks, _) = gridMarks(Paper.US_LETTER, false)
        val p = plan.spec.printableWidthMm
        val ph = plan.spec.printableHeightMm
        var vertical = 0
        var horizontal = 0
        for (t in plan.tiles) {
            val strokes = marks.getValue(t.row to t.col)
            val x0 = t.clip.x0; val y0 = t.clip.y0
            val hasRight = t.col < plan.cols - 1
            val hasBelow = t.row < plan.rows - 1
            if (!hasRight && !hasBelow) assertTrue(strokes.isEmpty(), "the last page has nothing laid on it, so prints nothing")
            // The covering pages' paper edges, in finished mm: the stroke's inner edge touches them from outside (0.1 mm centre offset).
            val rightEdge = if (hasRight) plan.tile(t.row, t.col + 1).clip.x0 - 3.0 else Double.NaN
            val belowEdge = if (hasBelow) plan.tile(t.row + 1, t.col).clip.y0 - 3.0 else Double.NaN
            val dlEdge = if (hasBelow && t.col > 0) plan.tile(t.row + 1, t.col - 1).clip.x0 + p + 3.0 else Double.NaN
            for (poly in strokes) for ((a, b) in segments(poly)) {
                assertTrue(inside(a, x0, y0, x0 + p, y0 + ph, 1e-6) && inside(b, x0, y0, x0 + p, y0 + ph, 1e-6), "guide inside the under page's printed area")
                if (abs(a.first - b.first) < 1e-6) {
                    val onRight = hasRight && abs(a.first - (rightEdge - 0.1)) < 0.01
                    val onDl = hasBelow && t.col > 0 && abs(a.first - (dlEdge + 0.1)) < 0.01
                    assertTrue(onRight || onDl, "vertical guide at ${a.first} is on a covering paper edge")
                    vertical++
                } else {
                    assertTrue(abs(a.second - b.second) < 1e-6)
                    assertTrue(hasBelow && abs(a.second - (belowEdge - 0.1)) < 0.01, "horizontal guide at ${a.second} is on the lower page's paper edge")
                    horizontal++
                }
            }
            if (hasRight) assertTrue(strokes.any { poly -> segments(poly).any { (a, b) -> abs(a.first - b.first) < 1e-6 && abs(a.first - (rightEdge - 0.1)) < 0.01 } }, "guides for the page on the right")
            if (hasBelow) assertTrue(strokes.any { poly -> segments(poly).any { (a, b) -> abs(a.second - b.second) < 1e-6 && abs(a.second - (belowEdge - 0.1)) < 0.01 } }, "guides for the page below")
        }
        assertTrue(vertical > 0 && horizontal > 0)
    }

    @Test
    fun whereThreeOrFourPagesMeetEachCoveredPagePrintsGuidesForEveryPageThatCoversIt() {
        val (plan, marks, _) = gridMarks(Paper.A4, false)
        val p = plan.spec.printableWidthMm
        // Page (0,1) is covered by (0,2), (1,0), (1,1) and (1,2): an L at the paper corner of (1,2) and one at the corner of (1,0).
        val t = plan.tile(0, 1)
        val l = marks.getValue(0 to 1).filter { it.size == 3 }
        val corner = plan.tile(1, 2).clip.x0 - 3.0 - 0.1 to plan.tile(1, 1).clip.y0 - 3.0 - 0.1
        assertTrue(l.any { abs(it[1].first - corner.first) < 0.01 && abs(it[1].second - corner.second) < 0.01 }, "L at the corner of the page below and to the right")
        val dl = plan.tile(1, 0).clip.x0 + p + 3.0 + 0.1 to corner.second
        assertTrue(l.any { abs(it[1].first - dl.first) < 0.01 && abs(it[1].second - dl.second) < 0.01 }, "L at the corner of the page below and to the left")
        // The L's arms run along the covering pages' paper edges, 4 mm (clamped to the printed area).
        for (poly in l) for ((a, b) in segments(poly)) assertTrue(abs(a.first - b.first) < 1e-6 || abs(a.second - b.second) < 1e-6)
        assertTrue(t.clip.x0 >= 0.0)
    }

    @Test
    fun aSinglePageHasNoMarksAndOnlyThePageUnderAnotherPrintsAny() {
        val one = parse(PrintPdf.make(stencil(40, 40) { _, _ -> true }, options(100.0)))
        val tile = one.contents.single { pageKind(it) == "tile" }
        assertTrue(interpret(tile).strokes.isEmpty(), "no marks on a single page")
        val sep = stencil(60, 20) { _, _ -> true }
        val o = options(PrintSizing.widthMmForSheetsWide(2.0, Paper.US_LETTER))
        val plan = PrintPdf.plan(sep, o).grid
        assertEquals(2, plan.cols)
        assertEquals(1, plan.rows)
        val pdf = parse(PrintPdf.make(sep, o))
        val under = marksOf(assertNotNull(tileContent(pdf, 0, 1, plan.tile(0, 0))), plan, plan.tile(0, 0), 279.4 * k)
        assertEquals(2, under.size, "two ticks, one at each end of the covering paper edge")
        for (poly in under) { assertEquals(2, poly.size); for (pt in poly) assertEquals(plan.tile(0, 1).clip.x0 - 3.0 - 0.1, pt.first, 0.01) }
        val top = marksOf(assertNotNull(tileContent(pdf, 0, 1, plan.tile(0, 1))), plan, plan.tile(0, 1), 279.4 * k)
        assertTrue(top.isEmpty(), "the top page prints nothing: its own margin lies on the guides")
    }

    @Test
    fun visibleRegionsTileThePieceAndOnlyThreeMillimetreStripsAtJoinsAreUncovered() {
        val page = Paper.US_LETTER.oriented(false)
        val width = PrintSizing.widthMmForSheetsWide(3.0, page)
        val height = PrintSizing.widthMmForSheetsHigh(2.0, 1.0, page) // 2 pages high at aspect 1: a square piece is not what we want; use the height directly
        val aspect = ((2 - 1) * PrintPdf.stepHeightMm(page) + PrintPdf.printableHeightMm(page)) / width
        val sep = stencil(300, (300 * aspect).roundToInt()) { _, _ -> true }
        val o = PrintOptions(width, Paper.US_LETTER, false)
        val plan = PrintPdf.plan(sep, o).grid
        assertEquals(3, plan.cols); assertEquals(2, plan.rows)
        val p = plan.spec.printableWidthMm
        val ph = plan.spec.printableHeightMm
        // Topmost page whose PAPER (window plus 3 mm all round) covers a point, later pages (row by row) lying on top.
        fun top(x: Double, y: Double): Tile? = plan.tiles.lastOrNull { t ->
            x >= t.clip.x0 - 3.0 && x <= t.clip.x0 + p + 3.0 && y >= t.clip.y0 - 3.0 && y <= t.clip.y0 + ph + 3.0
        }
        val step = 0.05
        val pieceH = plan.heightMm
        // Along a line through the middle of the first row: only the two joins' margins are blank, 3 mm each.
        var blankAlong = 0.0
        var x = 0.0
        val y = ph / 2
        while (x < plan.widthMm) {
            val t = assertNotNull(top(x + step / 2, y))
            val shown = x + step / 2 >= t.clip.x0 && x + step / 2 <= t.clip.x0 + p
            if (!shown) blankAlong += step
            x += step
        }
        val endGapX = maxOf(0.0, plan.widthMm - (plan.tile(0, plan.cols - 1).clip.x0 + p))
        assertEquals(2 * 3.0 + endGapX, blankAlong, 0.15, "two joins across, a 3 mm blank strip at each (and any end of the piece past the last window)")
        // And down the first column.
        var blankDown = 0.0
        var yy = 0.0
        while (yy < pieceH) {
            val t = assertNotNull(top(p / 2, yy + step / 2))
            val shown = yy + step / 2 >= t.clip.y0 && yy + step / 2 <= t.clip.y0 + ph
            if (!shown) blankDown += step
            yy += step
        }
        val endGapY = maxOf(0.0, pieceH - (plan.tile(plan.rows - 1, 0).clip.y0 + ph))
        assertEquals(1 * 3.0 + endGapY, blankDown, 0.15, "one join down")
        assertTrue(height > 0.0)
        // Artwork at true positions: every tile page translates the piece so finished x lands at paper x = 3 mm + (x - window left).
        val pdf = parse(PrintPdf.make(sep, o))
        for (t in plan.tiles) {
            val c = assertNotNull(tileContent(pdf, 0, 1, t))
            val m = assertNotNull(Regex("(?m)^1 0 0 1 ([0-9.-]+) ([0-9.-]+) cm$").find(c))
            assertEquals((3.0 - t.clip.x0) * k, m.groupValues[1].toDouble(), 0.01, "x translation of tile ${t.row},${t.col}")
            assertEquals(279.4 * k - (3.0 - t.clip.y0 + plan.heightMm) * k, m.groupValues[2].toDouble(), 0.05, "y translation")
        }
    }

    @Test
    fun theGuideStrokeIsThinAndNothingIsDrawnOutsideThePrintableArea() {
        val s = stencil(600, 600) { x, y -> (x / 80) % 2 == 0 || (x == 100 && y == 100) }
        for (landscape in listOf(false, true)) {
            val o = options(520.0, Paper.A4, landscape)
            val pdf = parse(PrintPdf.make(s, o))
            val pageW = o.page.widthMm * k
            val pageH = o.page.heightMm * k
            val left = 3.0 * k
            val bottom = 3.0 * k
            val right = pageW - left
            val top = pageH - bottom
            val eps = 0.01
            for (c in pdf.contents) {
                val d = interpret(c)
                for ((x, y) in d.points) assertTrue(x >= left - eps && x <= right + eps && y >= bottom - eps && y <= top + eps, "point $x,$y in ${pageKind(c)}")
                for (r in d.clips) assertTrue(r[0] >= left - eps && r[1] >= bottom - eps && r[0] + r[2] <= right + eps && r[1] + r[3] <= top + eps, "clip inside window")
                for (w in d.words) {
                    val width = PdfText.width(w.text, w.size, w.bold)
                    assertTrue(w.x >= left - eps && w.x + width <= right + eps && w.y >= bottom - eps && w.y + w.size <= top + eps,
                        "word '${w.text}' at ${w.x},${w.y} width $width on ${pageKind(c)}")
                }
                if (pageKind(c) == "tile") for (p in d.strokes) assertTrue(p.width < 1.0 * k, "stroke ${p.width} is thin")
            }
        }
    }

    @Test
    fun everyTilePageIsClippedToItsPrintableArea() {
        val o = options(450.0)
        val pdf = parse(PrintPdf.make(stripes(), o))
        for (c in pdf.contents.filter { pageKind(it) == "tile" }) {
            val clip = interpret(c).clips.single()
            assertEquals(3 * k, clip[0], 0.01)
            assertEquals(3 * k, clip[1], 0.01)
            assertEquals((215.9 - 6) * k, clip[2], 0.01)
            assertEquals((279.4 - 6) * k, clip[3], 0.01)
            assertTrue("0.1 0.1 0.1 RG" in c, "dark outline as well as a fill")
        }
    }

    // ---- Instructions.

    @Test
    fun theInstructionsPageStatesTheConventionAndHasADiagram() {
        val pdf = parse(PrintPdf.make(stripes(), options(450.0)))
        val text = interpret(pdf.contents[0]).words.joinToString(" ") { it.text }
        for (needle in listOf("PRINT AT 100%", "Actual size", "LAY THE PAGES", "Nothing is cut", "ON TOP", "guide", "touches the guide",
            "only where two pages overlap", "TAPE", "left to right, top to bottom", "THE 3 MM STRIP", "3 mm strip of blank paper",
            "Continue each cut line straight across it", "REPEAT FOR EVERY SHEET", "CUT", "exactly 100 mm")) {
            assertTrue(needle in text, "instructions mention '$needle'")
        }
        assertFalse("trim" in text.lowercase(), "the instructions never ask for trimming: $text")
        // The diagram draws strokes (the pages and the marks) in virtual coordinates under a scale.
        assertTrue(interpret(pdf.contents[0]).strokes.size >= 6)
    }

    // ---- Cutting tips, migrated from the raster exporter.

    private fun pdfText(s: Separation, width: Double) = parse(PrintPdf.make(s, options(width))).let { it to it.contents }

    @Test
    fun cuttingAdviceCanUseCutawayWhenThereIsSafeRoom() {
        val width = 240
        val sizeMm = 200.0
        // Broad waste surrounds a small trapped stock island; unlike the old all-stock
        // placement, the printed warning belongs in the area we will remove.
        val cut: (Int, Int) -> Boolean = { x, y ->
            x in 20..220 && y in 20..220 && !(x in 100..110 && y in 100..110)
        }
        val pdf = parse(PrintPdf.make(stencil(width, width, cut), options(sizeMm)))
        val tile = pdf.contents.single { pageKind(it) == "tile" }
        val pos = assertNotNull(Regex("""/F2 7\.4 Tf\n([0-9.]+) ([0-9.]+) Td\n\(ISLAND""").find(tile))
        val leftMm = (pos.groupValues[1].toDouble() - 3 * k - 2.0) / k
        val topMm = (792.0 - pos.groupValues[2].toDouble() - 11.0) / k - 3.0
        // Independently sample the full note region, not just its anchor, against the
        // explicitly constructed cut mask.
        for (a in 1..8) for (b in 1..5) {
            val x = ((leftMm + (146.0 / k) * a / 9.0) * width / sizeMm).toInt()
            val y = ((topMm + (49.0 / k) * b / 6.0) * width / sizeMm).toInt()
            assertTrue(cut(x, y), "Cutting advice is not in the cutaway at pixel $x,$y")
        }
        assertTrue(tile.contains("q 1 g\nBT\n/F2 7.4 Tf"), "White type must contrast against black cutaways")
    }

    @Test
    fun paintingAdviceNeverEntersCutaway() {
        val width = 240
        val sizeMm = 200.0
        val cut: (Int, Int) -> Boolean = { x, _ -> x < 145 }
        val marked = stencil(width, width, cut)
            .withLiftZones(listOf(LiftZone(0, 144, 120, LiftDirection.RIGHT)))
        val pdf = parse(PrintPdf.make(marked, options(sizeMm)))
        val tile = pdf.contents.single { pageKind(it) == "tile" }
        val pos = assertNotNull(Regex("""/F2 7\.4 Tf\n([0-9.]+) ([0-9.]+) Td\n\(SOFT GRADIENT""").find(tile))
        val leftMm = (pos.groupValues[1].toDouble() - 3 * k - 2.0) / k
        val topMm = (792.0 - pos.groupValues[2].toDouble() - 11.0) / k - 3.0
        for (a in 1..8) for (b in 1..5) {
            val x = ((leftMm + (146.0 / k) * a / 9.0) * width / sizeMm).toInt()
            val y = ((topMm + (49.0 / k) * b / 6.0) * width / sizeMm).toInt()
            assertFalse(cut(x, y), "Painting advice overlaps the cutaway at pixel $x,$y")
        }
    }

    @Test
    fun aFineDetailTipIsPrintedOnUncutStockNeverOverTheCut() {
        val s = stencil(240, 240) { x, y -> x == 100 && y == 110 }
        val (pdf, contents) = pdfText(s, 200.0)
        assertEquals(3, pdf.pages.size, "instructions, map, one tile")
        val tile = contents.single { pageKind(it) == "tile" }
        assertTrue("(FINE DETAIL) Tj" in tile)
        assertTrue("(At 84,92mm) Tj" in tile)
        val m = assertNotNull(Regex("""/F2 7\.4 Tf\n([0-9.]+) ([0-9.]+) Td\n\(FINE DETAIL""").find(tile))
        val textX = m.groupValues[1].toDouble()
        val textY = m.groupValues[2].toDouble()
        val pointPerPixel = (200.0 * k) / 240.0
        val cutLeft = 3 * k + 100.0 * pointPerPixel
        val cutRight = cutLeft + pointPerPixel
        val cutTop = 792.0 - 3 * k - 110.0 * pointPerPixel
        val cutBottom = cutTop - pointPerPixel
        val labelLeft = textX - 2.0
        val labelRight = labelLeft + 146.0
        val labelTop = textY + 11.0
        val labelBottom = labelTop - 49.0
        assertTrue(labelRight < cutLeft || labelLeft > cutRight || labelTop < cutBottom || labelBottom > cutTop, "the note never touches the cut")
    }

    @Test
    fun thePageLabelAndCodeNeverSitOnACut() {
        val s = stencil(240, 240) { x, y -> x in 20..60 || y in 100..140 }
        val pdf = parse(PrintPdf.make(s, options(200.0)))
        val tile = pdf.contents.single { pageKind(it) == "tile" }
        val label = interpret(tile).words.single { it.text.startsWith("Sheet 1 of 1 - Row 1 Col 1 (A1)") }
        val width = PdfText.width(label.text, label.size)
        val mm = 200.0 / 240
        for (px in 0 until 240) for (py in 0 until 240) {
            if (!(px in 20..60 || py in 100..140)) continue
            val x0 = (3 + px * mm) * k
            val x1 = (3 + (px + 1) * mm) * k
            val y1 = 792.0 - (3 + py * mm) * k
            val y0 = 792.0 - (3 + (py + 1) * mm) * k
            val apart = label.x + width < x0 || label.x > x1 || label.y + label.size < y0 || label.y > y1
            assertTrue(apart, "label touches cut pixel $px,$py")
        }
    }

    @Test
    fun aCuttingTipCanBePrintedOnRemovedMaterialWhenStockIsTooSmall() {
        // Most of the image is an opening; the only surviving island cannot fit a note.
        val s = stencil(30, 30) { x, y -> x !in 12..17 || y !in 12..17 }
        val pdf = parse(PrintPdf.make(s, options(100.0)))
        assertEquals(3, pdf.pages.size, "instructions, map, tile: the removed region fits the note")
        val tile = pdf.contents.single { pageKind(it) == "tile" }
        assertTrue("(ISLAND) Tj" in tile)
        assertFalse("(REFERENCE ONLY - DO NOT CUT FROM THIS PAGE) Tj" in pdf.s)
        // Ink over an opaque dark opening must contrast with the printed dark paint.
        assertTrue("q 1 g\n" in tile)
    }

    @Test
    fun aCuttingProblemWithNeitherSafeRegionGetsItsOwnAdjacentReferenceDetail() {
        // Alternating vertical openings and stock are all narrower than the annotation.
        // Enclosing a pocket of stock produces an island without providing room for text.
        val s = stencil(60, 60) { x, y ->
            x % 12 < 5 || (x in 24..36 && (y == 24 || y == 36)) ||
                (y in 24..36 && (x == 24 || x == 36))
        }
        val pdf = parse(PrintPdf.make(s, options(120.0)))
        assertTrue("(REFERENCE ONLY - DO NOT CUT FROM THIS PAGE) Tj" in pdf.s)
        assertTrue(pdf.contents.any { pageKind(it) == "reference" && "(ISLAND) Tj" in it })
        assertTrue(pdf.contents.any { pageKind(it) == "reference" && "(SHEET 1 - LOCAL DETAIL) Tj" in it })
    }

    @Test
    fun bufferedTipPositionsCannotCrossFromStockIntoOpenings() {
        val mask = BooleanArray(100 * 100) { i -> (i % 100) >= 50 }
        val space = PrintPdf.UncutSpace(mask, 100, 100, 1.0)
        assertEquals(false, space.region(10.0, 10.0, 8.0, 8.0, 1.0))
        assertEquals(true, space.region(70.0, 10.0, 8.0, 8.0, 1.0))
        assertEquals(null, space.region(44.0, 10.0, 8.0, 8.0, 0.0))
        assertEquals(null, space.region(0.0, 10.0, 8.0, 8.0, 1.0))
        assertFalse(space.clear(70.0, 10.0, 8.0, 8.0, 1.0),
            "Page labels retain their stock-only policy")
    }

    @Test
    fun pervasiveIslandsArePrintedOnTilesOrNearbyReferenceDetailsWithoutBeingDropped() {
        val s = stencil(40, 40) { x, y -> !(x in setOf(4, 11, 18, 25, 32) && y in setOf(4, 11, 18, 25, 32)) }
        val pdf = parse(PrintPdf.make(s, options(150.0)))
        assertEquals(25, Regex("""\(ISLAND\) Tj""").findAll(pdf.s).count())
        assertFalse("(ISLAND) Tj" in pdf.contents.single { pageKind(it) == "tile" },
            "Scattered stock islands interrupt every sufficiently wide buffered note region")
        assertEquals(7, pdf.contents.count { pageKind(it) == "reference" },
            "All 25 tips remain on adjacent reference pages when no continuous safe rectangle fits")
    }

    @Test
    fun concentricIslandsWithTheSameBoundsCenterBothReceiveCutDetailReferences() {
        val s = stencil(41, 41) { x, y ->
            val onOuter = (x in 5..35 && (y == 5 || y == 35)) || (y in 5..35 && (x == 5 || x == 35))
            val onInner = (x in 13..27 && (y == 13 || y == 27)) || (y in 13..27 && (x == 13 || x == 27))
            onOuter || onInner
        }
        val pdf = parse(PrintPdf.make(s, options(150.0)))
        assertEquals(2, Regex("""\(ISLAND\) Tj""").findAll(pdf.s).count())
        assertEquals(2, Regex("""\(SHEET 1 - LOCAL DETAIL\) Tj""").findAll(pdf.s).count())
    }

    @Test
    fun aTipWhosePageIsBlankStillGoesToAReferencePage() {
        // A ring of cut around uncut material at the far right of a three-page piece; the cut sits in the right
        // pages, but the island's anchor is deep inside it. Whatever page owns it, the tip is not dropped.
        val s = stencil(300, 100) { x, y -> (x in 200..260 && (y == 20 || y == 80)) || (y in 20..80 && (x == 200 || x == 260)) }
        val pdf = parse(PrintPdf.make(s, options(450.0)))
        assertEquals(1, Regex("""\(ISLAND\) Tj""").findAll(pdf.s).count())
    }

    @Test
    fun highlyFragmentedStencilsFailBeforeGeneratingThousandsOfReferencePages() {
        val s = stencil(200, 200) { x, y -> (x + y) % 2 == 0 }
        val error = assertFailsWith<IllegalArgumentException> { PrintPdf.make(s, options(190.0)) }
        assertTrue(error.message.orEmpty().contains("Too many stencil details"))
    }

    @Test
    fun cuttingInstructionsDescribeBothSafePlacementRegions() {
        val pdf = parse(PrintPdf.make(stencil(20, 20) { x, _ -> x in 5..14 }, options(120.0)))
        val expected = "Cutting notes may sit in openings or retained stock, clear of cut edges"
        val words = Regex("""\(([^)]*)\) Tj""").findAll(pdf.contents.first())
            .joinToString(" ") { it.groupValues[1] }
        println("CUT_INSTRUCTIONS_TEXT=$words")
        assertTrue(words.contains(expected), "Flat-sheet cutting instructions must allow both regions")
        assertFalse(words.contains("sit only on material that stays"))
    }

    @Test
    fun multipleSheetsAreLabelledInOrderWithoutAssumingThreeOrGreyLayers() {
        val s = Separation(10, 10, List(4) { Sheet(0xFF113399.toInt()) }, IntArray(100) { (it % 10) / 3 })
        val pdf = parse(PrintPdf.make(s, options(100.0)))
        for (sheet in 1..4) {
            assertTrue(pdf.contents.any { "(Sheet $sheet of 4 - Row 1 Col 1 \\(A1\\)) Tj" in it || "(Sheet $sheet of 4 - assembly map) Tj" in it })
        }
        assertEquals(1 + 4 * 2, pdf.pages.size)
        assertTrue("0.0667 0.2 0.6 rg" in pdf.s, "the sheet's own colour")
    }

    @Test
    fun onlyTheRequestedSheetsAreWritten() {
        val s = Separation(10, 10, List(4) { Sheet(0xFF113399.toInt()) }, IntArray(100) { (it % 10) / 3 })
        val pdf = parse(PrintPdf.make(s, options(100.0), listOf(2)))
        assertEquals(1 + 2, pdf.pages.size)
        assertTrue("(Sheet 3 of 4 - assembly map) Tj" in pdf.contents[1])
    }
}
