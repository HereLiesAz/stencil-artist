package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.Separation
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

    // ---- Tiles, overlap and marks.

    @Test
    fun tilesAdvanceByThePrintableSizeLessOneMillimetreAndCoverThePieceWithoutGaps() {
        for (paper in Paper.PRESETS + Paper.custom(250.0, 180.0)) for (landscape in listOf(false, true)) {
            val page = paper.oriented(landscape)
            val spec = PrintPdf.spec(page)
            assertEquals(page.widthMm - 6.0, spec.printableWidthMm, 1e-9)
            assertEquals(page.widthMm - 6.0 - 1.0, spec.stepWidthMm, 1e-9)
            val plan = Tiling.plan(1500.0, 1100.0, spec)
            for (c in 1 until plan.cols) {
                val prev = plan.tile(0, c - 1).clip
                val cur = plan.tile(0, c).clip
                assertEquals(spec.stepWidthMm * c, cur.x0, 1e-9)
                assertEquals(1.0, prev.x1 - cur.x0, 1e-9, "neighbours share exactly 1 mm")
            }
            for (r in 1 until plan.rows) assertEquals(1.0, plan.tile(r - 1, 0).clip.y1 - plan.tile(r, 0).clip.y0, 1e-9)
            assertEquals(0.0, plan.tile(0, 0).clip.x0)
            assertEquals(1500.0, plan.tile(0, plan.cols - 1).clip.x1, 1e-9)
            assertEquals(1100.0, plan.tile(plan.rows - 1, 0).clip.y1, 1e-9)
        }
    }

    private fun segments(poly: List<Pair<Double, Double>>) = poly.zipWithNext()

    private fun key(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        fun f(p: Pair<Double, Double>) = Decimal.format(p.first, 2) + "," + Decimal.format(p.second, 2)
        return listOf(f(a), f(b)).sorted().joinToString("-")
    }

    private fun inside(p: Pair<Double, Double>, x0: Double, y0: Double, x1: Double, y1: Double, eps: Double = 0.02) =
        p.first >= x0 - eps && p.first <= x1 + eps && p.second >= y0 - eps && p.second <= y1 + eps

    /** The marks of every page of a 3 x 3 grid of [paper], keyed by (row, col). */
    private fun gridMarks(paper: Paper, landscape: Boolean): Triple<TilePlan, Map<Pair<Int, Int>, List<List<Pair<Double, Double>>>>, Double> {
        val page = paper.oriented(landscape)
        val width = PrintSizing.widthMmForSheetsWide(2.5, page)
        val aspect = (2.5 * PrintPdf.stepHeightMm(page) + PrintPdf.OVERLAP_MM) / width
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
    fun neighbouringPagesPrintIdenticalMarkSegmentsInTheirSharedBand() {
        val (plan, marks, _) = gridMarks(Paper.US_LETTER, false)
        val spec = plan.spec
        val pw = spec.printableWidthMm
        val ph = spec.printableHeightMm
        var compared = 0
        for (t in plan.tiles) {
            for ((dr, dc) in listOf(0 to 1, 1 to 0)) {
                if (t.row + dr >= plan.rows || t.col + dc >= plan.cols) continue
                val n = plan.tile(t.row + dr, t.col + dc)
                // The shared band: the next tile's first millimetre across (or down), over the pair's common run.
                val bx0 = n.clip.x0
                val by0 = n.clip.y0
                val (bx1, by1) = if (dc == 1) bx0 + 1.0 to by0 + ph else bx0 + pw to by0 + 1.0
                fun inBand(m: List<List<Pair<Double, Double>>>) = m.flatMap { segments(it) }
                    .filter { (a, b) -> inside(a, bx0, by0, bx1, by1) && inside(b, bx0, by0, bx1, by1) }
                    .map { (a, b) -> key(a, b) }.toSet()
                val mine = inBand(marks.getValue(t.row to t.col))
                val theirs = inBand(marks.getValue(n.row to n.col))
                assertTrue(mine.isNotEmpty(), "pages ${t.row},${t.col} and ${n.row},${n.col} have marks in their band")
                assertEquals(mine, theirs, "marks in the band shared by ${t.row},${t.col} and ${n.row},${n.col}")
                compared++
            }
        }
        assertEquals(2 * plan.cols * plan.rows - plan.cols - plan.rows, compared)
    }

    @Test
    fun theFourPagesMeetingAtACrossingAllPrintAnLWhoseCornerIsTheSamePoint() {
        val (plan, marks, _) = gridMarks(Paper.A4, false)
        val spec = plan.spec
        val cx = spec.stepWidthMm + 0.5
        val cy = spec.stepHeightMm + 0.5
        for ((r, c) in listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)) {
            val ls = marks.getValue(r to c).filter { it.size == 3 }
            assertTrue(ls.any { abs(it[1].first - cx) < 0.01 && abs(it[1].second - cy) < 0.01 }, "page $r,$c has an L at ($cx, $cy)")
        }
    }

    @Test
    fun everyMarkLiesOnTheCentreLineOfASharedBandAndOuterSidesHaveNone() {
        val (plan, marks, _) = gridMarks(Paper.US_LETTER, true)
        val spec = plan.spec
        val lines = { step: Double, n: Int -> (1 until n).map { it * step + 0.5 } }
        val xs = lines(spec.stepWidthMm, plan.cols)
        val ys = lines(spec.stepHeightMm, plan.rows)
        for (t in plan.tiles) {
            val x0 = t.clip.x0; val y0 = t.clip.y0
            val x1 = x0 + spec.printableWidthMm; val y1 = y0 + spec.printableHeightMm
            for (poly in marks.getValue(t.row to t.col)) for ((a, b) in segments(poly)) {
                val vertical = abs(a.first - b.first) < 1e-6
                val horizontal = abs(a.second - b.second) < 1e-6
                assertTrue(vertical || horizontal)
                if (vertical) assertTrue(xs.any { abs(it - a.first) <= 0.01 }, "vertical mark at x=${a.first} is on a band centre line")
                if (horizontal) assertTrue(ys.any { abs(it - a.second) <= 0.01 }, "horizontal mark at y=${a.second} is on a band centre line")
                // Nothing along an outer side of the piece.
                if (t.col == 0 && vertical) assertTrue(a.first > x0 + 1.0)
                if (t.col == plan.cols - 1 && vertical) assertTrue(a.first < x1 - 1.0)
                if (t.row == 0 && horizontal) assertTrue(a.second > y0 + 1.0)
                if (t.row == plan.rows - 1 && horizontal) assertTrue(a.second < y1 - 1.0)
                // Within the printable area, and within a band's width of the printable edge it belongs to.
                assertTrue(inside(a, x0, y0, x1, y1, 1e-6) && inside(b, x0, y0, x1, y1, 1e-6))
            }
        }
        val centre = marks.getValue(1 to 1)
        assertTrue(centre.any { it.size == 3 }, "an interior page has Ls at its band crossings")
    }

    @Test
    fun aSinglePageHasNoMarksAndAPieceOfTwoHasMarksOnlyOnTheSeam() {
        val one = parse(PrintPdf.make(stencil(40, 40) { _, _ -> true }, options(100.0)))
        val tile = one.contents.single { pageKind(it) == "tile" }
        assertTrue(interpret(tile).strokes.isEmpty(), "no marks on a single page")
        val sep = stencil(60, 20) { _, _ -> true }
        val o = options(PrintSizing.widthMmForSheetsWide(2.0, Paper.US_LETTER))
        val plan = PrintPdf.plan(sep, o).grid
        assertEquals(2, plan.cols)
        assertEquals(1, plan.rows)
        val pdf = parse(PrintPdf.make(sep, o))
        for (t in plan.tiles) {
            val m = marksOf(assertNotNull(tileContent(pdf, 0, 1, t)), plan, t, 279.4 * k)
            assertEquals(2, m.size, "two straight arms, one from each end of the seam")
            for (poly in m) assertEquals(2, poly.size)
            for (poly in m) for (p in poly) assertEquals(plan.spec.stepWidthMm + 0.5, p.first, 0.01)
        }
    }

    @Test
    fun markGeometryIsThreeAndAHalfMillimetresFromThePaperEdgeOnBandCentreLinesForEveryPaper() {
        for (paper in Paper.PRESETS + Paper.custom(260.0, 200.0)) for (landscape in listOf(false, true)) {
            val page = paper.oriented(landscape)
            val (plan, marks, pageH) = gridMarks(paper, landscape)
            val pageW = page.widthMm
            val tile = plan.tile(1, 1)
            // On the page (not finished) coordinates: a vertical arm sits 3.5 mm from the left or right paper edge.
            for (poly in marks.getValue(tile.row to tile.col)) for ((a, b) in segments(poly)) {
                val onPageX = a.first - tile.clip.x0 + 3.0
                val onPageY = a.second - tile.clip.y0 + 3.0
                if (abs(a.first - b.first) < 1e-6) {
                    assertTrue(abs(onPageX - 3.5) < 0.01 || abs(onPageX - (pageW - 3.5)) < 0.01, "x $onPageX on $paper $landscape")
                } else {
                    assertTrue(abs(onPageY - 3.5) < 0.01 || abs(onPageY - (pageH / k - 3.5)) < 0.01, "y $onPageY on $paper $landscape")
                }
            }
            // Arms are 4 mm long and the stroke is 0.2 mm.
            val arms = marks.getValue(tile.row to tile.col).flatMap { segments(it) }
            assertTrue(arms.all { (a, b) -> abs(abs(a.first - b.first) + abs(a.second - b.second) - 4.0) < 0.01 })
        }
    }

    @Test
    fun theStrokeIsNarrowerThanTheBandAndNothingIsDrawnOutsideThePrintableArea() {
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
                if (pageKind(c) == "tile") for (p in d.strokes) assertTrue(p.width < 1.0 * k, "stroke ${p.width} fits the 1 mm band")
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
        for (needle in listOf("PRINT AT 100%", "Actual size", "TRIM", "3 mm", "LEFT", "ABOVE", "Lay each new page OVER", "exactly on the mark beneath",
            "only where two pages meet", "TAPE", "left to right, top to bottom", "REPEAT FOR EVERY SHEET", "CUT", "exactly 100 mm")) {
            assertTrue(needle in text, "instructions mention '$needle'")
        }
        // The diagram draws strokes (the pages and the marks) in virtual coordinates under a scale.
        assertTrue(interpret(pdf.contents[0]).strokes.size >= 6)
    }

    // ---- Cutting tips, migrated from the raster exporter.

    private fun pdfText(s: Separation, width: Double) = parse(PrintPdf.make(s, options(width))).let { it to it.contents }

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
    fun aCuttingProblemWithNoRoomGetsItsOwnAdjacentReferenceDetail() {
        val s = stencil(30, 30) { x, y -> x !in 12..17 || y !in 12..17 }
        val pdf = parse(PrintPdf.make(s, options(100.0)))
        assertEquals(4, pdf.pages.size, "instructions, map, tile, one reference page")
        val all = pdf.s
        assertTrue("(REFERENCE ONLY - DO NOT CUT FROM THIS PAGE) Tj" in all)
        assertTrue("(ISLAND) Tj" in all)
        assertTrue("(SHEET 1 - LOCAL DETAIL) Tj" in all)
        val tile = pdf.contents.single { pageKind(it) == "tile" }
        assertFalse("(ISLAND) Tj" in tile, "the template page stays unannotated")
        assertTrue(pdf.contents.single { pageKind(it) == "reference" }.contains("(ISLAND) Tj"))
    }

    @Test
    fun pervasiveIslandsAllGetNearbyReferenceDetailsRatherThanBeingDropped() {
        val s = stencil(40, 40) { x, y -> !(x in setOf(4, 11, 18, 25, 32) && y in setOf(4, 11, 18, 25, 32)) }
        val pdf = parse(PrintPdf.make(s, options(150.0)))
        assertEquals(1 + 1 + 1 + 7, pdf.pages.size, "instructions, map, tile, seven reference pages of four")
        assertEquals(25, Regex("""\(ISLAND\) Tj""").findAll(pdf.s).count())
        assertEquals(7, pdf.contents.count { pageKind(it) == "reference" })
        assertFalse("(ISLAND) Tj" in pdf.contents.single { pageKind(it) == "tile" })
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
