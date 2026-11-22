package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GraffitiXR's `StencilTonesTest`, carried over, plus the cases any number of tones adds. */
class TonesTest {

    private fun histogramOf(vararg values: Pair<Int, Int>): IntArray {
        val h = IntArray(256)
        for ((v, n) in values) h[v] += n
        return h
    }

    @Test
    fun threeClearTonesSplitBetweenTheirCentres() {
        val (lo, hi) = Tones.thresholds(histogramOf(20 to 100, 128 to 100, 235 to 100))!!.toList()
        assertTrue(lo in 20..127, "first threshold $lo should fall between the dark and middle tones")
        assertTrue(hi in 128..234, "second threshold $hi should fall between the middle and light tones")
    }

    @Test
    fun thresholdsAreOrderedEvenForAOneToneImage() {
        for (count in 2..12) {
            val t = Tones.thresholds(histogramOf(90 to 500), count)!!
            assertEquals(count - 1, t.size)
            for (i in 1 until t.size) assertTrue(t[i - 1] < t[i], "thresholds must increase, got ${t.toList()}")
            assertTrue(t.all { it in 0..255 })
        }
    }

    @Test
    fun anEmptyHistogramHasNoThresholds() {
        assertNull(Tones.thresholds(IntArray(256)))
    }

    @Test
    fun theSameHistogramAlwaysSplitsTheSameWay() {
        val h = histogramOf(10 to 40, 60 to 70, 140 to 90, 200 to 30, 250 to 60)
        assertContentEquals(Tones.thresholds(h), Tones.thresholds(h))
    }

    @Test
    fun anyNumberOfTonesSeparatesThatManyClearTones() {
        val levels = listOf(10, 60, 110, 160, 210, 250)
        val t = Tones.thresholds(histogramOf(*levels.map { it to 100 }.toTypedArray()), levels.size)!!
        for (i in t.indices) assertTrue(t[i] in levels[i] until levels[i + 1], "threshold $i = ${t[i]}")
    }

    @Test
    fun oneToneHasNoThresholdAndEveryLevelCanBeItsOwnTone() {
        assertEquals(0, Tones.thresholds(histogramOf(90 to 5), 1)!!.size)
        val all = Tones.thresholds(IntArray(256) { 1 }, 256)!!
        assertContentEquals(IntArray(255) { it }, all)
    }

    @Test
    fun theCallGoesToWhicheverOfDarkAndLightCoversMoreArea() {
        assertTrue(Tones.isDarkByArea(histogramOf(20 to 700, 240 to 300)))
        assertFalse(Tones.isDarkByArea(histogramOf(20 to 300, 240 to 700)))
    }

    @Test
    fun aTieAndAnEmptySubjectAreDark() {
        assertTrue(Tones.isDarkByArea(histogramOf(20 to 500, 240 to 500)))
        assertTrue(Tones.isDarkByArea(IntArray(256)))
    }

    @Test
    fun turningBrightnessDownAndContrastUpMakesAMidBrightImageDark() {
        // Mid-grey 150 averages "light" by mean luminance, but dimmed and contrast-pushed it is dark.
        assertTrue(Tones.isDarkByArea(histogramOf(150 to 1000)))
        // Genuinely bright stays light.
        assertFalse(Tones.isDarkByArea(histogramOf(230 to 1000)))
    }

    @Test
    fun luminanceUsesTheOldWeights() {
        assertEquals(0.0, Tones.luma(0, 0, 0), 1e-9)
        assertEquals(1.0, Tones.luma(255, 255, 255), 1e-9)
        assertTrue(Tones.luma(0, 255, 0) > Tones.luma(0, 0, 255), "green outweighs blue")
    }

    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun threeSheetsRampBlackGreyWhiteFromTheBase() {
        assertEquals(listOf(black, grey, white), Tones.ramp(3, dark = true).map { it.color })
        assertEquals(listOf(white, grey, black), Tones.ramp(3, dark = false).map { it.color })
    }

    @Test
    fun aRampOfAnyLengthRunsEvenlyFromTheBaseToTheOtherEnd() {
        assertEquals(listOf(black), Tones.ramp(1, dark = true).map { it.color })
        assertEquals(listOf(white), Tones.ramp(1, dark = false).map { it.color })
        for (count in listOf(2, 4, 7, 40)) {
            val levels = Tones.ramp(count, dark = true).map { it.color and 0xFF }
            assertEquals(0, levels.first())
            assertEquals(255, levels.last())
            assertEquals(levels.sorted(), levels)
            assertEquals(levels.reversed(), Tones.ramp(count, dark = false).map { it.color and 0xFF })
        }
    }
}
