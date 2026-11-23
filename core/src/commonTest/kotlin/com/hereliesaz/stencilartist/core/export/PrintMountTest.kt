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

    /** A tile page's board number, tile column and row (0-based), from its label. */
    private class Where(val board: Int, val col: Int, val row: Int)

    private fun whereIs(d: Drawn): Where? {
        val label = d.words.map { it.third }.firstOrNull { Regex("Sheet \\d+ of \\d+ - Board \\d+ of \\d+ - [A-Z]+\\d+").matches(it) } ?: return null
        val m = Regex("Board (\\d+) of \\d+ - ([A-Z]+)(\\d+)").find(label)!!
        return Where(m.groupValues[1].toInt(), m.groupValues[2][0] - 'A', m.groupValues[3].toInt() - 1)
    }

    private val step = 209.9 - 1.0
    private val stepY = 273.4 - 1.0

    /** Page points to finished-piece mm for a page of [w] at region origin ([ox], [oy]) and tile ([col], [row]). */
    private fun finished(p: Pair<Double, Double>, pageH: Double, ox: Double, oy: Double, w: Where) =
        ((p.first / k - 3.0) + ox + w.col * step) to (((pageH - p.second) / k - 3.0) + oy + w.row * stepY)

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

    // ---- Plan.

    @Test
    fun thePlanReportsBoardsOrientationBlankBoardsAndPages() {
        // Cut only in the left 30% of a 1 m piece: with portrait boards (457.2 mm) only board column 0 has cut.
        val s = stencil(400, 400) { x, _ -> x < 120 }
        val plan = PrintPdf.plan(s, options())
        val grid = assertNotNull(plan.boards)
        assertEquals(3, grid.cols); assertEquals(2, grid.rows)
        assertFalse(grid.landscape)
        assertEquals(6, plan.boardsPerSheet)
        assertEquals(4, plan.blankBoards)
        assertEquals(2, plan.printedBoards)
        val boards = plan.sheets.single().boards
        assertEquals(listOf(false, true, true, false, true, true), boards.map { it.blank })
        assertTrue(boards.filter { !it.blank }.all { it.printed.isNotEmpty() })
        assertEquals(plan.printedPages, boards.sumOf { it.printed.size })
    }

    // ---- The file.

    @Test
    fun everyBoardPageIsLabelledWithItsBoardAndTheMapShowsBoardsPagesAndBlanks() {
        val s = stencil(400, 400) { x, _ -> x < 120 && (x / 24) % 2 == 0 }
        val pdf = pages(PrintPdf.make(s, options()))
        val plan = PrintPdf.plan(s, options())
        assertEquals("instructions", pdf[0].kind)
        assertEquals(1, pdf.count { it.kind == "instructions" })
        assertEquals(1, pdf.count { it.kind == "map" })
        val tiles = pdf.filter { it.kind == "tile" }
        assertEquals(plan.printedPages, tiles.size)
        assertEquals(1 + 1 + tiles.size + pdf.count { it.kind == "reference" }, pdf.size)
        val labels = tiles.map { interpret(it.content).words.map { w -> w.third }.first { w -> w.startsWith("Sheet ") } }
        assertTrue(labels.all { Regex("Sheet 1 of 1 - Board [14] of 6 - [A-Z]\\d").matches(it) }, labels.toString())
        val map = interpret(pdf[1].content).words.map { it.third }
        assertTrue("Board 1" in map && "Board 4" in map, map.toString())
        assertTrue("Board 2 blank" in map && "Board 3 blank" in map && "Board 5 blank" in map && "Board 6 blank" in map, map.toString())
        assertTrue(map.any { "Boards: 6 (3 across x 2 down, portrait)" in it })
    }

    @Test
    fun noPagePrintsAcrossABoardEdgeAndMarksOnlyJoinPagesOfTheSameBoard() {
        val opts = options()
        val sep = stripes()
        val grid = assertNotNull(PrintPdf.plan(sep, opts).boards)
        val pdf = pages(PrintPdf.make(sep, opts))
        val pageH = 279.4 * k
        var checked = 0
        for (page in pdf.filter { it.kind == "tile" }) {
            val d = interpret(page.content)
            val w = assertNotNull(whereIs(d))
            val cell = grid.cells[w.board - 1].cut
            val ox = cell.x0 - poster.borderMm + 3.0
            val oy = cell.y0 - poster.borderMm + 3.0
            // The printed art is clipped to this board's cut area (and to the page): the first clip rectangle.
            val clip = d.clips.first()
            val a = finished(clip[0] to clip[1] + clip[3], pageH, ox, oy, w)
            val b = finished(clip[0] + clip[2] to clip[1], pageH, ox, oy, w)
            if (clip[2] > 0.0 && clip[3] > 0.0) assertTrue(a.first >= cell.x0 - 0.01 && a.second >= cell.y0 - 0.01 && b.first <= cell.x1 + 0.01 && b.second <= cell.y1 + 0.01,
                "page ${w.board}/${w.col},${w.row} prints $a-$b inside cell $cell")
            // Its marks all lie within its own board's tile grid (A1's corner to the cut area's far side), so a band never straddles two boards.
            for (poly in d.strokes) if (poly.size > 1) for (p in poly) {
                val f = finished(p, pageH, ox, oy, w)
                // (The dashed trim line along the board's far edges is not a mark.)
                val trim = abs(f.first - (cell.x1 + poster.borderMm)) < 0.01 || abs(f.second - (cell.y1 + poster.borderMm)) < 0.01
                if (trim) continue
                assertTrue(f.first >= ox - 0.01 && f.first <= cell.x1 + 0.01 && f.second >= oy - 0.01 && f.second <= cell.y1 + 0.01,
                    "mark point $f of board ${w.board} is outside its region")
            }
            checked++
        }
        assertTrue(checked > 6)
        // Board cells partition the plane: no two overlap in area.
        for (a in grid.cells) for (b in grid.cells) if (a.number < b.number) {
            val ox = min(a.cut.x1, b.cut.x1) - maxOf(a.cut.x0, b.cut.x0)
            val oy = min(a.cut.y1, b.cut.y1) - maxOf(a.cut.y0, b.cut.y0)
            assertFalse(ox > 1e-9 && oy > 1e-9, "boards ${a.number} and ${b.number} overlap")
        }
    }

    /** A fully cut piece, 400 mm at 4 mm a pixel, so every board's A1 page has art on it. */
    private fun allCut() = stencil(100, 100) { _, _ -> true }

    @Test
    fun pageA1OfEveryBoardHasItsPaperOnTheBoardCornerAndTheCutStartsTheBorderIn() {
        // 1 m piece: 6 boards. On each board's A1 page the cut area begins exactly the border from the paper's left and top edges.
        val sep = stencil(100, 100) { _, _ -> true }
        val opts = options(1000.0)
        val grid = assertNotNull(PrintPdf.plan(sep, opts).boards)
        val pdf = pages(PrintPdf.make(sep, opts))
        val pageH = 279.4 * k
        val a1 = pdf.filter { it.kind == "tile" }.filter { p -> whereIs(interpret(p.content)).let { it != null && it.col == 0 && it.row == 0 } }
        assertEquals(grid.count, a1.size, "every board has its A1 page")
        for (page in a1) {
            val clip = interpret(page.content).clips.first()
            assertEquals(poster.borderMm * k, clip[0], 0.02, "cut area starts the border in from the paper's left edge")
            assertEquals(pageH - poster.borderMm * k, clip[1] + clip[3], 0.02, "and from the paper's top edge")
        }
    }

    @Test
    fun everyCutPixelOfABoardIsAtLeastTheBorderFromAllFourBoardEdgesAndNothingIsDrawnPastTheBoard() {
        val opts = options(1000.0)
        val sep = stencil(100, 100) { _, _ -> true }
        val grid = assertNotNull(PrintPdf.plan(sep, opts).boards)
        val pdf = pages(PrintPdf.make(sep, opts))
        val pageH = 279.4 * k
        val board = poster.board
        for (page in pdf.filter { it.kind == "tile" }) {
            val d = interpret(page.content)
            val w = assertNotNull(whereIs(d))
            val clip = d.clips.first()
            if (clip[2] <= 0.0 || clip[3] <= 0.0) continue
            // Board coordinates: x across from the board's left edge, y down from its top edge. Page (col, row)'s paper starts at col * step.
            val left = w.col * step + clip[0] / k
            val right = w.col * step + (clip[0] + clip[2]) / k
            val topY = w.row * stepY + (pageH - clip[1] - clip[3]) / k
            val bottomY = w.row * stepY + (pageH - clip[1]) / k
            assertTrue(left >= poster.borderMm - 0.02 && topY >= poster.borderMm - 0.02, "board ${w.board} art starts the border in")
            assertTrue(right <= board.widthMm - poster.borderMm + 0.02 && bottomY <= board.heightMm - poster.borderMm + 0.02, "board ${w.board} art ends the border before the far edges")
        }
        assertTrue(grid.count > 1)
    }

    @Test
    fun aPageThatRunsPastTheBoardEdgeIsTrimmedAlongADashedLineAndPrintsNothingBeyondIt() {
        // Poster board 558.8 wide: Letter pages start 0, 208.9, 417.8 (paper to 633.7), so the third column overhangs by 74.9 mm.
        val sep = allCut()
        val opts = options(400.0)
        val pdf = pages(PrintPdf.make(sep, opts))
        val pageH = 279.4 * k
        val over = pdf.filter { it.kind == "tile" }.filter { p -> whereIs(interpret(p.content))?.col == 2 }
        assertTrue(over.isNotEmpty())
        for (page in over) {
            val d = interpret(page.content)
            val w = assertNotNull(whereIs(d))
            assertTrue("[3 2] 0 d" in page.content, "dashed board-edge trim line")
            assertTrue("(TRIM TO BOARD EDGE) Tj" in page.content)
            // Everything drawn outside the clip lies on the board side of the edge (the line itself is on it).
            val edge = poster.board.widthMm - 2 * step.let { 0.0 } // board width
            for (poly in d.strokes) for (p in poly) {
                val boardX = w.col * step + p.first / k
                assertTrue(boardX <= edge + 0.01, "stroke point at board x=$boardX is past the board edge $edge")
            }
            val clip = d.clips.first()
            if (clip[2] > 0) assertTrue(w.col * step + (clip[0] + clip[2]) / k <= edge - poster.borderMm + 0.02)
        }
        assertTrue(pageH > 0)
    }

    @Test
    fun theCornerPageIsPrintedWheneverABoardHasAnyPageEvenWithNothingToCutOnIt() {
        // Cut only in the far corner of the board: x 380..399 mm of a 400 mm piece lies in A1's... use a corner far from A1.
        val sep = stencil(100, 100) { x, y -> x >= 80 && y >= 80 }
        val opts = options(400.0)
        val plan = PrintPdf.plan(sep, opts)
        val board = plan.sheets.single().boards.single()
        assertFalse(board.blank)
        assertTrue(board.printed.any { it.col == 0 && it.row == 0 }, "A1 is printed")
        assertTrue(board.skipped.isNotEmpty(), "other pages with nothing to cut are still skipped")
        val pdf = pages(PrintPdf.make(sep, opts))
        val labels = pdf.filter { it.kind == "tile" }.mapNotNull { whereIs(interpret(it.content)) }
        assertTrue(labels.any { it.col == 0 && it.row == 0 })
        assertEquals(board.printed.size, labels.size)
        // An empty board prints no A1 at all.
        val none = PrintPdf.plan(stencil(100, 100) { _, _ -> false }, opts).sheets.single().boards.single()
        assertTrue(none.blank)
        assertTrue(none.printed.isEmpty())
    }

    @Test
    fun noCornerTargetsAreDrawn() {
        val pdf = pages(PrintPdf.make(stripes(), options()))
        assertFalse(pdf.any { "TARGET" in it.content })
        val text = pdf.flatMap { interpret(it.content).words }.joinToString(" ") { it.third }
        assertFalse("pin" in text.lowercase().split(" ", "."), text)
    }

    @Test
    fun nothingIsDrawnOutsideThePrintableAreaOnAnyMountedPage() {
        for (landscape in listOf(false, true)) {
            val opts = PrintOptions(900.0, Paper.A4, landscape, poster)
            val pdf = pages(PrintPdf.make(stripes(), opts))
            val pw = opts.page.widthMm * k
            val ph = opts.page.heightMm * k
            val left = 3.0 * k
            for (page in pdf) {
                val d = interpret(page.content)
                for ((x, y) in d.points) assertTrue(x >= left - 0.01 && x <= pw - left + 0.01 && y >= left - 0.01 && y <= ph - left + 0.01, "$x,$y on ${page.kind}")
                for (c in d.clips) assertTrue(c[0] >= left - 0.01 && c[1] >= left - 0.01 && c[0] + c[2] <= pw - left + 0.01 && c[1] + c[3] <= ph - left + 0.01)
                for ((i, w) in d.words.withIndex()) {
                    val width = PdfText.width(w.third, d.sizes[i], d.bold[i])
                    assertTrue(w.first >= left - 0.01 && w.first + width <= pw - left + 0.01 && w.second >= left - 0.01 && w.second + d.sizes[i] <= ph - left + 0.01,
                        "word '${w.third}' on ${page.kind}")
                }
            }
        }
    }

    @Test
    fun theInstructionsExplainGluingTargetsBoardsAndWallRegistration() {
        val pdf = pages(PrintPdf.make(stripes(), options()))
        val text = interpret(pdf[0].content).words.joinToString(" ") { it.third }
        for (needle in listOf("GET THE BOARD", "Poster board 22 x 28 in", "railroad board", "Thin poster board tears", "6 boards", "2 in", "50.8 mm",
            "GLUE", "page A1's top and left paper edges flush with the board's", "CUT", "paper and the board together",
            "One board: page A1, unprinted strip, border, cut area", "TRIM", "ALIGN", "A1 is the board's top-left corner page")) {
            assertTrue(needle in text, "instructions mention '$needle'")
        }
        for (gone in listOf("REGISTER", "target", "pierce")) assertFalse(gone in text, "no '$gone' in the instructions")
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
    fun mountedSnapWorksOnBoardsAndLeavesPartialPagesInsideABoardAlone() {
        val paper = Paper.US_LETTER.oriented(false)
        val two = PrintSizing.widthMmForBoardsWide(2.0, poster, false)
        // Exactly two boards across: no sliver, even though the pages inside a board end in a partial page.
        assertFalse(PrintSizing.hasSliver(two, 0.5, paper, poster))
        assertEquals(two, PrintSizing.snapToWholePages(two, 0.5, paper, poster), 1e-9)
        // A hair over two boards: a sliver board column, snapped to exactly two.
        val sliver = two + 10.0
        assertTrue(PrintSizing.hasSliver(sliver, 0.5, paper, poster))
        assertEquals(two, PrintSizing.snapToWholePages(sliver, 0.5, paper, poster), 1e-9)
        // A row sliver is fixed too, and the result never ends in a sliver.
        for (w in listOf(470.0, 930.0, 1000.0, 1380.0, 1400.0, 2000.0)) for (aspect in listOf(0.4, 0.8, 1.0, 1.3)) {
            assertFalse(PrintSizing.hasSliver(PrintSizing.snapToWholePages(w, aspect, paper, poster), aspect, paper, poster), "w=$w aspect=$aspect")
        }
        // The paper-only snap is untouched by mounting being absent.
        val letter = paper
        val twoPages = PrintSizing.widthMmForSheetsWide(2.0, letter)
        assertEquals(twoPages, PrintSizing.snapToWholePages(twoPages + 5.0, 0.2, letter), 1e-9)
    }
}
