package com.hereliesaz.stencilartist.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecimalTest {
    @Test
    fun writesFixedDecimalsAndTrimsTrailingZeros() {
        assertEquals("2.5", Decimal.format(2.5, 3))
        assertEquals("3", Decimal.format(3.0, 3))
        assertEquals("0.125", Decimal.format(0.125, 3))
        assertEquals("0.001", Decimal.format(0.0005, 3), "half rounds away from zero")
        assertEquals("1234.568", Decimal.format(1234.5678, 3))
        assertEquals("0", Decimal.format(0.0004, 3))
        assertEquals("100", Decimal.format(99.9996, 3))
        assertEquals("12", Decimal.format(11.6, 0))
    }

    @Test
    fun negativeNumbersAndNegativeZero() {
        assertEquals("-0.125", Decimal.format(-0.125, 3))
        assertEquals("-3", Decimal.format(-3.0, 3))
        assertEquals("0", Decimal.format(-0.0, 3))
        assertEquals("0", Decimal.format(-0.0001, 3), "no minus sign on a value that rounds to zero")
    }

    @Test
    fun neverUsesExponentNotationAndKeepsLeadingFractionZeros() {
        assertEquals("1000000000", Decimal.format(1e9, 3))
        assertEquals("0.000001", Decimal.format(1e-6, 6))
        assertEquals("0.05", Decimal.format(0.05, 3))
        assertEquals("123456789.123", Decimal.format(123456789.123, 3))
    }

    @Test
    fun pointAlwaysKeepsOneDecimalOnWholeNumbers() {
        assertEquals("3.0", Decimal.format(3.0, 4, pointAlways = true))
        assertEquals("0.0", Decimal.format(0.00001, 4, pointAlways = true))
        assertEquals("2.25", Decimal.format(2.25, 4, pointAlways = true))
    }

    @Test
    fun refusesNumbersItCannotWrite() {
        assertFailsWith<IllegalArgumentException> { Decimal.format(Double.NaN, 3) }
        assertFailsWith<IllegalArgumentException> { Decimal.format(Double.POSITIVE_INFINITY, 3) }
        assertFailsWith<IllegalArgumentException> { Decimal.format(1.0, 12) }
    }

    @Test
    fun colourHex() {
        assertEquals("#cc3300", Decimal.rgbHex(ORANGE))
        assertEquals("#000000", Decimal.rgbHex(0xFF000000.toInt()))
        assertEquals("#ffffff", Decimal.rgbHex(0x00FFFFFF))
    }
}
