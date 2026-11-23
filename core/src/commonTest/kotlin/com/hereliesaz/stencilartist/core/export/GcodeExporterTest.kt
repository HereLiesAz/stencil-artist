package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GcodeExporterTest {
    private class Ring(val laserOn: Boolean, val points: List<Point>, val feeds: List<Double>)

    private fun code(line: String) = line.substringBefore(';').trim()

    private fun word(line: String, letter: Char): Double? =
        code(line).split(" ").firstOrNull { it.startsWith(letter) && it.length > 1 }?.substring(1)?.toDouble()

    /**
     * Replays laser-mode G-code: every G0 starts a ring and must turn the beam off (S0); G1 moves cut with
     * the modal power and feed. Returns each ring's start and cut points and checks the beam state as it goes.
     */
    private fun laserRings(g: String): List<Ring> {
        val rings = ArrayList<Ring>()
        var s = 0.0
        var f = 0.0
        var cur: ArrayList<Point>? = null
        var feeds = ArrayList<Double>()
        var powerOnCut = true
        var on = false
        fun flush() { cur?.let { rings.add(Ring(powerOnCut, it, feeds)) }; cur = null; feeds = ArrayList() }
        for (line in g.lines()) {
            val c = code(line)
            if (c.isEmpty()) continue
            val parts = c.split(" ")
            when (parts[0]) {
                "G0" -> {
                    flush()
                    word(c, 'S')?.let { s = it }
                    assertEquals(0.0, s, "beam off while travelling: $c")
                    cur = arrayListOf(Point(word(c, 'X')!!, word(c, 'Y')!!))
                    powerOnCut = true
                }
                "G1" -> {
                    word(c, 'S')?.let { s = it }
                    word(c, 'F')?.let { f = it }
                    assertTrue(s > 0.0 && f > 0.0, "cutting needs power and feed: $c")
                    feeds.add(f)
                    cur!!.add(Point(word(c, 'X')!!, word(c, 'Y')!!))
                    on = true
                }
                "M5" -> { flush(); s = 0.0 }
            }
        }
        flush()
        assertTrue(on || rings.isEmpty())
        return rings
    }

    @Test
    fun theModalHeaderComesFirstAndTheProgramEndsWithTheLaserOff() {
        val lines = GcodeExporter.export(sampleDrawing(), sampleSize).lines().map { code(it) }.filter { it.isNotEmpty() }
        assertEquals("G21 G90", lines[0])
        assertEquals("M4 S0", lines[1])
        assertEquals(listOf("M5", "M2"), lines.takeLast(2))
        assertEquals(3, lines.count { it == "M5" })
    }

    @Test
    fun commentsNameEachSheetAndAllSheetsPauseWithM0Between() {
        val g = GcodeExporter.export(sampleDrawing(), sampleSize)
        val comments = g.lines().filter { it.startsWith("; Stencil Artist") }
        assertEquals(3, comments.size)
        assertTrue(comments[0].contains("sheet 1 of 3") && comments[0].contains("#cc3300"))
        assertTrue(comments[2].contains("sheet 3 of 3") && comments[2].contains("#f8f8f0"))
        val m0 = g.lines().filter { code(it).startsWith("M0") }
        assertEquals(2, m0.size)
        val order = g.lines().map { code(it) }.filter { it.isNotEmpty() }
        assertTrue(order.indexOf("M0") > order.indexOf("M5"), "the pause follows the sheet's M5")
        assertEquals(0, GcodeExporter.export(sampleDrawing(), sampleSize, sheet = 1).lines().count { code(it).startsWith("M0") })
    }

    @Test
    fun theBeamIsOffForEveryTravelAndRingsRoundTripInMillimetresWithYUp() {
        val d = sampleDrawing()
        val o = GcodeOptions(feedMmPerMin = 900.0, travelMmPerMin = 5000.0, power = 750.0)
        val g = GcodeExporter.export(d, sampleSize, sheet = 0, options = o)
        val rings = laserRings(g)
        val expected = expectedRings(d, 0, sampleSize).map { r -> (r + r.first()).map { Point(it.x, 120.0 - it.y) } }
        assertRingsEqual(expected, rings.map { it.points }, 0.0005)
        assertTrue(rings.all { it.feeds.all { f -> f == 900.0 } })
        assertTrue(g.contains("G1 X"), "cuts exist")
        assertTrue(g.lines().filter { code(it).startsWith("G0") }.all { it.contains("S0") }, "every rapid carries S0")
        assertTrue(g.lines().filter { code(it).startsWith("G1") }.first().contains("S750") )
    }

    @Test
    fun passesRepeatEachOutlineFromItsStart() {
        val d = sampleDrawing()
        val one = laserRings(GcodeExporter.export(d, sampleSize, sheet = 2))
        val three = laserRings(GcodeExporter.export(d, sampleSize, sheet = 2, options = GcodeOptions(passes = 3)))
        assertEquals(one.size, three.size)
        val cuts = one[0].points.drop(1)
        assertEquals(one[0].points.first(), three[0].points.first())
        assertEquals(cuts + cuts + cuts, three[0].points.drop(1))
    }

    @Test
    fun routerModePlungesAndRetractsAroundEveryCut() {
        val d = sampleDrawing()
        val o = GcodeOptions(laserMode = false, safeZMm = 6.0, cutZMm = -3.0, passes = 2, feedMmPerMin = 600.0, power = 12000.0)
        val g = GcodeExporter.export(d, sampleSize, sheet = 1, options = o)
        val lines = g.lines().map { code(it) }.filter { it.isNotEmpty() }
        assertEquals(listOf("G21 G90", "G0 Z6", "M3 S12000"), lines.take(3))
        assertEquals(listOf("G0 Z6", "M5", "M2"), lines.takeLast(3))
        assertTrue(lines.none { it.startsWith("M4") })
        var z = 6.0
        var cuttingXy = 0
        val plunges = ArrayList<Double>()
        for (l in lines) {
            word(l, 'Z')?.let { nz -> if (l.startsWith("G1")) plunges.add(nz); z = nz }
            if (l.startsWith("G1 X")) { cuttingXy++; assertTrue(z < 0.0, "cutting below the surface: $l") }
            if (l.startsWith("G0 X")) assertEquals(6.0, z, "travel at the safe height: $l")
        }
        assertEquals(listOf(-1.5, -3.0), plunges, "two passes step down to the cut depth")
        assertEquals(2 * 3, cuttingXy, "a triangle cut twice")
    }

    @Test
    fun tilesStayOnTheMat() {
        val d = sampleDrawing()
        val tile = Tiling.plan(d, sampleSize, TileSpec.custom(100.0, 100.0, 5.0, 10.0)).tile(0, 1)
        for (r in laserRings(GcodeExporter.export(d, sampleSize, tile = tile))) {
            assertTrue(r.points.all { it.x in -1e-9..90.0 + 1e-9 && it.y in -1e-9..90.0 + 1e-9 })
        }
    }

    @Test
    fun optionsAreValidated() {
        assertFailsWith<IllegalArgumentException> { GcodeOptions(feedMmPerMin = 0.0) }
        assertFailsWith<IllegalArgumentException> { GcodeOptions(passes = 0) }
        assertFailsWith<IllegalArgumentException> { GcodeOptions(power = -1.0) }
        assertFailsWith<IllegalArgumentException> { GcodeOptions(safeZMm = 1.0, cutZMm = 2.0) }
    }
}
