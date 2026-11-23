package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HpglExporterTest {
    private class Stroke(val pen: Int, val ring: List<Point>)

    /** Instructions split on ';', and the pen strokes: each PU starts one, following PD runs continue it. */
    private fun commands(hpgl: String): List<String> {
        assertTrue(hpgl.trimEnd().endsWith(";"), "every instruction is terminated")
        return hpgl.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun strokes(cmds: List<String>): List<Stroke> {
        val out = ArrayList<Stroke>()
        var pen = 0
        var cur: ArrayList<Point>? = null
        fun flush() { cur?.let { if (it.size > 1) out.add(Stroke(pen, it)) }; cur = null }
        fun coords(s: String) = s.split(",").map { it.toInt() }.chunked(2).map { Point(it[0] / 40.0, it[1] / 40.0) }
        for (c in cmds) {
            when {
                c.startsWith("SP") -> { flush(); pen = c.substring(2).toInt() }
                c.startsWith("PU") -> { flush(); val xy = c.substring(2); if (xy.isNotEmpty()) cur = ArrayList(coords(xy)) }
                c.startsWith("PD") -> cur!!.addAll(coords(c.substring(2)))
                c == "IN" -> {}
                else -> error("unexpected instruction $c")
            }
        }
        flush()
        return out
    }

    @Test
    fun theFileInitialisesSelectsPensAndParksTheHead() {
        val hpgl = HpglExporter.export(sampleDrawing(), sampleSize)
        val cmds = commands(hpgl)
        assertEquals("IN", cmds.first())
        assertEquals(listOf("PU0,0", "SP0"), cmds.takeLast(2))
        assertEquals(listOf("SP1", "SP2", "SP3"), cmds.filter { it.startsWith("SP") && it != "SP0" })
        assertTrue(hpgl.all { it.code in 10..126 }, "plain ASCII")
    }

    @Test
    fun everyPathIsClosedAndCoordinatesAreIntegersAtFortyUnitsPerMillimetre() {
        val cmds = commands(HpglExporter.export(sampleDrawing(), sampleSize))
        for (c in cmds.filter { it.startsWith("PU") || it.startsWith("PD") }) {
            assertTrue(c.substring(2).split(",").all { it.toIntOrNull() != null }, "integers only: $c")
        }
        for (s in strokes(cmds)) assertEquals(s.ring.first(), s.ring.last(), "closed")
    }

    @Test
    fun geometryRoundTripsWithinAPlotterUnitAndYPointsUp() {
        val d = sampleDrawing()
        val all = strokes(commands(HpglExporter.export(d, sampleSize)))
        var at = 0
        for (i in d.sheets.indices) {
            val expected = expectedRings(d, i, sampleSize).map { r -> r.map { Point(it.x, 120.0 - it.y) } }
            val got = all.drop(at).take(expected.size)
            assertTrue(got.all { it.pen == i + 1 })
            assertRingsEqual(expected, got.map { it.ring.dropLast(1) }, 0.5 / 40 + 1e-9, "sheet $i")
            at += expected.size
        }
        assertEquals(at, all.size)
    }

    @Test
    fun pensCycleOneToEight() {
        val cmds = commands(HpglExporter.export(manySheets(10), PhysicalSize(100.0)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 1, 2), cmds.filter { it.startsWith("SP") && it != "SP0" }.map { it.substring(2).toInt() })
    }

    @Test
    fun aLongRingIsSplitIntoSeveralPenDownCommandsAndStillRoundTrips() {
        val d = roundDrawing(300)
        val size = PhysicalSize(100.0)
        val cmds = commands(HpglExporter.export(d, size))
        val pds = cmds.count { it.startsWith("PD") }
        assertEquals(1 + 300 / HpglExporter.PAIRS_PER_PD, pds, "301 pen-down points in runs of at most 64")
        assertTrue(cmds.filter { it.startsWith("PD") }.all { it.substring(2).split(",").size <= HpglExporter.PAIRS_PER_PD * 2 })
        val s = strokes(cmds).single()
        assertRingsEqual(expectedRings(d, 0, size).map { r -> r.map { Point(it.x, 100.0 - it.y) } }, listOf(s.ring.dropLast(1)), 0.5 / 40 + 1e-9)
    }

    @Test
    fun aSingleSheetAndATileWork() {
        val d = sampleDrawing()
        val tile = Tiling.plan(d, sampleSize, TileSpec.custom(100.0, 100.0, 5.0, 10.0)).tile(0, 1)
        val cmds = commands(HpglExporter.export(d, sampleSize, sheet = 1, tile = tile))
        assertEquals(listOf("SP2"), cmds.filter { it.startsWith("SP") && it != "SP0" })
        for (s in strokes(cmds)) {
            assertTrue(s.ring.all { it.x in -1e-9..90.0 + 1e-9 && it.y in -1e-9..90.0 + 1e-9 })
            assertEquals(s.ring.first(), s.ring.last())
        }
    }
}
