package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfExporterTest {
    private class Poly(val layer: String, val closed: Boolean, val vertices: List<Point>, val sawSeqend: Boolean)

    private fun pairs(dxf: String): List<Pair<Int, String>> {
        val lines = dxf.split("\n")
        assertEquals("", lines.last(), "ends with a newline")
        val body = lines.dropLast(1)
        assertEquals(0, body.size % 2)
        return body.chunked(2).map { it[0].trim().toInt() to it[1].trim() }
    }

    private fun sections(p: List<Pair<Int, String>>): List<String> {
        val names = ArrayList<String>()
        for (i in p.indices) if (p[i] == 0 to "SECTION") names.add(p[i + 1].second)
        return names
    }

    private fun parsePolylines(p: List<Pair<Int, String>>): List<Poly> {
        val out = ArrayList<Poly>()
        var i = p.indexOf(0 to "SECTION" ).let { _ -> p.indexOfFirst { it == 2 to "ENTITIES" } }
        while (i < p.size) {
            if (p[i] == 0 to "POLYLINE") {
                var layer = ""
                var flag = -1
                var j = i + 1
                while (p[j].first != 0) {
                    if (p[j].first == 8) layer = p[j].second
                    if (p[j].first == 70) flag = p[j].second.toInt()
                    j++
                }
                val verts = ArrayList<Point>()
                while (p[j] == 0 to "VERTEX") {
                    var x = 0.0
                    var y = 0.0
                    var vl = ""
                    j++
                    while (p[j].first != 0) {
                        when (p[j].first) { 8 -> vl = p[j].second; 10 -> x = p[j].second.toDouble(); 20 -> y = p[j].second.toDouble() }
                        j++
                    }
                    assertEquals(layer, vl, "vertex on its polyline's layer")
                    verts.add(Point(x, y))
                }
                out.add(Poly(layer, flag == 1, verts, p[j] == 0 to "SEQEND"))
                i = j
            }
            i++
        }
        return out
    }

    private fun flipped(rings: List<List<Point>>, height: Double) = rings.map { r -> r.map { Point(it.x, height - it.y) } }

    @Test
    fun sectionsAreInOrderAndTheFileEndsWithEof() {
        val dxf = DxfExporter.export(sampleDrawing(), sampleSize)
        val p = pairs(dxf)
        assertEquals(listOf("HEADER", "TABLES", "ENTITIES"), sections(p))
        assertEquals(0 to "EOF", p.last())
        assertEquals(0 to "ENDSEC", p[p.size - 2])
        assertEquals(p.count { it == 0 to "SECTION" }, p.count { it == 0 to "ENDSEC" })
        assertEquals(2, p.count { it == 0 to "TABLE" })
        assertEquals(2, p.count { it == 0 to "ENDTAB" })
        val version = p.indexOf(9 to "\$ACADVER")
        assertEquals(1 to "AC1009", p[version + 1], "release 12")
        assertTrue(p.none { it.second == "LWPOLYLINE" || it.second == "\$INSUNITS" }, "neither exists in R12")
    }

    @Test
    fun oneLayerPerSheetWithACyclingAciColour() {
        val d = manySheets(10)
        val p = pairs(DxfExporter.export(d, PhysicalSize(100.0)))
        val layers = ArrayList<Pair<String, Int>>()
        for (i in p.indices) if (p[i] == 0 to "LAYER") {
            var name = ""
            var aci = -1
            var j = i + 1
            while (p[j].first != 0) { if (p[j].first == 2) name = p[j].second; if (p[j].first == 62) aci = p[j].second.toInt(); j++ }
            layers.add(name to aci)
        }
        val sheetLayers = layers.filter { it.first != "0" }
        assertEquals((1..10).map { "SHEET_$it" }, sheetLayers.map { it.first })
        assertEquals(sheetLayers.take(8).map { it.second }.distinct().size, 8, "eight distinct colours before cycling")
        assertEquals(sheetLayers[0].second, sheetLayers[8].second)
        assertEquals(sheetLayers[1].second, sheetLayers[9].second)
        val declared = p[p.indexOf(2 to "LAYER") + 1]
        assertEquals(70 to layers.size.toString(), declared)
    }

    @Test
    fun everyRingIsAClosedPolylineWithVerticesAndSeqend() {
        val d = sampleDrawing()
        val polys = parsePolylines(pairs(DxfExporter.export(d, sampleSize)))
        assertEquals(5, polys.size, "frame, hole, island, triangle, bar")
        assertTrue(polys.all { it.closed && it.sawSeqend })
    }

    @Test
    fun geometryRoundTripsWithYFlippedToYUp() {
        val d = sampleDrawing()
        val polys = parsePolylines(pairs(DxfExporter.export(d, sampleSize)))
        val h = 120.0
        var at = 0
        for (i in d.sheets.indices) {
            val expected = flipped(expectedRings(d, i, sampleSize), h)
            val got = polys.drop(at).take(expected.size)
            assertTrue(got.all { it.layer == "SHEET_${i + 1}" })
            assertRingsEqual(expected, got.map { it.vertices }, 0.00006, "sheet $i")
            at += expected.size
        }
        // The thin bar (104..116 mm down) is near the bottom of the image, so 4..16 mm up from the origin.
        assertTrue(polys.last().vertices.all { it.y in 3.99..16.01 })
    }

    @Test
    fun inchesMultiplyByOneOver25Point4() {
        val d = sampleDrawing()
        val mm = parsePolylines(pairs(DxfExporter.export(d, sampleSize)))
        val inch = parsePolylines(pairs(DxfExporter.export(d, sampleSize, options = DxfOptions(inches = true))))
        for (i in mm.indices) for (j in mm[i].vertices.indices) {
            assertTrue(abs(mm[i].vertices[j].x / 25.4 - inch[i].vertices[j].x) < 2e-6)
            assertTrue(abs(mm[i].vertices[j].y / 25.4 - inch[i].vertices[j].y) < 2e-6)
        }
    }

    @Test
    fun aSingleSheetAndATileKeepTheLayerNumberAndStayOnTheMat() {
        val d = sampleDrawing()
        val tile = Tiling.plan(d, sampleSize, TileSpec.custom(100.0, 100.0, 5.0, 10.0)).tile(0, 0)
        val p = pairs(DxfExporter.export(d, sampleSize, sheet = 0, tile = tile))
        val polys = parsePolylines(p)
        assertTrue(polys.isNotEmpty() && polys.all { it.layer == "SHEET_1" })
        assertTrue(polys.all { poly -> poly.vertices.all { it.x in -1e-6..90.0 + 1e-6 && it.y in -1e-6..90.0 + 1e-6 } })
        assertEquals(1, p.count { it == 0 to "LAYER" } - 1, "default layer plus one sheet")
    }

    @Test
    fun realNumbersAlwaysCarryADecimalPoint() {
        val dxf = DxfExporter.export(sampleDrawing(), sampleSize)
        val numbers = pairs(dxf).filter { it.first in listOf(10, 20, 30) }.map { it.second }
        assertTrue(numbers.isNotEmpty() && numbers.all { '.' in it && 'E' !in it && 'e' !in it })
    }
}
