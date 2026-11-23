package com.hereliesaz.stencilartist.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class UserCanColoursTest {
    private val original = fixtureCatalogue()

    @Test
    fun missingRgbCanCanBeAssignedAColourWithoutMutatingBundledCatalogue() {
        val silver = original.named("Marble Silver")
        assertNull(silver.rgb)
        val saved = UserCanColours.EMPTY.withColour(silver.canKey, 0xFF2864A0.toInt())
        val coloured = original.withUserColours(saved)
        val chosen = coloured.named("Marble Silver")
        assertEquals(0xFF2864A0.toInt(), chosen.rgb)
        assertEquals(HexSource.USER, chosen.hexSource)
        assertEquals(silver.canKey, chosen.canKey)
        assertEquals(silver, original.named("Marble Silver"))
        assertEquals(chosen, coloured.byKey(silver.canKey))
        assertEquals(listOf(chosen), coloured.withColour(0xFF2864A0.toInt()))
        assertEquals(listOf(chosen), coloured.search("marble"))
        assertEquals(chosen, coloured.cans("Montana Cans", "MARBLE 400ml").single())
    }

    @Test
    fun changesPropagateToSelectionsBarcodeAndMatchingWithoutFakingAnOfficialSwatch() {
        val pink = original.named("Shock Pink")
        val silver = original.named("Marble Silver")
        val colour = 0xFF2864A0.toInt()
        val changed = original.withUserColours(UserCanColours.EMPTY
            .withColour(silver.canKey, colour)
            .withColour(pink.canKey, colour))
        assertEquals(HexSource.USER, changed.byGtin(EAN)?.hexSource)
        assertEquals(2, changed.withColour(colour).size)
        assertEquals(1, Selection(changed.withColour(colour)).distinctColours().size)
        assertEquals(1, PaintPlan.availableColours(changed, setOf("Montana Cans|MARBLE 400ml")))
        assertEquals("Marble Silver", changed.bestCans(listOf(colour), setOf("Montana Cans|MARBLE 400ml")).single().primary.name)
    }

    @Test
    fun localDataSurvivesRestartAndSupportsClearingAndBadStoredLines() {
        val key = original.named("Marble Silver").canKey
        val a = UserCanColours.EMPTY.withColour(key, 0xFF102030.toInt())
        assertSame(UserCanColours.EMPTY, UserCanColours.EMPTY.withoutColour(key))
        assertEquals(0xFF102030.toInt(), UserCanColours.decode(a.encode()).colourFor(key))
        assertEquals(a, UserCanColours.decode(a.encode() + "bad\nmissing\t#ABCDE\n$KEY\t#G0FF00\n"))
        assertEquals(UserCanColours.EMPTY, a.withoutColour(key))
        assertEquals(original.named("Marble Silver"), original.withUserColours(a.withoutColour(key)).named("Marble Silver"))
    }

    @Test
    fun rejectsNonopaqueColoursAndInvalidKeys() {
        assertFailsWith<IllegalArgumentException> { UserCanColours.EMPTY.withColour("valid", 0x80112233.toInt()) }
        assertFailsWith<IllegalArgumentException> { UserCanColours.EMPTY.withColour("tab\tbroken", 0xFF112233.toInt()) }
        assertFailsWith<IllegalArgumentException> { UserCanColours.EMPTY.withColour("", 0xFF112233.toInt()) }
    }

    companion object {
        private const val KEY = "unknown|line||can"
    }
}
