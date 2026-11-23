package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.Sheet
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mounting on board: boards, board layout, corner targets, labels, map and instructions. */
class PrintMountTest {
    private val k = 72.0 / 25.4
    private val ink = Sheet(0xFF000000.toInt())
    private val poster = MountOptions(Board.POSTER_22X28)

    private fun stencil(width: Int, height: Int, cut: (Int, Int) -> Boolean): Separation =
        Separation(width, height, listOf(ink), IntArray(width * height) { i -> if (cut(i % width, i / width)) 0 else Separation.NONE })

    /** 400 x 400 px, 60 mm stripes of cut at 1 m wide (2.5 mm a pixel): every board has cut and clear stock for labels. */
    /** A fully cut 100 x 100 pixel piece: every board has cut everywhere. */
    private fun allCut() = stencil(100, 100) { _, _ -> true }

    private fun stripes() = stencil(400, 400) { x, _ -> (x / 24) % 2 == 0 }

    private fun options(width: Double = 1000.0, mount: MountOptions? = poster, paper: Paper = Paper.US_LETTER) =
        PrintOptions(width, paper, false, mount)

    // ---- A small content-stream reader (the print tests have their own; this one also reads curves).

    private class Page(val content: String) {
        val kind = when {
            "How to put the pages together" in content -> "instructions"
            "assembly map" in content -> "map"
            "REFERENCE ONLY" in content -> "reference"
            else -> "tile"
        }
    }

    private class Drawn(val strokes: List<List<Pair<Double, Double>>>, val points: List<Pair<Double, Double>>, val clips: List<DoubleArray>, val words: List<Triple<Double, Double, String>>, val sizes: List<Double>, val bold: List<Boolean>)

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
                    while (c[i] != ')') { if (c[i] == '\\') i++; sb.append(c[i]); i++ }
                    i++
                    out += "(" + sb
                }
                else -> { val j = i; while (i < c.length && !c[i].isWhitespace()) i++; out += c.substring(j, i) }
            }
        }
        return out
    }

    private fun interpret(content: String): Drawn {
        class Gs(val m: DoubleArray, val clipped: Boolean)
        var gs = Gs(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), false)
        val stack = ArrayList<Gs>()
        val nums = ArrayList<Double>()
        val strokes = ArrayList<List<Pair<Double, Double>>>()
        val points = ArrayList<Pair<Double, Double>>()
        val clips = ArrayList<DoubleArray>()
        val words = ArrayList<Triple<Double, Double, String>>()
        val sizes = ArrayList<Double>()
        val bold = ArrayList<Boolean>()
        var path = ArrayList<ArrayList<Pair<Double, Double>>>()
        var lastRect: DoubleArray? = null
        var pending = false
        var tx = 0.0
        var ty = 0.0
        var size = 0.0
        var isBold = false
        fun apply(x: Double, y: Double) = (gs.m[0] * x + gs.m[2] * y + gs.m[4]) to (gs.m[1] * x + gs.m[3] * y + gs.m[5])
        for (t in tokens(content)) {
            val n = t.toDoubleOrNull()
            if (n != null) { nums += n; continue }
            if (t.startsWith("(")) {
                if (!gs.clipped) { words += Triple(tx, ty, t.substring(1)); sizes += size * abs(gs.m[3]); bold += isBold }
                continue
            }
            when {
                t.startsWith("/") -> { nums.clear(); if (t == "/F2") isBold = true else if (t == "/F1") isBold = false }
                t == "q" -> stack += gs
                t == "Q" -> gs = stack.removeAt(stack.size - 1)
                t == "cm" -> {
                    val v = nums.takeLast(6)
                    val m = gs.m
                    gs = Gs(doubleArrayOf(
                        v[0] * m[0] + v[1] * m[2], v[0] * m[1] + v[1] * m[3], v[2] * m[0] + v[3] * m[2], v[2] * m[1] + v[3] * m[3],
                        v[4] * m[0] + v[5] * m[2] + m[4], v[4] * m[1] + v[5] * m[3] + m[5],
                    ), gs.clipped)
                }
                t == "m" -> path.add(arrayListOf(nums[nums.size - 2] to nums.last()))
                t == "l" -> path.last().add(nums[nums.size - 2] to nums.last())
                t == "c" -> path.last().add(nums[nums.size - 2] to nums.last())
                t == "re" -> {
                    val (x, y, w, h) = nums.takeLast(4)
                    path.add(arrayListOf(x to y, x + w to y, x + w to y + h, x to y + h))
                    lastRect = doubleArrayOf(x, y, w, h)
                }
                t == "W" -> pending = true
                t == "n" || t == "S" || t == "f" || t == "f*" || t == "B" -> {
                    if (t == "n" && pending) {
                        val r = lastRect!!
                        val (x0, y0) = apply(r[0], r[1])
                        val (x1, y1) = apply(r[0] + r[2], r[1] + r[3])
                        clips += doubleArrayOf(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0))
                        gs = Gs(gs.m, true)
                    }
                    if (!gs.clipped) for (sub in path) {
                        val dev = sub.map { apply(it.first, it.second) }
                        points += dev
                        if (t == "S" || t == "B") strokes += dev
                    }
                    path = ArrayList()
                    pending = false
                }
                t == "Tf" -> size = nums.last()
                t == "Td" -> { val p = apply(nums[nums.size - 2], nums.last()); tx = p.first; ty = p.second }
            }
            nums.clear()
        }
        return Drawn(strokes, points, clips, words, sizes, bold)
    }

    private operator fun <T> List<T>.component5(): T = this[4]

    private fun pages(bytes: ByteArray): List<Page> {
        val s = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        assertTrue(s.startsWith("%PDF-1.4\n") && s.endsWith("%%EOF\n"))
        val startxref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref\n", startxref))
        val count = Regex("xref\n0 (\\d+)\n").find(s, startxref)!!.groupValues[1].toInt()
        val entries = s.substring(Regex("xref\n0 (\\d+)\n").find(s, startxref)!!.range.last + 1).take(count * 20)
        for (i in 1 until count) assertTrue(s.startsWith("$i 0 obj\n", entries.substring(i * 20, i * 20 + 10).toInt()), "xref entry $i")
        val out = ArrayList<Page>()
        for (m in Regex("<< /Length (\\d+) >>\nstream\n").findAll(s)) {
            val from = m.range.last + 1
            val end = s.indexOf("\nendstream", from)
            assertEquals(m.groupValues[1].toInt(), end - from)
            out += Page(s.substring(from, end))
        }
        assertEquals(out.size, Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt())
        return out
    }

    // ---- Boards and options.

    @Test
    fun aBoardIsAtLeastEighteenInchesAndABorderAtLeastOneInch() {
        val tooSmall = assertFailsWith<IllegalArgumentException> { Board.custom(400.0, 600.0) }
        assertTrue("18 in" in tooSmall.message.orEmpty(), tooSmall.message)
        assertFailsWith<IllegalArgumentException> { Board.custom(600.0, 450.0) }
        Board.custom(457.2, 457.2) // exactly 18 in is allowed
        val border = assertFailsWith<IllegalArgumentException> { MountOptions(Board.POSTER_22X28, 20.0) }
        assertTrue("at least 1 in" in border.message.orEmpty(), border.message)
        MountOptions(Board.POSTER_22X28, 25.4)
        assertEquals(50.8, poster.borderMm, 1e-9)
        assertFailsWith<IllegalArgumentException> { MountOptions(Board.POSTER_22X28, 300.0) }
        assertEquals(558.8, Board.POSTER_22X28.widthMm, 1e-9)
        assertEquals(711.2, Board.POSTER_22X28.heightMm, 1e-9)
    }

    @Test
    fun theCutAreaIsTheBoardLessTheBorderOnAllFourSides() {
        val (w, h) = poster.cutSize(false)
        assertEquals(18 * 25.4, w, 1e-9)
        assertEquals(24 * 25.4, h, 1e-9)
        val (lw, lh) = poster.cutSize(true)
        assertEquals(24 * 25.4, lw, 1e-9)
        assertEquals(18 * 25.4, lh, 1e-9)
        val custom = MountOptions(Board.custom(500.0, 700.0), 30.0)
        assertEquals(440.0 to 640.0, custom.cutSize(false))
    }

    @Test
    fun theOrientationNeedingFewerBoardsWinsAndATieIsPortrait() {
        // 1200 x 900 mm: portrait 3 x 2 = 6, landscape 2 x 2 = 4.
        val landscape = BoardGrid.of(1200.0, 900.0, poster)
        assertTrue(landscape.landscape)
        assertEquals(2, landscape.cols); assertEquals(2, landscape.rows); assertEquals(4, landscape.count)
        // 400 x 400: one board either way.
        assertFalse(BoardGrid.of(400.0, 400.0, poster).landscape)
        // 1000 x 1000: 3 x 2 portrait = 6, 2 x 3 landscape = 6: a tie.
        val tie = BoardGrid.of(1000.0, 1000.0, poster)
        assertFalse(tie.landscape)
        assertEquals(6, tie.count)
        // Cells butt exactly with no overlap.
        for (a in tie.cells) for (b in tie.cells) {
            if (a.col + 1 == b.col && a.row == b.row) assertEquals(a.cut.x1, b.cut.x0, 0.0)
            if (a.row + 1 == b.row && a.col == b.col) assertEquals(a.cut.y1, b.cut.y0, 0.0)
        }
    }

    @Test
    fun theUnmountedPrintIsUnchangedAndThePlanSaysNothingAboutBoards() {
        val plan = PrintPdf.plan(stripes(), PrintOptions(450.0))
        assertEquals(null, plan.boards)
        assertEquals(0, plan.boardsPerSheet)
        assertTrue(plan.sheets.single().boards.isEmpty())
    }

    // ---- Pages, boards and the single grid.

    private val pw = 215.9
    private val ph = 279.4
    private val stepX = pw - 4.0
    private val stepY = ph - 4.0
    private val bw = 558.8
    private val bh = 711.2

    /** A tile page's boards, and its column and row (0-based) in the one grid, from its label. */
    private class Where(val boards: List<Int>, val col: Int, val row: Int)

    private fun whereIs(d: Drawn): Where? {
        val label = d.words.map { it.third }.firstOrNull { Regex("Sheet \\d+ of \\d+ - (Board \\d+ of \\d+|Boards [0-9+]+) - [A-Z]+\\d+").matches(it) } ?: return null
        val m = Regex("- (Board (\\d+) of \\d+|Boards ([0-9+]+)) - ([A-Z]+)(\\d+)$").find(label)!!
        val boards = if (m.groupValues[2].isNotEmpty()) listOf(m.groupValues[2].toInt()) else m.groupValues[3].split("+").map { it.toInt() }
        return Where(boards, m.groupValues[4][0] - 'A', m.groupValues[5].toInt() - 1)
    }

    private fun windowLeft(w: Where) = 3.0 + w.col * stepX
    private fun windowTop(w: Where) = 3.0 + w.row * stepY

    /** Page points to grid (board rectangle) mm for the page at [w]. */
    private fun grid(p: Pair<Double, Double>, w: Where, pageH: Double = ph * k) =
        (p.first / k - 3.0 + windowLeft(w)) to ((pageH - p.second) / k - 3.0 + windowTop(w))

    private fun plan(sep: Separation, o: PrintOptions) = PrintPdf.plan(sep, o)

    @Test
    fun theMountedStepIsThePaperOnlyStepAndOneGridSpansTheBoardRectangleFromTheOrigin() {
        val sep = allCut()
        val opts = options(1000.0)
        val p = plan(sep, opts)
        val g = p.grid
        // 3 x 2 boards of 558.8 x 711.2 mm: a 1676.4 x 1422.4 mm rectangle.
        assertEquals(3 * bw, g.widthMm, 1e-9)
        assertEquals(2 * bh, g.heightMm, 1e-9)
        // The step is exactly the paper-only one: paper size less 4 mm, and the first window is the paper's own 3 mm margin in.
        assertEquals(3.0, g.tile(0, 0).clip.x0, 1e-9)
        assertEquals(3.0, g.tile(0, 0).clip.y0, 1e-9)
        for (c in 1 until g.cols) assertEquals(stepX, g.tile(0, c).clip.x0 - g.tile(0, c - 1).clip.x0, 1e-9)
        for (r in 1 until g.rows) assertEquals(stepY, g.tile(r, 0).clip.y0 - g.tile(r - 1, 0).clip.y0, 1e-9)
        assertEquals(PrintPdf.stepWidthMm(Paper.US_LETTER), stepX, 1e-9)
        // The windows reach the rectangle's far edges.
        assertTrue(g.tile(0, g.cols - 1).clip.x0 + 209.9 >= g.widthMm - 3.0 - 1e-6)
        assertTrue(g.tile(g.rows - 1, 0).clip.y0 + 273.4 >= g.heightMm - 3.0 - 1e-6)
        assertEquals(6, p.boardsPerSheet)
    }

    @Test
    fun thePlanReportsBoardsOrientationBlankBoardsAndPages() {
        // Cut only in the left 30% of a 1 m piece: with portrait boards (457.2 mm) only board column 0 has cut.
        val s = stencil(400, 400) { x, _ -> x < 120 }
        val p = plan(s, options())
        val grid = assertNotNull(p.boards)
        assertEquals(3, grid.cols); assertEquals(2, grid.rows)
        assertFalse(grid.landscape)
        assertEquals(6, p.boardsPerSheet)
        assertEquals(4, p.blankBoards)
        assertEquals(2, p.printedBoards)
        val boards = p.sheets.single().boards
        assertEquals(listOf(false, true, true, false, true, true), boards.map { it.blank })
        // Pages come from the single grid: the printed and skipped ones together are the whole grid.
        assertEquals(p.tilesPerSheet, p.sheets.single().printed.size + p.sheets.single().blank.size)
        assertTrue(p.sheets.single().printed.isNotEmpty())
        assertTrue(boards.filter { !it.blank }.all { it.printed.isNotEmpty() })
    }

    @Test
    fun theTopLeftPageIsAlwaysPrintedWhenAnythingIsAndNothingIsPrintedWhenNothingIsCut() {
        // Cut only far from the top-left corner.
        val sep = stencil(100, 100) { x, y -> x >= 80 && y >= 80 }
        val p = plan(sep, options(400.0))
        assertTrue(p.sheets.single().printed.any { it.col == 0 && it.row == 0 }, "A1 is the squaring reference")
        assertTrue(p.sheets.single().blank.isNotEmpty())
        val pdf = pages(PrintPdf.make(sep, options(400.0)))
        assertTrue(pdf.filter { it.kind == "tile" }.mapNotNull { whereIs(interpret(it.content)) }.any { it.col == 0 && it.row == 0 })
        val none = plan(stencil(100, 100) { _, _ -> false }, options(400.0))
        assertTrue(none.sheets.single().printed.isEmpty())
    }

    @Test
    fun pageLabelsNameEveryBoardAPageTouchesAndTheMapShowsBoardsPagesAndDottedLines() {
        val sep = allCut()
        val pdf = pages(PrintPdf.make(sep, options(1000.0)))
        assertEquals("instructions", pdf[0].kind)
        assertEquals(1, pdf.count { it.kind == "map" })
        val labels = pdf.filter { it.kind == "tile" }.map { interpret(it.content).words.map { w -> w.third }.first { w -> w.startsWith("Sheet ") } }
        assertTrue(labels.any { Regex("Sheet 1 of 1 - Board 1 of 6 - A1").matches(it) }, labels.take(3).toString())
        assertTrue(labels.any { Regex("Sheet 1 of 1 - Boards 1\\+2 - [A-Z]\\d").matches(it) }, "a page across the edge between boards 1 and 2")
        assertTrue(labels.any { Regex("Sheet 1 of 1 - Boards 1\\+4 - [A-Z]\\d").matches(it) }, "and one across the edge between boards 1 and 4")
        assertTrue(labels.any { Regex("Sheet 1 of 1 - Boards [0-9]\\+[0-9]\\+[0-9]\\+[0-9] - [A-Z]\\d").matches(it) }, "and one at a corner touching four boards")
        val map = pdf.first { it.kind == "map" }.content
        assertTrue("[0.01 3.0] 0 d" in map, "dotted lines on the map")
        val words = interpret(map).words.map { it.third }
        assertTrue("Board 1" in words && "Board 6" in words, words.toString())
        assertTrue(words.any { "Boards: 6 (3 across x 2 down, portrait)" in it })
    }

    @Test
    fun aBlankBoardIsMarkedBlankOnTheMap() {
        val s = stencil(400, 400) { x, _ -> x < 120 }
        val map = pages(PrintPdf.make(s, options())).first { it.kind == "map" }.content
        val words = interpret(map).words.map { it.third }
        assertTrue("Board 2 blank" in words && "Board 3 blank" in words && "Board 5 blank" in words && "Board 6 blank" in words, words.toString())
        assertTrue("Board 1" in words && "Board 4" in words)
    }

    /** The dotted cut lines of a tile page, as ((x1, y1), (x2, y2)) in grid mm. */
    private fun dottedLines(content: String, w: Where): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
        val out = ArrayList<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        val re = Regex("\\[0\\.01 3\\.6\\] 0 d\n1 J\n1\\.7 w\n0\\.1 0\\.1 0\\.1 RG\n(\\S+) (\\S+) m (\\S+) (\\S+) l S")
        for (m in re.findAll(content)) {
            val a = grid(m.groupValues[1].toDouble() to m.groupValues[2].toDouble(), w)
            val b = grid(m.groupValues[3].toDouble() to m.groupValues[4].toDouble(), w)
            out += a to b
        }
        return out
    }

    @Test
    fun everyPageCrossingABoardEdgeHasADottedLineExactlyOnItAndNoneElsewhere() {
        val sep = allCut()
        val opts = options(1000.0)
        val g = plan(sep, opts).grid
        val pdf = pages(PrintPdf.make(sep, opts))
        val rw = g.widthMm
        val rh = g.heightMm
        var crossings = 0
        for (page in pdf.filter { it.kind == "tile" }) {
            val d = interpret(page.content)
            val w = assertNotNull(whereIs(d))
            val lines = dottedLines(page.content, w)
            val wl = windowLeft(w); val wt = windowTop(w)
            // The vertical and horizontal board edges that cross this page's printed area.
            val expectedX = (1..3).map { it * bw }.filter { it > wl + 0.7 && it <= wl + 209.9 }
            val expectedY = (1..2).map { it * bh }.filter { it > wt + 0.7 && it <= wt + 273.4 }
            val xs = lines.filter { (a, b) -> abs(a.first - b.first) < 0.01 }
            val ys = lines.filter { (a, b) -> abs(a.second - b.second) < 0.01 }
            assertEquals(expectedX.size, xs.size, "vertical dotted lines on page ${w.col},${w.row}")
            assertEquals(expectedY.size, ys.size, "horizontal dotted lines on page ${w.col},${w.row}")
            for ((line, edge) in xs.zip(expectedX)) {
                val (a, b) = line
                // On the edge itself (or, if the edge is in the 3 mm strip, just before it on the visible side).
                val nextLeft = if (w.col < g.cols - 1) g.tile(w.row, w.col + 1).clip.x0 - 3.0 else Double.NaN
                val inStrip = !nextLeft.isNaN() && edge >= nextLeft && edge <= nextLeft + 3.0
                assertEquals(if (inStrip) nextLeft - 0.3 else edge, a.first, 0.01, "dotted line exactly on the board edge")
                // Across the page's printed extent, within the rectangle.
                assertEquals(wt, minOf(a.second, b.second), 0.01)
                assertEquals(minOf(wt + 273.4, rh), maxOf(a.second, b.second), 0.01)
                crossings++
            }
            for ((line, edge) in ys.zip(expectedY)) {
                val (a, b) = line
                val nextTop = if (w.row < g.rows - 1) g.tile(w.row + 1, w.col).clip.y0 - 3.0 else Double.NaN
                val inStrip = !nextTop.isNaN() && edge >= nextTop && edge <= nextTop + 3.0
                assertEquals(if (inStrip) nextTop - 0.3 else edge, a.second, 0.01)
                assertEquals(wl, minOf(a.first, b.first), 0.01)
                assertEquals(minOf(wl + 209.9, rw), maxOf(a.first, b.first), 0.01)
                crossings++
            }
        }
        assertTrue(crossings >= 10, "pages cross board edges: $crossings")
    }

    @Test
    fun dotsContrastWithWhatTheyCrossBlankPaperDarkAndLight() {
        val clip = "0 0 100 100 re W n\n"
        val dark = PrintPdf.cutLineOps(10.0, 10.0, 10.0, 90.0, 0xFF101820.toInt(), clip)
        val light = PrintPdf.cutLineOps(10.0, 10.0, 10.0, 90.0, 0xFFF4F0E0.toInt(), clip)
        val blank = PrintPdf.cutLineOps(10.0, 10.0, 10.0, 90.0, 0xFF101820.toInt(), null)
        // Over blank paper: dark dots only, nothing else.
        assertEquals(1, Regex("0\\.1 0\\.1 0\\.1 RG").findAll(blank).count())
        assertFalse("1 1 1 RG" in blank)
        // Over a light fill the dark dots already contrast: no second pass.
        assertFalse("1 1 1 RG" in light)
        assertTrue("0.1 0.1 0.1 RG" in light)
        // Over a dark fill: dark dots, then the same dots in white, inside the clip to the cut shapes, so each dot suits what lies under it.
        assertTrue("0.1 0.1 0.1 RG" in dark)
        val white = dark.indexOf("1 1 1 RG")
        assertTrue(white > dark.indexOf("0.1 0.1 0.1 RG"))
        assertTrue(dark.indexOf("W n") in 0 until white, "the white pass is clipped to the cut shapes")
        // Round dots (round caps and a zero-length dash), distinct from the thin solid guides and the outline.
        assertTrue("[0.01 3.6] 0 d" in dark && "1 J" in dark && "1.7 w" in dark)
        assertTrue(PrintPdf.luminance(0xFF000000.toInt()) < 0.01 && PrintPdf.luminance(0xFFFFFFFF.toInt()) > 0.99)
        // The pivot is half luminance: mid grey 0x80 is just over.
        val mid = PrintPdf.cutLineOps(0.0, 0.0, 1.0, 1.0, 0xFF808080.toInt(), clip)
        assertFalse("1 1 1 RG" in mid)
        val darkish = PrintPdf.cutLineOps(0.0, 0.0, 1.0, 1.0, 0xFF707070.toInt(), clip)
        assertTrue("1 1 1 RG" in darkish)
    }

    /** The art blocks of a tile page: each clip rectangle in grid mm and the translation of its finished-piece art. */
    private fun artBlocks(content: String, w: Where): List<Pair<DoubleArray, Double>> {
        val out = ArrayList<Pair<DoubleArray, Double>>()
        val re = Regex("q\n(\\S+) (\\S+) (\\S+) (\\S+) re W n\n1 0 0 1 (\\S+) (\\S+) cm\n")
        for (m in re.findAll(content)) {
            val x = m.groupValues[1].toDouble(); val y = m.groupValues[2].toDouble()
            val cw = m.groupValues[3].toDouble(); val ch = m.groupValues[4].toDouble()
            val x0 = x / k - 3.0 + windowLeft(w)
            val y1 = (ph * k - y) / k - 3.0 + windowTop(w)
            val clip = doubleArrayOf(x0, y1 - ch / k, x0 + cw / k, y1)
            out += clip to m.groupValues[5].toDouble()
        }
        return out
    }

    @Test
    fun whatAPageShowsBeyondABoardEdgeIsTheNextBoardsContentAtItsStartAndCutsStayInsideEachCutArea() {
        val sep = allCut()
        val opts = options(1000.0)
        val grid = assertNotNull(plan(sep, opts).boards)
        val border = poster.borderMm
        val pdf = pages(PrintPdf.make(sep, opts))
        var straddling = 0
        for (page in pdf.filter { it.kind == "tile" }) {
            val w = assertNotNull(whereIs(interpret(page.content)))
            val wl = windowLeft(w)
            for ((clip, tx) in artBlocks(page.content, w)) {
                // Which board this block belongs to: the one whose rectangle holds it.
                val bcol = (clip[0] / bw).toInt().coerceAtMost(2)
                val brow = (clip[1] / bh).toInt().coerceAtMost(1)
                val cell = grid.cells[brow * 3 + bcol]
                val cutX0 = bcol * bw + border; val cutX1 = (bcol + 1) * bw - border
                val cutY0 = brow * bh + border; val cutY1 = (brow + 1) * bh - border
                // A tab (cut in the border, wholly before the cut area on the left or top) is not a cut-area block.
                if (clip[2] <= cutX0 + 0.01 || clip[3] <= cutY0 + 0.01) continue
                // Cuts stay inside the board's cut area.
                assertTrue(clip[0] >= cutX0 - 0.01 && clip[2] <= cutX1 + 0.01 && clip[1] >= cutY0 - 0.01 && clip[3] <= cutY1 + 0.01,
                    "art ${clip.toList()} inside board ${cell.number}'s cut area")
                // The art's translation maps a finished point f to grid x = tx/k - 3 + wl + f; for this board that must be (f - cell.x0 + border) from its left edge.
                val f = cell.cut.x0 + 10.0
                val gridX = tx / k - 3.0 + wl + f - 0.0
                // tx is in page points: page x = tx + f * k, so grid x = (tx + f * k) / k - 3 + wl.
                val local = (tx + f * k) / k - 3.0 + wl - bcol * bw
                assertEquals(border + 10.0, local, 0.02, "board ${cell.number}: a point 10 mm into the cell is ${border + 10.0} mm from the board's edge")
                assertTrue(gridX.isFinite())
            }
            if (w.boards.size > 1) straddling++
        }
        assertTrue(straddling >= 3)
    }

    @Test
    fun aPointPastBoardOnesRightEdgeOnAStraddlingPageIsBoardTwosLocalX() {
        val sep = allCut()
        val opts = options(1000.0)
        val pdf = pages(PrintPdf.make(sep, opts))
        // Find a page that carries both board 1's cut area and board 2's, in the first row of boards.
        val border = poster.borderMm
        var checked = 0
        for (page in pdf.filter { it.kind == "tile" }) {
            val w = assertNotNull(whereIs(interpret(page.content)))
            val blocks = artBlocks(page.content, w)
            val b2 = blocks.firstOrNull { (clip, _) -> clip[0] >= bw - 0.01 && clip[0] < 2 * bw && clip[1] < bh } ?: continue
            val b1 = blocks.firstOrNull { (clip, _) -> clip[2] <= bw + 0.01 && clip[1] < bh }
            if (b1 == null) continue
            // A point d past board 1's edge, on this page: grid x = bw + d, which is board 2's local x = d. The art there is cut area only past border.
            val d = border + 7.0
            val gridX = bw + d
            val finishedOnBoard2 = gridX - (bw - (457.2 - border)) // the cell-1 left edge is 457.2, its board's left edge at finished 457.2 - border
            val tx2 = b2.second
            assertEquals(gridX, (tx2 + finishedOnBoard2 * k) / k - 3.0 + windowLeft(w), 0.02)
            // The start of board 2's cut area on the page is exactly at local x = border.
            assertEquals(bw + border, b2.first[0], 0.02)
            checked++
        }
        assertTrue(checked >= 1)
    }

    @Test
    fun labelsAndTipsNeverSitOnADottedLineOrAcrossABoardEdge() {
        val sep = allCut()
        val pdf = pages(PrintPdf.make(sep, options(1000.0)))
        for (page in pdf.filter { it.kind == "tile" }) {
            val d = interpret(page.content)
            val w = assertNotNull(whereIs(d))
            for ((i, word) in d.words.withIndex()) {
                if (word.third.startsWith("cut ") || word.third == "cut here - board edge" || word.third == "TAB") continue
                val x0 = (word.first / k - 3.0) + windowLeft(w)
                val x1 = x0 + PdfText.width(word.third, d.sizes[i], d.bold[i]) / k
                for (edge in listOf(bw, 2 * bw, 3 * bw)) assertFalse(x0 < edge + 1.0 && x1 > edge - 1.0, "'${word.third}' crosses a board edge at x=$edge")
                val y0 = (ph * k - word.second) / k - 3.0 + windowTop(w)
                for (edge in listOf(bh, 2 * bh)) assertFalse(y0 - d.sizes[i] / k < edge + 1.0 && y0 > edge - 1.0, "'${word.third}' crosses a board edge at y=$edge")
            }
        }
    }

    @Test
    fun tabsNeverLieOnABoardEdgeSoNoDottedLineSplitsOne() {
        for (border in listOf(25.4, 50.8, 76.2, 101.6)) {
            val m = MountOptions(Board.POSTER_22X28, border)
            val sep = stripes()
            val g = BoardGrid.of(1000.0, 1000.0, m)
            val full = m.board.oriented(g.landscape)
            for (b in plan(sep, options(1000.0, m)).sheets.single().boards) for (t in b.tabs) {
                val local = if (t.side == TabSide.LEFT) t.rect.x0 - (b.board.cut.x0 - border) to t.rect.x1 - (b.board.cut.x0 - border)
                else t.rect.y0 - (b.board.cut.y0 - border) to t.rect.y1 - (b.board.cut.y0 - border)
                assertTrue(local.first > 0.0 && local.second < (if (t.side == TabSide.LEFT) full.widthMm else full.heightMm), "tab clear of the board's edges")
                assertTrue(local.first >= border / 2 - 1e-9, "the tab starts at least half the border from the board's edge")
            }
        }
    }

    @Test
    fun nothingIsDrawnOutsideThePrintableAreaOnAnyMountedPage() {
        for (landscape in listOf(false, true)) {
            val opts = PrintOptions(900.0, Paper.A4, landscape, poster)
            val pdf = pages(PrintPdf.make(stripes(), opts))
            val pageW = opts.page.widthMm * k
            val pageHt = opts.page.heightMm * k
            val left = 3.0 * k
            for (page in pdf) {
                val d = interpret(page.content)
                for ((x, y) in d.points) assertTrue(x >= left - 0.01 && x <= pageW - left + 0.01 && y >= left - 0.01 && y <= pageHt - left + 0.01, "$x,$y on ${page.kind}")
                for (c in d.clips) assertTrue(c[0] >= left - 0.01 && c[1] >= left - 0.01 && c[0] + c[2] <= pageW - left + 0.01 && c[1] + c[3] <= pageHt - left + 0.01)
                for ((i, w) in d.words.withIndex()) {
                    val width = PdfText.width(w.third, d.sizes[i], d.bold[i])
                    assertTrue(w.first >= left - 0.01 && w.first + width <= pageW - left + 0.01 && w.second >= left - 0.01 && w.second + d.sizes[i] <= pageHt - left + 0.01,
                        "word '${w.third}' on ${page.kind}")
                }
            }
        }
    }

    @Test
    fun theInstructionsExplainTheDottedLineOffcutsBoardsAndTheStrip() {
        val pdf = pages(PrintPdf.make(stripes(), options()))
        val text = interpret(pdf[0].content).words.joinToString(" ") { it.third }
        for (needle in listOf("GET THE BOARD", "Poster board 22 x 28 in", "railroad board", "Thin poster board tears", "6 boards", "2 in", "50.8 mm",
            "FIND YOUR PAGES", "one grid of pages", "LAY THE PAGES", "page A1's paper edges on the board's top and left edges", "THE 3 MM STRIP", "Continue each cut line",
            "CUT AT THE DOTTED LINE", "cut the paper along the dotted line", "keep the offcut", "first piece of the next board",
            "up to three other boards", "The dotted line is the only place paper is cut", "waste", "GLUE", "butt it square", "CUT", "paper and the board together",
            "Two boards edge to edge: cut on the dotted line", "A1 is the top-left corner page")) {
            assertTrue(needle in text, "instructions mention '$needle'")
        }
        for (gone in listOf("REGISTER", "target", "pierce", "fitted", "overhang")) assertFalse(gone in text, "no '$gone' in the instructions")
        assertFalse("trim" in text.lowercase(), "the instructions never ask for trimming")
    }

    @Test
    fun aBorderOfThreeInchesChangesTheCutAreaAndTheText() {
        val m = MountOptions(Board.POSTER_22X28, 76.2)
        val grid = BoardGrid.of(1000.0, 1000.0, m)
        assertEquals(16 * 25.4, grid.cutWidthMm, 1e-9)
        val pdf = pages(PrintPdf.make(stripes(), options(1000.0, m)))
        assertTrue("76.2 mm" in interpret(pdf[0].content).words.joinToString(" ") { it.third })
    }

    // ---- Sizing.

    @Test
    fun boardsWideAndHighConvertToMillimetresAndBack() {
        assertEquals(2 * 457.2, PrintSizing.widthMmForBoardsWide(2.0, poster, false), 1e-9)
        assertEquals(2.5 * 609.6, PrintSizing.widthMmForBoardsWide(2.5, poster, true), 1e-9)
        assertEquals(2.0 * 609.6 / 1.5, PrintSizing.widthMmForBoardsHigh(2.0, 1.5, poster, false), 1e-9)
        for (n in listOf(0.5, 1.0, 3.3)) {
            assertEquals(n, PrintSizing.boardsWide(PrintSizing.widthMmForBoardsWide(n, poster, false), poster, false), 1e-9)
            assertEquals(n, PrintSizing.boardsHigh(PrintSizing.widthMmForBoardsHigh(n, 1.2, poster, true), 1.2, poster, true), 1e-9)
        }
    }

    @Test
    fun mountedSnapWorksOnBoardsAndLeavesPartialPagesAlone() {
        val paper = Paper.US_LETTER.oriented(false)
        val two = PrintSizing.widthMmForBoardsWide(2.0, poster, false)
        assertFalse(PrintSizing.hasSliver(two, 0.5, paper, poster))
        assertEquals(two, PrintSizing.snapToWholePages(two, 0.5, paper, poster), 1e-9)
        val sliver = two + 10.0
        assertTrue(PrintSizing.hasSliver(sliver, 0.5, paper, poster))
        assertEquals(two, PrintSizing.snapToWholePages(sliver, 0.5, paper, poster), 1e-9)
        for (w in listOf(470.0, 930.0, 1000.0, 1380.0, 1400.0, 2000.0)) for (aspect in listOf(0.4, 0.8, 1.0, 1.3)) {
            assertFalse(PrintSizing.hasSliver(PrintSizing.snapToWholePages(w, aspect, paper, poster), aspect, paper, poster), "w=$w aspect=$aspect")
        }
        val twoPages = PrintSizing.widthMmForSheetsWide(2.0, paper)
        assertEquals(twoPages, PrintSizing.snapToWholePages(twoPages + 5.0, 0.2, paper), 1e-9)
    }
}
