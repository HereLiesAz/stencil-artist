package com.hereliesaz.stencilartist.core.paint

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanTopTest {

    private fun alphaOf(p: Int) = p ushr 24
    private fun channel(p: Int, shift: Int) = (p shr shift) and 0xFF
    private val size = CanTop.SIZE
    private fun at(pixels: IntArray, x: Int, y: Int) = pixels[y * size + x]

    @Test
    fun theTopIsASquareOfTheDocumentedSize() {
        assertEquals(size * size, CanTop.pixels(0xFF336699.toInt()).size)
        assertEquals(size * size, CanTop.plain().size)
    }

    @Test
    fun theCanIsARoundShapeWithClearCorners() {
        val p = CanTop.pixels(0xFFB84725.toInt())
        assertEquals(0, alphaOf(p[0]), "the corner is clear")
        assertEquals(0, alphaOf(at(p, size - 1, size - 1)))
        assertTrue(alphaOf(at(p, size / 2, size / 2)) >= 250, "the middle is can")
        val covered = p.count { alphaOf(it) >= 250 }
        // A disc fills pi/4 (about 78%) of its bounding square.
        assertTrue(covered in (size * size * 70 / 100)..(size * size * 80 / 100), "a disc, got $covered of ${size * size}")
    }

    @Test
    fun thePaintColourSitsBehindThePictureAndShowsThroughItsClearDome() {
        val red = CanTop.pixels(0xFFE02010.toInt())
        val blue = CanTop.pixels(0xFF1030E0.toInt())
        // Find a pixel inside the can where the picture is see-through: there the paint must show.
        val plain = CanTop.plain()
        val clear = (0 until size * size).filter { i ->
            val x = i % size - size / 2
            val y = i / size - size / 2
            x * x + y * y < (size * 30 / 100) * (size * 30 / 100) && alphaOf(plain[i]) in 1..120
        }
        assertTrue(clear.size > 50, "the dome has clear areas; found ${clear.size}")
        val redder = clear.count { channel(red[it], 16) > channel(red[it], 0) + 20 }
        val bluer = clear.count { channel(blue[it], 0) > channel(blue[it], 16) + 20 }
        assertTrue(redder > clear.size / 2, "red paint shows through ($redder of ${clear.size})")
        assertTrue(bluer > clear.size / 2, "blue paint shows through ($bluer of ${clear.size})")
    }

    @Test
    fun theRimStaysSilverWhateverThePaint() {
        // The outer rim is opaque in the picture, so two very different paints look the same there.
        val a = CanTop.pixels(0xFFE02010.toInt())
        val b = CanTop.pixels(0xFF1030E0.toInt())
        val plain = CanTop.plain()
        val rim = (0 until size * size).filter { alphaOf(plain[it]) >= 250 }
        assertTrue(rim.isNotEmpty())
        val same = rim.count { abs(channel(a[it], 8) - channel(b[it], 8)) < 6 }
        assertTrue(same > rim.size / 4, "opaque silver parts are unaffected by the paint: $same of ${rim.size}")
    }

    @Test
    fun theSameColourAlwaysGivesTheSamePixels() {
        assertContentEquals(CanTop.pixels(0xFF22AA44.toInt()), CanTop.pixels(0xFF22AA44.toInt()))
    }

    @Test
    fun blackPaintStillShowsTheRimAndNozzleRing() {
        val p = CanTop.pixels(0xFF000000.toInt()).filter { alphaOf(it) >= 250 }.map { channel(it, 8) }
        assertTrue(p.max() > p.min() + 40, "a black can is not one flat disc")
    }
}
