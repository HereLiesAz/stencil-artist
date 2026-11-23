package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SvgExporterTest {
    private class Group(val id: String, val attrs: Map<String, String>, val rings: List<List<Point>>)

    private fun attrs(tag: String): Map<String, String> =
        Regex("([A-Za-z-]+)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun parseRings(d: String): List<List<Point>> {
        val rings = ArrayList<List<Point>>()
        var cur = ArrayList<Point>()
        val t = d.trim().split(Regex("\\s+"))
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "M", "L" -> { cur.add(Point(t[i + 1].toDouble(), t[i + 2].toDouble())); i += 3 }
                "Z" -> { rings.add(cur); cur = ArrayList(); i++ }
                else -> error("unexpected token ${t[i]}")
            }
        }
        assertTrue(cur.isEmpty(), "every subpath is closed with Z")
        return rings
    }

    private fun parse(svg: String): List<Group> {
        val groups = ArrayList<Group>()
        for (g in Regex("<g ([^>]*)>(.*?)</g>", RegexOption.DOT_MATCHES_ALL).findAll(svg)) {
            val a = attrs(g.groupValues[1])
            val rings = Regex("<path d=\"([^\"]*)\"/>").findAll(g.groupValues[2]).flatMap { parseRings(it.groupValues[1]) }.toList()
            groups.add(Group(a.getValue("id"), a, rings))
        }
        return groups
    }

    @Test
    fun theDocumentIsSizedInMillimetresWithAMatchingViewBox() {
        val svg = SvgExporter.export(sampleDrawing(), sampleSize)
        assertTrue(svg.startsWith("<?xml"))
        val root = attrs(svg.substringAfter("<svg ").substringBefore(">"))
        assertEquals("200mm", root["width"])
        assertEquals("120mm", root["height"])
        assertEquals("0 0 200 120", root["viewBox"])
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun oneGroupPerSheetInPaintOrderWithItsColourAndEvenOddFill() {
        val groups = parse(SvgExporter.export(sampleDrawing(), sampleSize))
        assertEquals(listOf("sheet-1", "sheet-2", "sheet-3"), groups.map { it.id })
        assertEquals(listOf("#cc3300", "#33aa66", "#f8f8f0"), groups.map { it.attrs["fill"] })
        assertTrue(groups.all { it.attrs["fill-rule"] == "evenodd" })
        assertEquals(null, groups[0].attrs["fill-opacity"], "opaque sheets carry no opacity")
        assertEquals("0.502", groups[1].attrs["fill-opacity"], "alpha 0x80 of 255")
    }

    @Test
    fun geometryRoundTripsAtTrueSizeWithOuterAndHolesAsSubpaths() {
        val d = sampleDrawing()
        val svg = SvgExporter.export(d, sampleSize)
        val groups = parse(svg)
        for (i in d.sheets.indices) assertRingsEqual(expectedRings(d, i, sampleSize), groups[i].rings, 0.0005, "sheet $i")
        // The first shape is one path with two subpaths (outer + hole); the island is its own path.
        val firstGroup = svg.substringAfter("id=\"sheet-1\"").substringBefore("</g>")
        val paths = Regex("<path d=\"([^\"]*)\"/>").findAll(firstGroup).map { it.groupValues[1] }.toList()
        assertEquals(2, paths.size)
        assertEquals(2, paths[0].count { it == 'M' })
        assertEquals(1, paths[1].count { it == 'M' })
        assertEquals("M 20 20 L 120 20 L 120 100 L 20 100 Z M 40 40 L 40 80 L 80 80 L 80 40 Z", paths[0])
    }

    @Test
    fun aSingleSheetKeepsItsOwnNumber() {
        val groups = parse(SvgExporter.export(sampleDrawing(), sampleSize, sheet = 2))
        assertEquals(listOf("sheet-3"), groups.map { it.id })
        assertFailsWith<IllegalArgumentException> { SvgExporter.export(sampleDrawing(), sampleSize, sheet = 3) }
    }

    @Test
    fun cutLinesAreStrokeOnlyHairlinesWithNoFill() {
        val svg = SvgExporter.export(sampleDrawing(), sampleSize, options = SvgOptions(cutLines = true))
        val groups = parse(svg)
        for (g in groups) {
            assertEquals("none", g.attrs["fill"])
            assertEquals("0.1", g.attrs["stroke-width"])
            assertNotNull(g.attrs["stroke"])
            assertEquals(null, g.attrs["fill-opacity"])
        }
        assertEquals(listOf("#000000", "#b00020", "#0b5fa5"), groups.map { it.attrs["stroke"] }, "dark table, not sheet colours")
        assertRingsEqual(expectedRings(sampleDrawing(), 0, sampleSize), groups[0].rings, 0.0005)
    }

    @Test
    fun aTileIsSizedToTheMatAndTranslatedToItsOrigin() {
        val d = sampleDrawing()
        val plan = Tiling.plan(d, sampleSize, TileSpec.custom(100.0, 140.0, 5.0, 10.0))
        assertEquals(3, plan.cols)
        val tile = plan.tile(0, 1)
        assertEquals(RectMm(80.0, 0.0, 170.0, 120.0), tile.clip)
        val svg = SvgExporter.export(d, sampleSize, tile = tile)
        val root = attrs(svg.substringAfter("<svg ").substringBefore(">"))
        assertEquals("90mm", root["width"])
        assertEquals("120mm", root["height"])
        assertEquals("0 0 90 120", root["viewBox"])
        val groups = parse(svg)
        for (g in groups) for (r in g.rings) {
            assertTrue(r.size >= 3)
            assertTrue(r.all { it.x in -1e-9..90.0 + 1e-9 && it.y in -1e-9..120.0 + 1e-9 }, "inside the mat")
        }
        // The triangle (70.5..95 px = 141..190 mm) is cut by the tile's right edge at 170 mm: x = 90 in the file.
        assertTrue(groups[1].rings.single().any { kotlin.math.abs(it.x - 90.0) < 1e-9 })
        // The thin bar (10..190 mm) spans the tile from its left edge.
        assertEquals(setOf(0.0, 90.0), groups[2].rings.single().map { it.x }.toSet().let { setOf(it.min(), it.max()) })
        // The frame (20..120 mm) ends 40 mm into the tile.
        assertEquals(40.0, groups[0].rings[0].maxOf { it.x }, 1e-9)
    }

    @Test
    fun numbersAreIdenticalAcrossTargetsBecauseNothingUsesToString() {
        val svg = SvgExporter.export(sampleDrawing(), PhysicalSize(123.456789))
        assertTrue(!svg.contains('E') && !svg.contains("e-"), "no exponent notation")
        assertTrue(Regex("\\d+\\.\\d{4,}").find(svg) == null, "at most three decimals")
    }

    @Test
    fun cutLineStrokesAreNeverWhiteOrPaleWhateverTheSheetColour() {
        val paleColours = listOf(0xFFFFFFFF.toInt(), NEAR_WHITE, 0xFFF0F0F0.toInt(), 0x00FFFFFF, 0xFFFFFFE0.toInt())
        val d = com.hereliesaz.stencilartist.core.vector.Drawing(
            100.0, 60.0,
            List(12) { i -> sampleDrawing().sheets[0].copy(color = paleColours[i % paleColours.size]) },
        )
        val groups = parse(SvgExporter.export(d, sampleSize, options = SvgOptions(cutLines = true)))
        assertEquals(12, groups.size)
        for (g in groups) {
            val hex = g.attrs.getValue("stroke")
            val (r, gr, b) = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) }
            assertTrue(0.299 * r + 0.587 * gr + 0.114 * b < 128.0, "stroke $hex is dark enough to see on white")
        }
        assertEquals(SvgOptions.STROKES.size, SvgOptions.STROKES.toSet().size, "table colours are distinct")
        assertEquals(groups[0].attrs["stroke"], groups[SvgOptions.STROKES.size].attrs["stroke"], "cycles")
    }
}
