package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [count] colours that are far apart in Oklab and none grey: hues around the wheel, alternately dark and bright. */
internal fun distinctColors(count: Int): List<Int> = List(count) { i ->
    val hue = i * 6.0 / count
    val value = if (i % 2 == 0) 0.55 else 1.0
    val saturation = 0.9
    val sector = hue.toInt()
    val f = hue - sector
    val p = value * (1 - saturation)
    val q = value * (1 - saturation * f)
    val t = value * (1 - saturation * (1 - f))
    val (r, g, b) = when (sector % 6) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, value, t)
        3 -> Triple(p, q, value)
        4 -> Triple(t, p, value)
        else -> Triple(value, p, q)
    }
    (0xFF shl 24) or ((r * 255 + 0.5).toInt() shl 16) or ((g * 255 + 0.5).toInt() shl 8) or (b * 255 + 0.5).toInt()
}

/** An opaque image of [colors] as flat [cell]-pixel squares, [columns] to a row, left to right then down. */
internal fun flatRegions(colors: List<Int>, columns: Int, cell: Int = 12): RgbaImage {
    val rows = (colors.size + columns - 1) / columns
    val w = columns * cell
    val h = rows * cell
    return RgbaImage(w, h, IntArray(w * h) { i ->
        val region = (i / w / cell) * columns + (i % w) / cell
        colors[minOf(region, colors.size - 1)]
    })
}

class ColorSeparatorTest {

    private val CELL = 30

    private val w = Golden.SUBJECT_W
    private val h = Golden.SUBJECT_H

    private fun image(rgbaHex: String): RgbaImage {
        val b = rgbaHex.unhex()
        return RgbaImage(w, h, IntArray(w * h) { i ->
            fun c(k: Int) = b[i * 4 + k].toInt() and 0xFF
            (c(3) shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        })
    }

    private val darkSubject get() = image(Golden.SUBJECT_DARK_RGBA)
    private val lightSubject get() = image(Golden.SUBJECT_LIGHT_RGBA)

    private fun channelGap(a: Int, b: Int) = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
        abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))

    private fun checkRegions(smoothing: Int, tolerance: Int) {
        for (k in listOf(2, 5, 9, 24)) {
            val colors = distinctColors(k)
            val columns = if (k <= 5) k else 6
            val image = flatRegions(colors, columns, CELL)
            val s = assertNotNull(ColorSeparator.separate(image, ColorParams(sheets = k, smoothing = smoothing))).separation
            assertEquals(k, s.sheets.size, "$k regions")
            val used = HashSet<Int>()
            for ((region, color) in colors.withIndex()) {
                // The pixel in the middle of the region, clear of any edge.
                val x = (region % columns) * CELL + CELL / 2
                val y = (region / columns) * CELL + CELL / 2
                val sheet = s.index[y * image.width + x]
                assertTrue(used.add(sheet), "$k regions: two regions share sheet $sheet")
                assertTrue(channelGap(s.sheets[sheet].color, color) <= tolerance, "$k regions: region $region is ${s.sheets[sheet].color.toUInt().toString(16)}, not ${color.toUInt().toString(16)}")
            }
        }
    }

    @Test
    fun flatRegionsSeparateIntoExactlyThoseColoursEachOnItsOwnSheet() = checkRegions(smoothing = 1, tolerance = 2)

    @Test
    fun smoothingMovesSheetColoursOnlyByWhatItsMixedCornerPixelsAdd() = checkRegions(smoothing = 9, tolerance = 14)

    @Test
    fun theSameImageGivesTheSameSheetsEveryTime() {
        val image = flatRegions(distinctColors(9), 3)
        val a = ColorSeparator.separate(image, ColorParams(sheets = 6))!!.separation
        val b = ColorSeparator.separate(image, ColorParams(sheets = 6))!!.separation
        assertEquals(a.sheets, b.sheets)
        assertContentEquals(a.index, b.index)
        val photo = darkSubject
        assertContentEquals(ColorSeparator.separate(photo)!!.separation.index, ColorSeparator.separate(photo)!!.separation.index)
    }

    @Test
    fun givenColoursAreTheOnlyColoursAndEveryPixelGoesToTheNearest() {
        val given = listOf(0xFF102030.toInt(), 0xFFE04010.toInt(), 0xFF10C0F0.toInt(), 0xFFFFE000.toInt(), 0xFF7F00FF.toInt())
        // A noisy image from a fixed generator, every pixel opaque.
        var seed = 12345L
        fun next(): Int { seed = (seed * 6364136223846793005L + 1442695040888963407L); return (seed ushr 33).toInt() and 0xFF }
        val image = RgbaImage(40, 30, IntArray(1200) { (0xFF shl 24) or (next() shl 16) or (next() shl 8) or next() })
        val s = ColorSeparator.separate(image, ColorParams(sheets = 5, smoothing = 1, closing = 1, colors = given))!!.separation
        assertEquals(given.toSet(), s.sheets.map { it.color }.toSet())
        val shown = s.render()
        val labs = given.map { Oklab.fromArgb(it) }
        for (i in image.pixels.indices) {
            assertTrue(shown[i] in given, "pixel $i is not one of the given colours")
            val p = Oklab.fromArgb(image.pixels[i])
            fun d(c: OklabColor) = (c.l - p.l) * (c.l - p.l) + (c.a - p.a) * (c.a - p.a) + (c.b - p.b) * (c.b - p.b)
            val best = labs.minOf { d(it) }
            assertEquals(best, d(Oklab.fromArgb(shown[i])), 1e-12, "pixel $i did not go to its nearest colour")
        }
    }

    @Test
    fun givenColoursAreRefusedWhenTheyDoNotMatchTheCountOrRepeat() {
        assertFailsWith<IllegalArgumentException> { ColorParams(sheets = 3, colors = listOf(0xFF000000.toInt())) }
        assertFailsWith<IllegalArgumentException> { ColorParams(sheets = 2, colors = listOf(0xFF102030.toInt(), 0x80102030.toInt())) }
        assertFailsWith<IllegalArgumentException> { ColorParams(sheets = 0) }
        assertFailsWith<IllegalArgumentException> { ColorParams(sheets = ColorParams.MAX_SHEETS + 1) }
        assertFailsWith<IllegalArgumentException> { ColorParams(smoothing = 4) }
        assertFailsWith<IllegalArgumentException> { ColorParams(closing = 0) }
    }

    /** Regions of different areas and lightness: 1x1, 2x1, 3x1, 4x1 and 5x1 cells wide in one row. */
    private fun bands(colors: List<Int>): RgbaImage {
        val cell = 10
        val widths = colors.indices.map { (it + 1) * cell }
        val total = widths.sum()
        return RgbaImage(total, 20, IntArray(total * 20) { i ->
            var x = i % total
            var k = 0
            while (x >= widths[k]) { x -= widths[k]; k++ }
            colors[k]
        })
    }

    private val five = listOf(0xFFE8D0A0.toInt(), 0xFF2050A0.toInt(), 0xFFC03020.toInt(), 0xFF30A050.toInt(), 0xFF804090.toInt())

    private fun lightness(s: Separation) = s.sheets.map { Oklab.fromArgb(it.color).l }

    @Test
    fun lightToDarkAndDarkToLightOrderSheetsByLightness() {
        val image = bands(five)
        val down = ColorSeparator.separate(image, ColorParams(sheets = 5, order = ColorOrder.LIGHT_TO_DARK))!!
        val up = ColorSeparator.separate(image, ColorParams(sheets = 5, order = ColorOrder.DARK_TO_LIGHT))!!
        assertEquals(5, down.separation.sheets.size)
        assertEquals(lightness(down.separation).sortedDescending(), lightness(down.separation))
        assertEquals(lightness(up.separation).sorted(), lightness(up.separation))
        assertEquals(down.separation.sheets.map { it.color }.reversed(), up.separation.sheets.map { it.color })
        assertEquals(ColorOrder.LIGHT_TO_DARK, down.order)
    }

    @Test
    fun largestFirstOrdersSheetsByTheAreaTheyShow() {
        val image = bands(five)
        val s = ColorSeparator.separate(image, ColorParams(sheets = 5, closing = 1, order = ColorOrder.LARGEST_FIRST))!!.separation
        val areas = s.sheets.indices.map { sheet -> s.index.count { it == sheet } }
        assertEquals(areas.sortedDescending(), areas)
        assertEquals(five[4], s.sheets[0].color.let { c -> five.minBy { channelGap(it, c) } }, "the widest band is the base")
    }

    @Test
    fun autoPutsADarkSubjectOnADarkBaseAndALightOneOnALightBase() {
        val darkImage = flatRegions(listOf(0xFF101820.toInt(), 0xFF182030.toInt(), 0xFF203040.toInt(), 0xFFD0C0A0.toInt()), 2)
        val lightImage = flatRegions(listOf(0xFFF0F0E0.toInt(), 0xFFE0E8F0.toInt(), 0xFFF0E0E0.toInt(), 0xFF302010.toInt()), 2)
        val dark = ColorSeparator.separate(darkImage, ColorParams(sheets = 4))!!
        val light = ColorSeparator.separate(lightImage, ColorParams(sheets = 4))!!
        assertTrue(dark.dark)
        assertEquals(ColorOrder.DARK_TO_LIGHT, dark.order)
        assertEquals(lightness(dark.separation).sorted(), lightness(dark.separation))
        assertTrue(!light.dark)
        assertEquals(ColorOrder.LIGHT_TO_DARK, light.order)
        assertEquals(lightness(light.separation).sortedDescending(), lightness(light.separation))
    }

    @Test
    fun anyNumberOfSheetsKeepsTheInvariantsOnDarkAndLightSubjects() {
        val tonalSilhouette = TonalSeparator.separate(darkSubject)!!.separation.mask(0)
        val tonalLight = TonalSeparator.separate(lightSubject)!!.separation.mask(0)
        for (count in listOf(1, 2, 3, 4, 6, 9, 24, 60)) {
            for ((name, subject, silhouette) in listOf(Triple("dark", darkSubject, tonalSilhouette), Triple("light", lightSubject, tonalLight))) {
                for (order in ColorOrder.entries) {
                    val s = ColorSeparator.separate(subject, ColorParams(sheets = count, order = order))!!.separation
                    val label = "$name subject, $count sheets, $order"
                    assertTrue(s.sheets.size in 1..count, "$label: ${s.sheets.size} sheets")
                    assertEquals(s.sheets.size, s.sheets.map { it.color }.toSet().size, "$label: duplicate sheet colours")
                    assertTrue(s.index.all { it == Separation.NONE || it in s.sheets.indices }, "$label: index out of range")
                    for (i in s.index.indices) {
                        if (subject.pixels[i] ushr 24 == 0 && !silhouette[i]) assertEquals(Separation.NONE, s.index[i], "$label: painted outside the silhouette at $i")
                    }
                    assertContentEquals(silhouette, s.mask(0), "$label: the base is the silhouette")
                    val partition = IntArray(s.index.size)
                    for (sheet in s.sheets.indices) {
                        if (sheet > 0) {
                            val outer = s.mask(sheet - 1)
                            val inner = s.mask(sheet)
                            assertTrue(inner.indices.all { !inner[it] || outer[it] }, "$label: sheet $sheet escapes sheet ${sheet - 1}")
                        }
                        s.mask(sheet, Coverage.EXCLUSIVE).forEachIndexed { i, on -> if (on) partition[i]++ }
                    }
                    assertContentEquals(IntArray(s.index.size) { if (silhouette[it]) 1 else 0 }, partition, "$label: exclusive masks partition the subject")
                }
            }
        }
    }

    @Test
    fun aCutOutKeepsItsTransparencyAndFilledHolesStayOnTheBase() {
        val colors = distinctColors(3)
        val cell = 12
        val base = flatRegions(colors, 3, cell)
        // Punch a hole in the middle of the middle region and clear the top row of the first.
        val pixels = base.pixels.copyOf()
        for (y in 4 until 8) for (x in cell + 4 until cell + 8) pixels[y * base.width + x] = 0
        for (y in 0 until 6) for (x in 0 until cell) pixels[y * base.width + x] = 0
        val image = RgbaImage(base.width, base.height, pixels)
        val s = ColorSeparator.separate(image, ColorParams(sheets = 3))!!.separation
        assertEquals(0, s.index[5 * image.width + cell + 5], "a hole inside the subject is filled and stays on the base")
        assertTrue(s.mask(0)[5 * image.width + cell + 5])
        assertEquals(Separation.NONE, s.index[2 * image.width + 3], "a transparent pixel on the border stays outside")
    }

    @Test
    fun anImageWithFewerColoursThanSheetsGetsOneSheetPerColour() {
        val colors = distinctColors(3)
        val image = flatRegions(colors, 3)
        for (asked in listOf(4, 8, 40)) {
            val s = ColorSeparator.separate(image, ColorParams(sheets = asked))!!.separation
            assertEquals(3, s.sheets.size, "$asked asked")
            assertEquals(3, s.sheets.map { it.color }.toSet().size)
        }
        val one = ColorSeparator.separate(RgbaImage(6, 6, IntArray(36) { 0xFF336699.toInt() }), ColorParams(sheets = 5))!!.separation
        assertEquals(listOf(Sheet(0xFF336699.toInt())), one.sheets)
        assertTrue(one.index.all { it == 0 })
    }

    @Test
    fun nothingToSeparateIsNull() {
        assertNull(ColorSeparator.separate(RgbaImage(1, 1, IntArray(1))))
        assertNull(ColorSeparator.separate(RgbaImage(8, 8, IntArray(64))), "fully transparent")
        assertNull(ColorSeparator.separate(RgbaImage(1, 9, IntArray(9) { -1 })), "one pixel wide")
    }

    @Test
    fun closingFillsANarrowGapInASheetAndNeverEatsIntoOne() {
        // Dark base with two bright bars one pixel apart: closing with 5 joins them, closing 1 leaves the gap.
        val dark = 0xFF101010.toInt()
        val bright = 0xFFF0E070.toInt()
        val image = RgbaImage(20, 12, IntArray(240) { i ->
            val x = i % 20
            if (x == 5 || x == 7) bright else dark
        })
        val loose = ColorSeparator.separate(image, ColorParams(sheets = 2, smoothing = 1, closing = 1, order = ColorOrder.DARK_TO_LIGHT))!!.separation
        val tight = ColorSeparator.separate(image, ColorParams(sheets = 2, smoothing = 1, closing = 5, order = ColorOrder.DARK_TO_LIGHT))!!.separation
        assertEquals(0, loose.index[6 + 20 * 5])
        assertEquals(1, tight.index[6 + 20 * 5])
        for (i in image.pixels.indices) assertTrue(tight.index[i] >= loose.index[i], "closing only moves pixels to later sheets")
    }

    @Test
    fun theColourOfASoftEdgeIsItsOwnNotAFringeOfBlack() {
        // A light disc with half-transparent rim on nothing: no dark cluster may appear.
        val light = 0xFFF0E8D0.toInt()
        val size = 40
        val image = RgbaImage(size, size, IntArray(size * size) { i ->
            val dx = i % size - 19.5
            val dy = i / size - 19.5
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            when {
                d < 14 -> light
                d < 16 -> (0x60 shl 24) or (light and 0xFFFFFF)
                else -> 0
            }
        })
        val s = ColorSeparator.separate(image, ColorParams(sheets = 3))!!.separation
        assertEquals(1, s.sheets.size, "one colour, however soft its edge")
        assertTrue(channelGap(s.sheets[0].color, light) <= 2)
    }

    @Test
    fun closingEverySheetAtOnceIsClosingEachMaskOnItsOwn() {
        var seed = 99L
        fun next(bound: Int): Int { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % bound).toInt() }
        for ((w, h, kernel) in listOf(Triple(23, 17, 5), Triple(31, 9, 4), Triple(8, 40, 7), Triple(13, 13, 2))) {
            val sheets = 6
            val observed = BooleanArray(w * h) { next(10) != 0 }
            val rank = IntArray(w * h) { if (!observed[it]) (if (next(2) == 0) 0 else Separation.NONE) else next(sheets) }
            val closed = ColorSeparator.closedRanks(rank, observed, w, h, kernel)
            for (s in 1 until sheets) {
                val expected = Raster.close(BooleanArray(w * h) { rank[it] >= s }, w, h, kernel)
                for (i in 0 until w * h) {
                    val shown = closed[i] >= s
                    assertEquals(expected[i] && observed[i], shown, "${w}x$h kernel $kernel sheet $s pixel $i")
                }
            }
        }
    }
}
