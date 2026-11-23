package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfExporterTest {
    private val pt = 72.0 / 25.4

    private fun str(b: ByteArray) = b.map { (it.toInt() and 0xFF).toChar() }.joinToString("")

    private class Pdf(val s: String) {
        val offsets = ArrayList<Int>()
        val pages = ArrayList<Pair<Double, Double>>()
        val contents = ArrayList<String>()
    }

    /** Parses and validates the structure: header, trailer, xref offsets, stream lengths, page boxes. */
    private fun parse(bytes: ByteArray): Pdf {
        val s = str(bytes)
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
            val off = e.substring(0, 10).toInt()
            pdf.offsets.add(off)
            assertTrue(s.startsWith("$i 0 obj\n", off), "object $i at offset $off")
            assertTrue(s.indexOf("endobj", off) > off)
        }
        assertTrue(Regex("trailer\n<< /Size $count /Root 1 0 R").containsMatchIn(s))
        assertTrue(s.startsWith("1 0 obj\n<< /Type /Catalog", pdf.offsets[0]))
        // Streams: /Length is the real length of the data.
        for (m in Regex("<< /Length (\\d+) >>\nstream\n").findAll(s)) {
            val len = m.groupValues[1].toInt()
            val from = m.range.last + 1
            val end = s.indexOf("\nendstream", from)
            assertEquals(len, end - from, "stream length")
            pdf.contents.add(s.substring(from, end))
        }
        for (m in Regex("/Type /Page /Parent \\d+ 0 R /MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").findAll(s)) {
            pdf.pages.add(m.groupValues[1].toDouble() to m.groupValues[2].toDouble())
        }
        assertEquals(pdf.pages.size, Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt())
        assertEquals(pdf.pages.size, pdf.contents.size)
        return pdf
    }

    @Test
    fun aSinglePageIsExactlyTheSizeOfTheArtwork() {
        val pdf = parse(PdfExporter.single(sampleDrawing(), sampleSize))
        assertEquals(1, pdf.pages.size)
        assertTrue(abs(pdf.pages[0].first - 200 * pt) < 0.001, "${pdf.pages[0].first}")
        assertTrue(abs(pdf.pages[0].second - 120 * pt) < 0.001)
    }

    @Test
    fun colourAlphaAndSheetOrderReachThePage() {
        val d = sampleDrawing()
        val pdf = parse(PdfExporter.single(d, sampleSize))
        val c = pdf.contents[0]
        assertEquals(4, Regex("(?m)^f\\*$").findAll(c).count(), "one fill per shape")
        val colours = Regex("(?m)^([0-9.]+) ([0-9.]+) ([0-9.]+) rg$").findAll(c).map { m -> m.groupValues.drop(1).map { it.toDouble() } }.toList()
        assertEquals(3, colours.size)
        assertEquals(listOf(0.8, 0.2, 0.0), colours[0].map { Decimal.format(it, 1).toDouble() })
        assertTrue(abs(colours[1][0] - 0x33 / 255.0) < 1e-4 && abs(colours[1][2] - 0x66 / 255.0) < 1e-4)
        assertTrue(abs(colours[2][0] - 0xF8 / 255.0) < 1e-4)
        assertTrue(pdf.s.contains("/GA128 "), "a graphics state for the translucent sheet")
        assertTrue(pdf.s.contains("/ca 0.502 /CA 0.502"))
        assertEquals(1, Regex("(?m)^/GA128 gs$").findAll(c).count(), "only the translucent sheet uses it")
        assertTrue(c.indexOf("0.8 0.2 0 rg") < c.indexOf("0.2 0.6667 0.4 rg"), "paint order")
    }

    @Test
    fun singleCanWriteOnlySomeSheets() {
        val pdf = parse(PdfExporter.single(sampleDrawing(), sampleSize, listOf(1)))
        assertEquals(1, Regex("(?m)^f\\*$").findAll(pdf.contents[0]).count())
        assertFailsWith<IllegalArgumentException> { PdfExporter.single(sampleDrawing(), sampleSize, listOf(5)) }
        assertFailsWith<IllegalArgumentException> { PdfExporter.single(sampleDrawing(), sampleSize, emptyList()) }
    }

    @Test
    fun pathCoordinatesAreInPointsWithYUp() {
        val d = Drawing(
            100.0, 50.0,
            listOf(com.hereliesaz.stencilartist.core.vector.SheetOutline(0xFF102030.toInt(), listOf(com.hereliesaz.stencilartist.core.vector.Shape(
                com.hereliesaz.stencilartist.core.vector.Ring(listOf(
                    com.hereliesaz.stencilartist.core.vector.Point(0.0, 0.0), com.hereliesaz.stencilartist.core.vector.Point(100.0, 0.0),
                    com.hereliesaz.stencilartist.core.vector.Point(100.0, 50.0)), false)))))
        )
        val c = parse(PdfExporter.single(d, PhysicalSize(254.0))).contents[0]
        // 254 mm is 10 in = 720 pt wide and 360 pt high; the top-left pixel corner is at the top of the page.
        assertTrue(c.contains("0 360 m\n720 360 l\n720 0 l\nh\n"), c)
    }

    @Test
    fun theTiledPageCountIsOneMapPlusOnePagePerTile() {
        // 400 x 300 px at 500 mm wide: 500 x 375 mm, the A4 case from TilingTest: 3 x 2 tiles.
        val d = Drawing(400.0, 300.0, sampleDrawing().sheets.map { s -> s })
        val pdf = parse(PdfExporter.tiled(d, PhysicalSize(500.0), 1, TileSpec.A4))
        assertEquals(1 + 3 * 2, pdf.pages.size)
        for (p in pdf.pages) {
            assertTrue(abs(p.first - 210 * pt) < 0.001 && abs(p.second - 297 * pt) < 0.001, "A4 in points: $p")
        }
    }

    @Test
    fun theAssemblyMapStatesSizeTileCountAndLabelsEveryTile() {
        val d = Drawing(400.0, 300.0, sampleDrawing().sheets)
        val c = parse(PdfExporter.tiled(d, PhysicalSize(500.0), 1, TileSpec.A4)).contents[0]
        assertTrue(c.contains("(Sheet 2 of 3 - assembly map) Tj"))
        assertTrue(c.contains("(Finished size: 500 x 375 mm \\(19.69 x 14.76 in\\)) Tj"), c)
        assertTrue(c.contains("(Tiles: 3 across x 2 down = 6 pages after this map) Tj"))
        assertTrue(c.contains("(Page: A4 210 x 297 mm, margin 10 mm, overlap 10 mm) Tj"))
        for (r in 1..2) for (col in 1..3) assertTrue(c.contains("(R$r C$col) Tj"), "label R$r C$col")
        assertEquals(1, Regex("(?m)^f\\*$").findAll(c).count(), "the whole sheet, small: one shape")
    }

    @Test
    fun everyTilePageIsClippedMarkedAndLabelled() {
        val d = Drawing(400.0, 300.0, sampleDrawing().sheets)
        val pdf = parse(PdfExporter.tiled(d, PhysicalSize(500.0), 1, TileSpec.A4))
        val plan = Tiling.plan(d, PhysicalSize(500.0), TileSpec.A4)
        var page = 1
        for (t in plan.tiles) {
            val c = pdf.contents[page++]
            assertTrue(c.contains("(Sheet 2 of 3 - Row ${t.row + 1} Col ${t.col + 1}) Tj"), "label for ${t.row},${t.col}")
            assertEquals(1, Regex("(?m) re W n$").findAll(c).count(), "one clip path")
            assertTrue(c.contains("[3 2] 0 d"), "dashed overlap line")
            // Four crosshairs, two strokes each, plus up to four dashed lines (those with a neighbour).
            val neighbours = (if (t.col > 0) 1 else 0) + (if (t.col < 2) 1 else 0) + (if (t.row > 0) 1 else 0) + (if (t.row < 1) 1 else 0)
            assertEquals(8 + neighbours, Regex("(?m) l S$").findAll(c).count(), "crosshair arms and dashed lines for tile ${t.row},${t.col}")
        }
    }

    @Test
    fun theClipPathIsTheTilesPrintableRectangleOnThePage() {
        val d = Drawing(400.0, 300.0, sampleDrawing().sheets)
        val pdf = parse(PdfExporter.tiled(d, PhysicalSize(500.0), 1, TileSpec.A4))
        // Tile (0, 0): 190 x 277 mm clipped at the top-left margin (10, 10). In points, y up from the page bottom.
        val c = pdf.contents[1]
        val m = Regex("(?m)^([0-9.]+) ([0-9.]+) ([0-9.]+) ([0-9.]+) re W n$").find(c)!!.groupValues.drop(1).map { it.toDouble() }
        assertTrue(abs(m[0] - 10 * pt) < 0.001)
        assertTrue(abs(m[1] - (297 - 10 - 277) * pt) < 0.001)
        assertTrue(abs(m[2] - 190 * pt) < 0.001)
        assertTrue(abs(m[3] - 277 * pt) < 0.001)
    }

    @Test
    fun aNearWhiteSheetIsFilledAndOutlinedInDarkInk() {
        val d = Drawing(100.0, 60.0, sampleDrawing().sheets)
        val c = parse(PdfExporter.tiled(d, sampleSize, 2, TileSpec.A4)).contents[1]
        assertTrue(c.contains("0.9725 0.9725 0.9412 rg"))
        assertTrue(c.contains("0.1 0.1 0.1 RG"))
        assertEquals(Regex("(?m)^f\\*$").findAll(c).count(), Regex("(?m)^S$").findAll(c).count() - 0, "every filled shape is also stroked")
    }

    @Test
    fun aDrawingThatFitsIsOneMapAndOneTile() {
        val pdf = parse(PdfExporter.tiled(sampleDrawing(), PhysicalSize(150.0), 0, TileSpec.A4))
        assertEquals(2, pdf.pages.size)
        assertEquals(8, Regex("(?m) l S$").findAll(pdf.contents[1]).count(), "four crosshairs, no overlap lines")
        assertTrue(pdf.contents[1].contains("(Sheet 1 of 3 - Row 1 Col 1) Tj"))
        assertTrue(pdf.contents[0].contains("(Tiles: 1 across x 1 down = 1 page after this map) Tj"))
    }

    @Test
    fun aRollGivesFullWidthStripsWhosePagesAreAsLongAsTheDrawing() {
        val d = sampleDrawing()
        val pdf = parse(PdfExporter.tiled(d, PhysicalSize(1500.0), 0, TileSpec.roll(600.0)))
        // 1500 x 900 mm on a 600 mm roll: three strips, each 900 mm plus 2 x 10 mm margins long.
        assertEquals(1 + 3, pdf.pages.size)
        for (p in pdf.pages.drop(1)) {
            assertTrue(abs(p.first - 600 * pt) < 0.001)
            assertTrue(abs(p.second - 920 * pt) < 0.001, "${p.second / pt} mm")
        }
        assertTrue(pdf.contents[0].contains("roll 600 mm wide"))
    }

    @Test
    fun textIsEscapedAndNeverBreaksTheContentStream() {
        val pdf = parse(PdfExporter.tiled(sampleDrawing(), sampleSize, 0, TileSpec.custom(300.0, 300.0).copy(name = "Odd (name) \\ é")))
        assertTrue(pdf.contents[0].contains("Odd \\(name\\) \\\\ ?"))
    }

    @Test
    fun badInputsAreRefused() {
        assertFailsWith<IllegalArgumentException> { PdfExporter.tiled(sampleDrawing(), sampleSize, 3, TileSpec.A4) }
        assertFailsWith<IllegalArgumentException> { PdfExporter.tiled(sampleDrawing(), PhysicalSize(100000.0), 0, TileSpec.custom(30.0, 30.0, 1.0, 0.5)) }
        assertFailsWith<IllegalArgumentException> { PdfExporter.tiled(sampleDrawing(), PhysicalSize(3000.0), 0, TileSpec.custom(30.0, 30.0, 1.0, 0.5)) }
    }
}
