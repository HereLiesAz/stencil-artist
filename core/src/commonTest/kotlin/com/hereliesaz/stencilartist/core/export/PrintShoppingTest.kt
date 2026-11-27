package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.ColorSeparator
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.Sheet
import com.hereliesaz.stencilartist.core.paint.Can
import com.hereliesaz.stencilartist.core.paint.HexSource
import com.hereliesaz.stencilartist.core.paint.MatchQuality
import com.hereliesaz.stencilartist.core.paint.PaintCatalogue
import com.hereliesaz.stencilartist.core.paint.PaintLayer
import com.hereliesaz.stencilartist.core.paint.ShoppingItem
import com.hereliesaz.stencilartist.core.paint.ShoppingList
import com.hereliesaz.stencilartist.core.paint.readBundledCatalogue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The paint shopping list on the instructions pages, and the accents it needs. */
class PrintShoppingTest {
    private val ink = Sheet(0xFF000000.toInt())

    private fun stripes() = Separation(300, 300, listOf(ink), IntArray(90000) { i -> if ((i % 300 / 40) % 2 == 0) 0 else Separation.NONE })

    private fun three() = Separation(
        300, 200, listOf(Sheet(0xFF202020.toInt()), Sheet(0xFFCC3333.toInt()), Sheet(0xFFEEEEEE.toInt())),
        IntArray(60000) { i -> if (i / 300 < 20) Separation.NONE else (i % 300) / 100 },
    )

    // ---- Reading a PDF.

    private class Doc(val s: String, val contents: List<String>, val mediaBoxes: List<Pair<Double, Double>>)

    private fun parse(bytes: ByteArray): Doc {
        assertTrue(bytes.all { it >= 0 }, "7-bit ASCII only")
        val s = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        assertTrue(s.startsWith("%PDF-1.4\n") && s.endsWith("%%EOF\n"))
        val startxref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref\n", startxref))
        val header = Regex("xref\n0 (\\d+)\n").find(s, startxref)!!
        val count = header.groupValues[1].toInt()
        val start = header.range.last + 1
        for (i in 1 until count) {
            val e = s.substring(start + i * 20, start + i * 20 + 20)
            assertTrue(Regex("\\d{10} 00000 n \n").matches(e))
            assertTrue(s.startsWith("$i 0 obj\n", e.substring(0, 10).toInt()), "object $i at its offset")
        }
        val contents = ArrayList<String>()
        for (m in Regex("<< /Length (\\d+) >>\nstream\n").findAll(s)) {
            val from = m.range.last + 1
            val end = s.indexOf("\nendstream", from)
            assertEquals(m.groupValues[1].toInt(), end - from, "stream length")
            contents += s.substring(from, end)
        }
        val boxes = Regex("/Type /Page /Parent \\d+ 0 R /MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").findAll(s)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertEquals(boxes.size, Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt())
        assertEquals(boxes.size, contents.size)
        return Doc(s, contents, boxes)
    }

    private class Word(val x: Double, val y: Double, val size: Double, val text: String)
    private class Swatch(val x: Double, val y: Double, val w: Double, val h: Double, val rgb: Int)

    /** The Unicode of a WinAnsi byte, for the few characters the tests use above 127. */
    private fun fromWinAnsi(b: Int): Char = when (b) { 0x92 -> '’'; 0x91 -> '‘'; 0x93 -> '“'; 0x94 -> '”'; else -> b.toChar() }

    private fun strings(c: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < c.length) {
            if (c[i] != '(') { i++; continue }
            val at = i
            val sb = StringBuilder()
            i++
            while (c[i] != ')') {
                if (c[i] == '\\') {
                    i++
                    if (c[i] in '0'..'7') { sb.append(fromWinAnsi(c.substring(i, i + 3).toInt(8))); i += 3; continue }
                }
                sb.append(c[i]); i++
            }
            i++
            out += at to sb.toString()
        }
        return out
    }

    /** Words (effective size, device position) and filled swatches of a content stream. */
    private fun read(content: String): Pair<List<Word>, List<Swatch>> {
        val words = ArrayList<Word>()
        val swatches = ArrayList<Swatch>()
        var m = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val stack = ArrayList<DoubleArray>()
        val nums = ArrayList<Double>()
        var size = 0.0
        var fill = 0
        var tx = 0.0; var ty = 0.0
        var rect: DoubleArray? = null
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (ch.isWhitespace()) { i++; continue }
            if (ch == '(') {
                val (_, str) = strings(content.substring(i)).first()
                var j = i + 1
                while (content[j] != ')') { if (content[j] == '\\') j++; j++ }
                i = j + 1
                words += Word(m[0] * tx + m[4], m[3] * ty + m[5], size * abs(m[3]), str)
                continue
            }
            val j = i
            while (i < content.length && !content[i].isWhitespace() && content[i] != '(') i++
            val t = content.substring(j, i)
            val n = t.toDoubleOrNull()
            if (n != null) { nums += n; continue }
            when (t) {
                "q" -> stack += m
                "Q" -> m = stack.removeAt(stack.size - 1)
                "cm" -> { val a = nums.takeLast(6); m = doubleArrayOf(a[0] * m[0], 0.0, 0.0, a[3] * m[3], a[4] * m[0] + m[4], a[5] * m[3] + m[5]) }
                "Tf" -> size = nums.last()
                "Td" -> { tx = nums[nums.size - 2]; ty = nums.last() }
                "rg" -> { val a = nums.takeLast(3); fill = (Math255(a[0]) shl 16) or (Math255(a[1]) shl 8) or Math255(a[2]) }
                "re" -> rect = nums.takeLast(4).toDoubleArray()
                "B" -> rect?.let {
                    val x0 = m[0] * it[0] + m[4]; val y0 = m[3] * it[1] + m[5]
                    swatches += Swatch(x0, y0, it[2] * m[0], it[3] * m[3], fill)
                }
            }
            nums.clear()
        }
        return words to swatches
    }

    @Suppress("FunctionName")
    private fun Math255(v: Double) = (v * 255.0 + 0.5).toInt()

    private fun textOf(content: String) = read(content).first.joinToString(" ") { it.text }

    private fun isInstruction(c: String) = "How to put the pages together" in c || "Paint" in c && "assembly map" !in c && "REFERENCE ONLY" !in c && "(Sheet " !in c

    // ---- Items.

    private val text = assertNotNull(readBundledCatalogue(), "models/paints.tsv not found")
    private val catalogue = PaintCatalogue.parse(text)

    /** [n] layers of the real catalogue matched to a synthetic image of [n] spread-out colours, and a separation made from them. */
    private fun realJob(n: Int): Pair<List<ShoppingItem>, Separation> {
        val w = 160
        val h = 160
        val px = IntArray(w * h) { i ->
            val k = (i % w) * n / w
            val r = (k * 97 + 31) % 256; val g = (k * 57 + 120) % 256; val b = (k * 181 + 200) % 256
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val layers = catalogue.pickFor(RgbaImage(w, h, px), n)
        assertTrue(layers.isNotEmpty())
        val sep = ColorSeparator.separate(RgbaImage(w, h, px), ColorParams(sheets = layers.size, colors = layers.map { it.color }))!!.separation
        return ShoppingList.of(layers) to sep.copy2()
    }

    private fun Separation.copy2() = this

    private fun can(brand: String, line: String, name: String, code: String?, rgb: Int, gtin: String? = null, src: HexSource = HexSource.OFFICIAL) =
        Can(brand, line, name, null, code, rgb, src, gtin)

    private fun fake(n: Int, equivalents: Int = 0, sampled: Boolean = false, quality: MatchQuality = MatchQuality.EXACT): ShoppingItem {
        val rgb = 0xFF000000.toInt() or (n * 0x050301)
        val src = if (sampled) HexSource.SAMPLED else HexSource.OFFICIAL
        val eq = (1..equivalents).map { can("Other$it", "Line$it", "Twin $it", "T$it", rgb, src = src) }
        return ShoppingItem(n, can("Brand$n", "Line $n", "Colour $n", "C-$n", rgb, "4006381333931", src), eq, 0.0, quality, sampled)
    }

    private fun fnv(b: ByteArray): String {
        var h = -0x340d631b7bdddcdbL
        for (x in b) { h = h xor (x.toLong() and 0xFF); h *= 0x100000001b3L }
        return h.toULong().toString(16)
    }

    // ---- No list: byte for byte what the exporter wrote before the list existed.

    @Test
    fun withoutAListMatchesTheVerifiedPdfGoldenAfterCuttingGuidanceUpdate() {
        // Fixed output lengths and FNV-1a hashes independently recomputed from CI job 139220855364, artifact 15023441862. All four PDFs rendered and page labels checked with independent readers.
        val golden = mapOf(
            "stripes450" to (17465 to "29e9afcef28f7fe3"),
            "three-a4-land" to (23843 to "9b08d0e363322368"),
            "mounted" to (75153 to "9a356b291d3ec2db"),
            "one-sheet-of-three" to (7982 to "6170347c1a834e81"),
        )
        val now = noListFixtures()
        assertEquals(golden, now.mapValues { (_, bytes) -> bytes.size to fnv(bytes) }, "No-list PDF goldens")
    }

    internal fun noListFixtures(): Map<String, ByteArray> = mapOf(
            "stripes450" to PrintPdf.make(stripes(), PrintOptions(450.0, shopping = emptyList())),
            "three-a4-land" to PrintPdf.make(three(), PrintOptions(500.0, Paper.A4, true)),
            "mounted" to PrintPdf.make(stripes(), PrintOptions(1000.0, Paper.US_LETTER, false, MountOptions(Board.POSTER_22X28))),
            "one-sheet-of-three" to PrintPdf.make(three(), PrintOptions(300.0), listOf(1)),
        )

    // ---- The list.

    @Test
    fun theListNamesEveryLayerInOrderWithItsCanCodeAndMatch() {
        val (items, sep) = realJob(6)
        val bytes = PrintPdf.make(sep, PrintOptions(450.0, shopping = items))
        val doc = parse(bytes)
        val plan = PrintPdf.plan(sep, PrintOptions(450.0, shopping = items))
        val instr = doc.contents.take(plan.instructionPages)
        val all = instr.joinToString(" ") { textOf(it) }
        var at = 0
        for (item in items) {
            for (needle in listOfNotNull("Layer ${item.layerNumber}", item.brand, item.name, item.code)) {
                val found = all.indexOf(needle, at)
                assertTrue(found >= at, "'$needle' after position $at for layer ${item.layerNumber}")
                at = found
            }
            val words = when (item.quality) { MatchQuality.EXACT -> "exact match"; MatchQuality.CLOSE -> "close match"; else -> "approximate match" }
            assertTrue(words in all, words)
            item.gtin?.let { assertTrue(it in all, "barcode $it") }
        }
        assertTrue("Spray in this order, base first." in all)
        assertTrue("CHECK THE COLOURS" in all && "cap" in all && "swatch card" in all)
        // The catalogue's own names may say "trim"; the instructions' words never do.
        val steps = all.substringAfter("CHECK THE COLOURS").substringBefore("Paint")
        assertFalse("trim" in steps.lowercase())
    }

    @Test
    fun swatchesAreFilledInTheCansColourWithADarkOutline() {
        val items = listOf(fake(1), fake(2).let { it.copy(can = it.can.copy(rgb = 0xFFFFFFFF.toInt())) })
        val c = PrintPdf.make(stripes(), PrintOptions(450.0, shopping = items)).let { parse(it).contents[0] }
        val (_, swatches) = read(c)
        assertEquals(items.map { it.can.rgb!! and 0xFFFFFF }, swatches.map { it.rgb }.filter { it != 0 })
        assertTrue("0.1 0.1 0.1 RG" in c)
        assertTrue(swatches.all { abs(it.w) > 5 })
    }

    @Test
    fun sampledColoursAreSaidToBeApproximateAndEquivalentsAreCappedAtThree() {
        val items = listOf(fake(1, equivalents = 5, sampled = true, quality = MatchQuality.CLOSE), fake(2, equivalents = 2), fake(3))
        val all = PrintPdf.make(stripes(), PrintOptions(450.0, shopping = items)).let { parse(it).contents }.joinToString(" ") { textOf(it) }
        assertTrue("close match (colour sampled, approximate)" in all)
        assertEquals(1, Regex("colour sampled").findAll(all).count())
        assertTrue("also: Other1 Line1 Twin 1 (T1), Other2 Line2 Twin 2 (T2), Other3 Line3 Twin 3 (T3), and 2 more" in all)
        assertFalse("Twin 4" in all)
        assertTrue("also: Other1 Line1 Twin 1 (T1), Other2 Line2 Twin 2 (T2)" in all && "and 0 more" !in all)
        assertEquals(2, Regex("also:").findAll(all).count())
    }

    @Test
    fun userEnteredCanColourIsIdentifiedAsApproximateInThePdf() {
        val userCan = can("MTN", "94", "Custom Red", "R-1", 0xFFAA2233.toInt(), src = HexSource.USER)
        val item = ShoppingList.of(listOf(PaintLayer(0xFFAA2233.toInt(), listOf(userCan)))).single()
        assertFalse(item.sampled)
        assertTrue(item.userColour)
        val words = PrintPdf.make(stripes(), PrintOptions(450.0, shopping = listOf(item)))
            .let { parse(it).contents }.joinToString(" ") { textOf(it) }
        assertTrue("user-entered colour, approximate" in words)
        assertFalse("colour sampled" in words)
        assertTrue("Custom Red" in words)
    }

    @Test
    fun aLongListContinuesOnExtraPagesWithLegibleTextInsideThePrintableArea() {
        val items = (1..40).map { fake(it, equivalents = if (it % 5 == 0) 4 else 0, sampled = it % 7 == 0) }
        for ((paper, landscape) in listOf(Paper.US_LETTER to false, Paper.A4 to true, Paper.A3 to false, Paper("Narrow", 130.0, 400.0) to false)) {
            val opts = PrintOptions(450.0, paper, landscape, shopping = items)
            val sep = stripes()
            val plan = PrintPdf.plan(sep, opts)
            assertTrue(plan.instructionPages >= 2, "${paper.name}: 40 layers need more than one page, got ${plan.instructionPages}")
            val doc = parse(PrintPdf.make(sep, opts))
            val sheetPages = 1 + plan.printedPages // a map and the tiles (no reference pages for stripes)
            assertEquals(plan.instructionPages + sheetPages, doc.contents.size, paper.name)
            val k = 72.0 / 25.4
            val page = opts.page
            val left = 3.0 * k; val right = (page.widthMm - 3.0) * k
            val bottom = 3.0 * k; val top = (page.heightMm - 3.0) * k
            var seen = ""
            for ((i, c) in doc.contents.take(plan.instructionPages).withIndex()) {
                val (words, _) = read(c)
                if (i > 0) assertTrue(words.first().text.startsWith("Paint"), "page ${i + 1} is a paint page")
                for (w in words) {
                    assertTrue(w.x >= left - 0.01 && w.y >= bottom && w.y <= top, "${paper.name} p${i + 1}: '${w.text}' at ${w.x}, ${w.y}")
                    assertTrue(w.x + PdfText.width(w.text, w.size) <= right + 0.5, "${paper.name} p${i + 1}: '${w.text}' fits the width")
                    if (w.y > 0 && i > 0 || w.text.startsWith("Layer") || w.text.startsWith("also") || w.text.startsWith("code")) {
                        assertTrue(w.size >= 7.0 - 1e-6, "${paper.name}: '${w.text}' is ${w.size} pt")
                    }
                }
                seen += textOf(c) + " "
            }
            for (n in 1..40) assertTrue("Layer $n " in seen || seen.contains("Layer $n"), "${paper.name} has layer $n")
            assertTrue("Paint, continued" in seen)
        }
    }

    @Test
    fun aShortListSharesPageOneAndAddsNoPage() {
        val plan = PrintPdf.plan(stripes(), PrintOptions(450.0, shopping = (1..3).map { fake(it) }))
        assertEquals(1, plan.instructionPages)
        assertEquals(1, PrintPdf.plan(stripes(), PrintOptions(450.0)).instructionPages)
    }

    @Test
    fun realLayersOfFortyCansProduceAValidFile() {
        val (items, sep) = realJob(40)
        assertTrue(items.size >= 30, "got ${items.size} layers")
        val opts = PrintOptions(300.0, shopping = items)
        val plan = PrintPdf.plan(sep, opts)
        val doc = parse(PrintPdf.make(sep, opts))
        assertTrue(plan.instructionPages >= 2)
        assertTrue(doc.contents.size >= plan.instructionPages + sep.sheets.size + plan.printedPages)
        for (c in doc.contents.take(plan.instructionPages)) assertTrue(isInstruction(c))
    }

    // ---- Accents.

    @Test
    fun latin1AccentsAndTypographicMarksAreWinAnsiOctalEscapesAndOtherLettersFold() {
        assertEquals("Frapp\\351", PdfText.escape("Frappé"))
        assertEquals("Atom\\264s", PdfText.escape("Atom´s"))
        assertEquals("Hooker\\222s", PdfText.escape("Hooker’s"))
        assertEquals("Ni\\361o \\374ber", PdfText.escape("Niño über"))
        assertEquals("L\\363dz \\(x\\)", PdfText.escape("\u0141\u00f3d\u017a (x)"))
        assertEquals("?", PdfText.escape("П"))
        assertEquals("c", PdfText.escape("č"))
        assertTrue(PdfText.width("é", 10.0) == PdfText.width("e", 10.0))
    }

    @Test
    fun realCatalogueNamesWithAccentsSurviveIntoThePdfText() {
        val odd = catalogue.cans.filter { c -> c.brand.any { it.code > 126 } || c.line.any { it.code > 126 } || c.name.any { it.code > 126 } }
        assertTrue(odd.size >= 3, "the file has accented names: ${odd.map { it.name }}")
        val items = odd.mapIndexed { i, c -> ShoppingItem(i + 1, c, emptyList(), 0.0, MatchQuality.EXACT, false) }
        val doc = parse(PrintPdf.make(stripes(), PrintOptions(450.0, shopping = items)))
        val all = doc.contents.joinToString(" ") { textOf(it) }
        for (c in odd) assertTrue(c.name in all, "'${c.name}' appears as written")
        assertTrue(odd.any { "é" in it.name } && odd.any { "’" in it.name })
        assertFalse(doc.s.contains("?" + "?"), "no unknown-character fallbacks")
    }
}
