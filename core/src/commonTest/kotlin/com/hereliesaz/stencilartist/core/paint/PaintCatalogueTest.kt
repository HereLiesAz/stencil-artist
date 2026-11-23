package com.hereliesaz.stencilartist.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaintCatalogueTest {
    private val catalogue = fixtureCatalogue()

    private fun names(cans: List<Can>) = cans.map { it.name }

    @Test
    fun parsesTheFixture() {
        assertEquals(9, catalogue.cans.size)
        assertEquals(listOf("Montana Cans", "Loop Colors"), catalogue.brands)
        assertEquals(listOf("GOLD 400ml", "MARBLE 400ml"), catalogue.lines("Montana Cans"))
        assertEquals(emptyList(), catalogue.lines("Nobody"))
        assertEquals(mapOf("Montana Cans" to listOf("www.montana-cans.com"), "Loop Colors" to listOf("loopcolors.com")), catalogue.sources)
        val pink = catalogue.named("Shock Pink")
        assertEquals(Can("Montana Cans", "GOLD 400ml", "Shock Pink", null, "RV-252", 0xFFE6007E.toInt(), HexSource.OFFICIAL, EAN), pink)
        assertEquals(HexSource.SAMPLED, catalogue.named("Rouge").hexSource)
        val silver = catalogue.named("Marble Silver")
        assertNull(silver.rgb)
        assertNull(silver.hexSource)
        assertEquals("Metallic", silver.finish)
        assertEquals(1, catalogue.cans("Montana Cans", "MARBLE 400ml").size)
        assertEquals(6, catalogue.cans("Montana Cans").size)
        assertEquals(emptyList(), catalogue.cans("Loop Colors", "nope"))
        // Brand and line names are shared, not copied for every can.
        assertSame(catalogue.named("Moss").brand, catalogue.named("Black").brand)
    }

    @Test
    fun toleratesCrLfAndBlankLines() {
        val text = fixtureText(row(0, "Moss", "RV-40", "6b8e23"), "", row(2, "Rouge", "L-100", "d01010")).replace("\n", "\r\n")
        assertEquals(2, PaintCatalogue.parse(text).cans.size)
    }

    @Test
    fun rejectsDamagedFilesReadably() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { PaintCatalogue.parse(text) }.message.orEmpty()
        assertTrue("no #stencil-artist-paints header" in message("hello\tworld\n"))
        assertTrue("line 7" in message(fixtureText(row(0, "Moss", "RV-40", "6b8e2g"))), "bad hex digit names its line")
        assertTrue("valid barcode" in message(fixtureText(row(0, "Moss", gtin = "4006381333932"))))
        assertTrue("unknown line" in message(fixtureText(row(7, "Moss"))))
        assertTrue("7 fields" in message(fixtureText("0\tMoss\t\t")))
        assertTrue("hex source" in message(fixtureText(row(0, "Moss", hex = "6b8e23", source = "x"))))
        assertTrue("six digits" in message(fixtureText(row(0, "Moss", hex = "6b8e"))))
        assertTrue("needs a name" in message(fixtureText(row(0, ""))))
    }

    @Test
    fun canKeyIsStableAndFindsTheCanAfterReparsing() {
        val again = fixtureCatalogue()
        assertEquals(catalogue.cans.map { it.canKey }, again.cans.map { it.canKey })
        assertEquals("Montana Cans|GOLD 400ml||RV-252", catalogue.named("Shock Pink").canKey)
        // Without a code the name stands in.
        val coded = Can("B", "L", "Name", "Gloss", null, null, null, null)
        assertEquals("B|L|Gloss|Name", coded.canKey)
        for (can in catalogue.cans) assertEquals(can, again.byKey(can.canKey))
        assertNull(catalogue.byKey("nothing|here||"))
        assertEquals(catalogue.cans.size, catalogue.cans.map { it.canKey }.toSet().size)
    }

    @Test
    fun codeFormsAreOneCode() {
        for (query in listOf("rv 252", "RV-252", "rv252", "Rv_252", " rv  252 ")) {
            assertEquals(listOf("Shock Pink"), names(catalogue.search(query).take(1)), query)
        }
        assertEquals(listOf("Shock Pink"), names(catalogue.byCode("rv-252")))
        assertEquals(listOf("Shock Pink"), names(catalogue.byCode("RV 252")))
        assertEquals(emptyList(), catalogue.byCode("rv-253"))
    }

    @Test
    fun searchIgnoresAccentsAndCase() {
        for (query in listOf("écarlate", "ecarlate", "ECARLATE", "Rouge Écarlate", "rouge-ecarlate")) {
            assertEquals("Rouge Écarlate", catalogue.search(query).first().name, query)
        }
        assertEquals("Rouge Écarlate", catalogue.search("ÉCARLATE").first().name)
    }

    @Test
    fun searchMatchesBrandAndLine() {
        assertEquals(6, catalogue.search("montana").size)
        assertEquals(listOf("Marble Silver"), names(catalogue.search("marble")))
        assertEquals(listOf("Pink Fantasy", "Rouge", "Blue Sky"), names(catalogue.search("loop colors")))
        assertEquals(listOf("Pink Fantasy"), names(catalogue.search("loop pink")))
        assertEquals(emptyList(), catalogue.search("   "))
        assertEquals(emptyList(), catalogue.search("zzz"))
    }

    @Test
    fun searchRanksExactCodeThenCodePrefixThenNamePrefixThenWordThenSubstring() {
        val ranking = PaintCatalogue.parse(
            fixtureText(
                row(0, "Husky", "A1", "101010"),
                row(0, "Big Sky", "A2", "202020"),
                row(0, "Skyline", "A3", "303030"),
                row(0, "Sky", "A4", "404040"),
                row(0, "Qux", "SKY2", "505050"),
                row(0, "Zed", "SKY", "606060"),
            ),
        )
        assertEquals(listOf("Zed", "Qux", "Skyline", "Sky", "Big Sky", "Husky"), names(ranking.search("sky")))
        assertEquals(listOf("Zed", "Qux"), names(ranking.search("sky", limit = 2)))
        assertEquals(emptyList(), ranking.search("sky", limit = 0))
    }

    @Test
    fun searchRestrictsToAllowedBrandsAndLines() {
        assertEquals(listOf("Pink Fantasy"), names(catalogue.search("252", allowed = setOf("Loop Colors"))))
        assertEquals(listOf("Marble Silver"), names(catalogue.search("silver", allowed = setOf("MARBLE 400ml"))))
        assertEquals(emptyList(), catalogue.search("silver", allowed = setOf("Montana Cans|GOLD 400ml")))
        assertEquals(1, catalogue.search("silver", allowed = setOf("Montana Cans|MARBLE 400ml")).size)
    }

    @Test
    fun partialCodesFindBySubstring() {
        assertEquals(listOf("Shock Pink", "Pink Fantasy"), names(catalogue.search("252")))
    }

    @Test
    fun equivalentsShareExactlyTheColour() {
        val pink = catalogue.named("Shock Pink")
        assertEquals(listOf("Pink Fantasy"), names(catalogue.equivalents(pink)))
        assertEquals(listOf("Shock Pink", "Pink Fantasy"), names(catalogue.withColour(0xE6007E)))
        assertEquals(emptyList(), catalogue.equivalents(catalogue.named("Marble Silver")))
    }

    @Test
    fun gtinByUpcAndEanForms() {
        val pink = catalogue.named("Shock Pink")
        assertEquals(pink, catalogue.byGtin(EAN))
        assertEquals(pink, catalogue.byGtin("0$EAN"), "as a GTIN-14")
        val black = catalogue.named("Black")
        assertEquals(black, catalogue.byGtin(UPC))
        assertEquals(black, catalogue.byGtin(UPC_AS_EAN), "a UPC-A is an EAN-13 with a leading zero")
        assertEquals(black, catalogue.byGtin("00$UPC_AS_EAN".takeLast(14)))
        assertEquals(black, catalogue.byGtin("0 36000-29145 2"), "spaces and hyphens are ignored")
        assertNull(catalogue.byGtin("036000291459"), "the wrong check digit is rejected, never matched nearby")
        assertNull(catalogue.byGtin(""))
        assertNull(catalogue.byGtin("abc"))
    }

    @Test
    fun gtinCheckDigits() {
        for (valid in listOf(UPC, UPC_AS_EAN, EAN, "96385074", "00036000291452", "9780306406157")) {
            assertTrue(GtinCheck.isValid(valid), valid)
        }
        // Every wrong last digit of a real number is caught.
        for (wrong in 0..9) {
            val candidate = EAN.dropLast(1) + wrong
            assertEquals(wrong == 1, GtinCheck.isValid(candidate), candidate)
        }
        // Swapping two neighbouring different digits changes the sum, so it is caught too.
        assertFalse(GtinCheck.isValid("4006381333391"))
        assertFalse(GtinCheck.isValid("400638133393"), "11 digits")
        assertFalse(GtinCheck.isValid("4006381333931 "))
        assertFalse(GtinCheck.isValid("40063813339x1"))
        assertEquals(1, GtinCheck.checkDigit("400638133393"))
        assertEquals(2, GtinCheck.checkDigit("03600029145"))
        assertEquals("00036000291452", GtinCheck.canonical(UPC))
        assertEquals("00036000291452", GtinCheck.canonical(UPC_AS_EAN))
        assertNull(GtinCheck.canonical("036000291453"))
    }

    @Test
    fun userBarcodesBeatTheCatalogue() {
        val pink = catalogue.named("Shock Pink")
        val rouge = catalogue.named("Rouge")
        val blue = catalogue.named("Blue Sky")
        val unknown = "4012345678901"
        assertTrue(GtinCheck.isValid(unknown))

        val user = UserBarcodes.EMPTY.learn(EAN, rouge.canKey).learn(unknown, blue.canKey)
        assertEquals(pink, catalogue.resolve(EAN), "no user map: the catalogue")
        assertEquals(rouge, catalogue.resolve(EAN, user), "the user's correction first")
        assertEquals(rouge, catalogue.resolve("0$EAN", user))
        assertNull(catalogue.byGtin(unknown))
        assertEquals(blue, catalogue.resolve(unknown, user), "a barcode only the user knows")
        assertEquals(catalogue.named("Black"), catalogue.resolve(UPC, user), "others fall through to the catalogue")
        assertNull(catalogue.resolve("4012345678900", user), "invalid barcodes resolve to nothing")

        // A taught key that the catalogue no longer has falls back to the catalogue.
        val stale = UserBarcodes.EMPTY.learn(EAN, "Gone|Line||X")
        assertEquals(pink, catalogue.resolve(EAN, stale))
        assertNull(catalogue.resolve(unknown, UserBarcodes.EMPTY.learn(unknown, "Gone|Line||X")))
    }

    @Test
    fun userBarcodesAreImmutableAndRoundTrip() {
        val one = UserBarcodes.EMPTY.learn(UPC, "A|B||C")
        assertEquals(0, UserBarcodes.EMPTY.size)
        assertEquals(1, one.size)
        assertEquals("A|B||C", one.keyFor(UPC_AS_EAN), "learned as UPC-A, found as EAN-13")
        assertEquals("D|E|Gloss|F", one.learn(UPC_AS_EAN, "D|E|Gloss|F").keyFor(UPC), "same barcode replaces")
        assertEquals(1, one.learn(UPC_AS_EAN, "D|E|Gloss|F").size)
        assertEquals(0, one.forget(UPC).size)
        assertSame(one, one.forget(EAN))
        val two = one.learn(EAN, "G|H||I")
        assertEquals(two, UserBarcodes.decode(two.encode()))
        assertEquals(UserBarcodes.EMPTY, UserBarcodes.decode(""))
        assertEquals(one, UserBarcodes.decode("garbage\n\t\n0123\tx\n${one.encode()}12345\t\n"), "bad lines are skipped")
        assertFailsWith<IllegalArgumentException> { UserBarcodes.EMPTY.learn("036000291453", "k") }
        assertFailsWith<IllegalArgumentException> { UserBarcodes.EMPTY.learn(UPC, "two\nlines") }
        assertNull(UserBarcodes.EMPTY.keyFor("not a barcode"))
    }

    @Test
    fun emptyCatalogueWorks() {
        assertEquals(0, PaintCatalogue.EMPTY.cans.size)
        assertEquals(emptyList(), PaintCatalogue.EMPTY.search("anything"))
        assertNotNull(PaintCatalogue.parse("#stencil-artist-paints\t1\n"))
    }
}
