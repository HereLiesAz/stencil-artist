package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Fixed examples, not values computed with the same functions under test. */
class PaletteColorTest {
    @Test
    fun validHexAcceptsOptionalHashAndLowercaseButAlwaysMakesOpaquePaint() {
        assertEquals(0xFF00A1BC.toInt(), PaletteColor.parseHex(" #00a1Bc "))
        assertEquals(0xFF123456.toInt(), PaletteColor.parseHex("123456"))
        assertEquals(0xFFFFFFFF.toInt(), PaletteColor.parseHex("#FFFFFF"))
        assertEquals(0xFF000000.toInt(), PaletteColor.parseHex("000000"))
    }

    @Test
    fun incompleteAndMalformedValuesCannotBeApplied() {
        for (invalid in listOf("", "#", "#FFF", "#FF00", "#FF00770", "#GG0000", "12345g", "0x123456", "#12 456")) {
            assertNull(PaletteColor.parseHex(invalid), invalid)
        }
    }

    @Test
    fun hexFormattingDoesNotTurnAlphaIntoAnExtraChannel() {
        assertEquals("#00A1BC", PaletteColor.hex(0x7000A1BC))
        assertEquals("#000000", PaletteColor.hex(0x00000000))
        assertEquals("#FFFFFF", PaletteColor.hex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun rgbSliderChangesOnlyItsOwnChannelAndKeepsOpaquePaint() {
        assertEquals(0xFF2A3B5C.toInt(), PaletteColor.withChannel(0x80123B5C.toInt(), 16, 42))
        assertEquals(0xFF12005C.toInt(), PaletteColor.withChannel(0xFF123B5C.toInt(), 8, 0))
        assertEquals(0xFF123BFF.toInt(), PaletteColor.withChannel(0xFF123B5C.toInt(), 0, 255))
        assertFailsWith<IllegalArgumentException> { PaletteColor.withChannel(0, 24, 5) }
        assertFailsWith<IllegalArgumentException> { PaletteColor.withChannel(0, 8, 256) }
    }

    @Test
    fun handEditedPaletteStillMustHaveDifferentColours() {
        assertFailsWith<IllegalArgumentException> {
            ColorParams(sheets = 2, colors = listOf(0xFF123456.toInt(), 0x80123456.toInt()))
        }
        val params = ColorParams(sheets = 2, colors = listOf(0xFF123456.toInt(), 0xFF654321.toInt()))
        assertEquals(listOf(0xFF123456.toInt(), 0xFF654321.toInt()), params.colors)
    }

    @Test
    fun preImagePaletteAndCountChangesPreserveChosenColors() {
        val chosen = listOf(0x80123456.toInt(), 0xFF654321.toInt())
        val grown = PaletteColor.resize(chosen, 4)
        assertEquals(listOf(0xFF123456.toInt(), 0xFF654321.toInt(), 0xFF223344.toInt(), 0xFFD34A32.toInt()), grown)
        assertEquals(listOf(0xFF123456.toInt()), PaletteColor.resize(grown, 1))
        assertEquals(listOf(0xFF223344.toInt(), 0xFFD34A32.toInt()), PaletteColor.resize(emptyList(), 2))
        assertEquals(listOf(0x80123456.toInt(), 0xFF654321.toInt()), chosen)
    }

    @Test
    fun largeStarterPaletteHasDistinctOpaquePaintAndSkipsDuplicateSeeds() {
        val palette = PaletteColor.resize(listOf(0xFF223344.toInt(), 0x00223344), ColorParams.MAX_SHEETS)
        assertEquals(1024, palette.size)
        assertEquals(1024, palette.toSet().size)
        assertEquals(0xFF223344.toInt(), palette[0])
        assertEquals(0xFFD34A32.toInt(), palette[1])
        assertEquals(true, palette.all { it ushr 24 == 255 })
        assertFailsWith<IllegalArgumentException> { PaletteColor.resize(emptyList(), 0) }
    }
}
