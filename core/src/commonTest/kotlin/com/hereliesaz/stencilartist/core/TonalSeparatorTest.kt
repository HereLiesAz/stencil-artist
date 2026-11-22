package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonalSeparatorTest {

    private val w = Golden.SUBJECT_W
    private val h = Golden.SUBJECT_H

    /** RGBA bytes, as OpenCV holds them, to the pipeline's ARGB ints. */
    private fun image(rgbaHex: String): RgbaImage {
        val b = rgbaHex.unhex()
        return RgbaImage(w, h, IntArray(w * h) { i ->
            fun c(k: Int) = b[i * 4 + k].toInt() and 0xFF
            (c(3) shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        })
    }

    private fun ranks(hex: String) = hex.unhex().let { b -> IntArray(b.size) { if (b[it].toInt() and 0xFF == 255) Separation.NONE else b[it].toInt() } }

    private val darkSubject get() = image(Golden.SUBJECT_DARK_RGBA)
    private val lightSubject get() = image(Golden.SUBJECT_LIGHT_RGBA)

    @Test
    fun theDefaultsReproduceGraffitiXrOnADarkSubject() {
        val result = assertNotNull(TonalSeparator.separate(darkSubject))
        assertTrue(result.dark)
        assertContentEquals(ranks(Golden.SUBJECT_DARK_RANKS), result.separation.index)
        assertEquals(listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt()), result.separation.sheets.map { it.color })
    }

    @Test
    fun theDefaultsReproduceGraffitiXrOnALightSubject() {
        val result = assertNotNull(TonalSeparator.separate(lightSubject))
        assertTrue(!result.dark)
        assertContentEquals(ranks(Golden.SUBJECT_LIGHT_RANKS), result.separation.index)
        assertEquals(listOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt()), result.separation.sheets.map { it.color })
    }

    @Test
    fun anyNumberOfSheetsNestAndTheBaseIsAlwaysTheSameSilhouette() {
        val silhouette = TonalSeparator.separate(darkSubject)!!.separation.mask(0)
        for (count in listOf(1, 2, 3, 4, 6, 9, 24)) {
            for (subject in listOf(darkSubject, lightSubject)) {
                val s = TonalSeparator.separate(subject, TonalParams(sheets = count))!!.separation
                assertEquals(count, s.sheets.size)
                assertTrue(s.index.all { it == Separation.NONE || it in 0 until count })
                assertContentEquals(silhouette, s.mask(0), "$count sheets: the base is the silhouette")
                for (sheet in 1 until count) {
                    val outer = s.mask(sheet - 1)
                    val inner = s.mask(sheet)
                    assertTrue(inner.indices.all { !inner[it] || outer[it] }, "$count sheets: sheet $sheet escapes sheet ${sheet - 1}")
                }
            }
        }
    }

    @Test
    fun moreSheetsUseMoreOfThem() {
        val four = TonalSeparator.separate(darkSubject, TonalParams(sheets = 4))!!.separation
        assertEquals(setOf(Separation.NONE, 0, 1, 2, 3), four.index.toSet())
    }

    @Test
    fun theBaseCanBeForcedEitherWay() {
        assertTrue(TonalSeparator.separate(lightSubject, TonalParams(base = Base.DARK))!!.dark)
        assertTrue(!TonalSeparator.separate(darkSubject, TonalParams(base = Base.LIGHT))!!.dark)
    }

    @Test
    fun biasMovesAreaBetweenTheBaseAndTheSheetsOverIt() {
        fun overBase(bias: Int) = TonalSeparator.separate(darkSubject, TonalParams(bias = bias))!!.separation.index.count { it >= 1 }
        // On a dark image, raising the thresholds leaves less of it light enough to leave the base.
        assertTrue(overBase(40) < overBase(0))
        assertTrue(overBase(-40) > overBase(0))
    }

    @Test
    fun sheetsTakeWhateverColoursTheyAreGiven() {
        val colors = listOf(0xFF102030.toInt(), 0xFFE04010.toInt(), 0xFF10C0F0.toInt(), 0xFFFFE000.toInt(), 0xFF7F00FF.toInt())
        val s = TonalSeparator.separate(darkSubject, TonalParams(sheets = 5, colors = colors))!!.separation
        assertEquals(colors, s.sheets.map { it.color })
        assertTrue(s.render().all { it == 0 || it in colors })
        assertFailsWith<IllegalArgumentException> { TonalParams(sheets = 3, colors = colors) }
    }

    @Test
    fun anUncutImageIsAllSubject() {
        val opaque = RgbaImage(w, h, IntArray(w * h) { darkSubject.pixels[it] or (0xFF shl 24) })
        assertTrue(TonalSeparator.separate(opaque)!!.separation.index.none { it == Separation.NONE })
    }

    @Test
    fun nothingToSeparateIsNull() {
        assertNull(TonalSeparator.separate(RgbaImage(1, 1, IntArray(1))))
        assertNull(TonalSeparator.separate(RgbaImage(8, 8, IntArray(64))), "fully transparent")
    }
}
