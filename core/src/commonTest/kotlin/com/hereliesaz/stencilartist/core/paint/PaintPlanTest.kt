package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.flatRegions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PINK = 0xFFE6007E.toInt()
private const val BLUE = 0xFF2060D0.toInt()
private const val RED = 0xFFD01010.toInt()
private const val MOSS = 0xFF6B8E23.toInt()
private const val BLACK = 0xFF0A0A0A.toInt()

class PaintPlanTest {
    private val catalogue = fixtureCatalogue()
    private val pink = catalogue.named("Shock Pink")
    private val loopPink = catalogue.named("Pink Fantasy")
    private val blue = catalogue.named("Blue Sky")
    private val red = catalogue.named("Rouge")
    private val moss = catalogue.named("Moss")
    private val silver = catalogue.named("Marble Silver")

    /** Pink, blue, red and moss as flat blocks: the image the cans below are chosen against. */
    private val image = flatRegions(listOf(PINK, BLUE, RED, MOSS), columns = 2, cell = 20)

    @Test
    fun freeModeMakesNoLayersBecauseTheColoursAreTheSeparatorsToFind() {
        assertEquals(emptyList(), PaintPlan.layersFor(PaintMode.FREE, catalogue, image, 4, emptySet(), listOf(pink)))
        assertFalse(PaintMode.FREE.snapped)
        assertTrue(PaintMode.PICK_FOR_ME.snapped && PaintMode.MY_CANS.snapped)
    }

    @Test
    fun myCansMakesOneLayerPerDistinctColourAndIgnoresCansWithNoColour() {
        val layers = PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), listOf(pink, silver, blue, loopPink))
        assertEquals(listOf(PINK, BLUE), layers.map { it.color })
        assertEquals(listOf(pink, loopPink), layers[0].cans, "two makers' cans of one colour are one layer")
        assertEquals(emptyList(), PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), listOf(silver)))
        assertEquals(emptyList(), PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), emptyList()))
    }

    @Test
    fun pickForMeMatchesTheImagesColoursToDistinctCansOfTheAllowedBrands() {
        val layers = PaintPlan.layersFor(PaintMode.PICK_FOR_ME, catalogue, image, 4, emptySet(), emptyList())
        assertEquals(4, layers.size)
        assertEquals(4, layers.map { it.color }.toSet().size, "no two layers are the same colour")
        assertTrue(layers.all { it.distance < 0.1 }, "each of the image's four colours has a near can")

        // Only Loop Colors: Montana's pink and moss are not allowed, so the layers are Loop's cans.
        val loop = PaintPlan.layersFor(PaintMode.PICK_FOR_ME, catalogue, image, 3, setOf("Loop Colors"), emptyList())
        assertEquals(3, loop.size)
        assertTrue(loop.all { layer -> layer.cans.all { it.brand == "Loop Colors" } })

        assertEquals(emptyList(), PaintPlan.layersFor(PaintMode.PICK_FOR_ME, catalogue, null, 3, emptySet(), emptyList()))
    }

    @Test
    fun pickForMeCannotAskForMoreLayersThanTheAllowedCansHaveColours() {
        // Montana: pink, red-ish "Rouge Écarlate", light pink, moss, black = 5 colours (Marble Silver has none).
        assertEquals(5, PaintPlan.availableColours(catalogue, setOf("Montana Cans")))
        // Loop: pink (same as Montana's), red, blue = 3.
        assertEquals(3, PaintPlan.availableColours(catalogue, setOf("Loop Colors")))
        // All brands: 5 + red + blue (pink is shared) = 7.
        assertEquals(7, PaintPlan.availableColours(catalogue, emptySet()))
        assertEquals(1, PaintPlan.availableColours(catalogue, setOf("Montana Cans|MARBLE 400ml")).coerceAtLeast(1))
        assertEquals(0, PaintPlan.availableColours(catalogue, setOf("Montana Cans|MARBLE 400ml")), "a line of cans with no colour value")
    }

    @Test
    fun theSeparatorUsesExactlyTheChosenColoursAndEveryPixelGetsOne() {
        val layers = PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), listOf(pink, blue, red, moss))
        val result = assertNotNull(PaintPlan.separate(image, ColorParams(smoothing = 1, closing = 1), layers))
        assertEquals(4, result.separation.sheets.size)
        assertEquals(layers.map { it.color }.toSet(), result.separation.sheets.map { it.color }.toSet())
        assertEquals(setOf(0, 1, 2, 3), result.layers.map { it.sheet }.toSet())
        assertTrue(result.layers.all { it.used })
        // The finished piece is made of nothing but the chosen cans' colours.
        assertEquals(layers.map { it.color }.toSet(), result.separation.render(null).toSet())
    }

    @Test
    fun aCanNoPixelPicksIsFlaggedUnusedAndLeftOffTheListsUnlessKept() {
        // Black fits no part of a pink, blue, red and moss image... except that every pixel must go to the nearest
        // colour, and the dark moss is nearer to black than to the others only if moss is not offered.
        val layers = PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), listOf(pink, blue, red, catalogue.named("Black")))
        val result = assertNotNull(PaintPlan.separate(image, ColorParams(smoothing = 1, closing = 1), layers))
        val unused = result.layers.filter { !it.used }
        // With no moss on offer, moss goes to the nearest of the four; whichever it is, the other three stay used.
        assertTrue(unused.size <= 1, "at most the can nothing is nearest to is idle")

        // Build the idle case exactly: an all-pink image and a blue can beside the pink one.
        val flat = flatRegions(listOf(PINK), columns = 1, cell = 20)
        val two = PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, flat, 2, emptySet(), listOf(pink, blue))
        val flatResult = assertNotNull(PaintPlan.separate(flat, ColorParams(smoothing = 1, closing = 1), two))
        assertEquals(2, flatResult.layers.size)
        val idle = flatResult.layers.single { !it.used }
        assertEquals(blue, idle.layer.primary)
        assertEquals("Layer ${idle.sheet + 1} (Loop Colors Blue Sky) isn't needed for this image", PaintPlan.unusedNote(idle))

        assertEquals(1, PaintPlan.layerCount(flatResult.layers), "the count shown is the count used")
        assertEquals(listOf(idle), PaintPlan.unneeded(flatResult.layers))
        val keep = setOf(BLUE and 0xFFFFFF)
        assertEquals(2, PaintPlan.layerCount(flatResult.layers, keep), "a kept layer counts")
        assertEquals(emptyList(), PaintPlan.unneeded(flatResult.layers, keep))

        val items = PaintPlan.shopping(flatResult.layers)
        assertEquals(listOf(pink), items.map { it.can })
        assertEquals(listOf(idle.sheet + 1).let { listOf(flatResult.layers.single { it.used }.sheet + 1) }, items.map { it.layerNumber }, "the sheet number, not a count")
        assertEquals(2, PaintPlan.shopping(flatResult.layers, kept = keep).size)
    }

    @Test
    fun theShoppingListOfOneSheetHoldsOnlyThatSheetAndKeepsItsSheetNumber() {
        val layers = PaintPlan.layersFor(PaintMode.MY_CANS, catalogue, image, 4, emptySet(), listOf(pink, blue, red, moss))
        val result = assertNotNull(PaintPlan.separate(image, ColorParams(smoothing = 1, closing = 1), layers))
        val all = PaintPlan.shopping(result.layers)
        assertEquals(4, all.size)
        assertEquals(result.layers.map { it.sheet + 1 }, all.map { it.layerNumber })
        val third = PaintPlan.shopping(result.layers, sheets = listOf(2))
        assertEquals(1, third.size)
        assertEquals(3, third[0].layerNumber)
        assertEquals(result.layers[2].layer.primary, third[0].can)
        assertEquals(emptyList(), PaintPlan.shopping(result.layers, sheets = emptyList()))
    }

    @Test
    fun paramsKeepTheUsersSmoothingAndNeedAtLeastOneLayer() {
        val layers = Selection(listOf(pink, blue)).distinctColours()
        val params = PaintPlan.paramsFor(ColorParams(sheets = 9, smoothing = 5, closing = 3), layers)
        assertEquals(2, params.sheets)
        assertEquals(5, params.smoothing)
        assertEquals(3, params.closing)
        assertEquals(listOf(PINK, BLUE), params.colors)
        assertFailsWith<IllegalArgumentException> { PaintPlan.paramsFor(ColorParams(), emptyList()) }
    }

    @Test
    fun cansOfOneColourAreReportedAsOneLayer() {
        assertEquals(
            listOf("Montana Cans Shock Pink and Loop Colors Pink Fantasy are the same colour: one layer."),
            PaintPlan.sharedColourNotes(listOf(pink, blue, loopPink, pink)),
        )
        assertEquals(emptyList(), PaintPlan.sharedColourNotes(listOf(pink, blue, silver)))
        val third = Can("Third", "L", "Pink Three", null, "3", PINK, HexSource.OFFICIAL, null)
        assertEquals(
            listOf("Montana Cans Shock Pink, Loop Colors Pink Fantasy and Third Pink Three are the same colour: one layer."),
            PaintPlan.sharedColourNotes(listOf(pink, loopPink, third)),
        )
    }

    @Test
    fun brandAndLineChipsToggleLikeTheyShow() {
        var allowed = emptySet<String>()
        allowed = CanFilter.toggleBrand(allowed, "Montana Cans")
        assertEquals(setOf("Montana Cans"), allowed)
        assertTrue(CanFilter.lineActive(allowed, "Montana Cans", "GOLD 400ml"))

        // From a whole brand, tapping a line leaves every other line.
        allowed = CanFilter.toggleLine(allowed, catalogue, "Montana Cans", "GOLD 400ml")
        assertEquals(setOf("Montana Cans|MARBLE 400ml"), allowed)
        assertTrue(CanFilter.brandActive(allowed, "Montana Cans"))
        assertFalse(CanFilter.lineActive(allowed, "Montana Cans", "GOLD 400ml"))

        // Tapping the missing line brings the whole brand back.
        allowed = CanFilter.toggleLine(allowed, catalogue, "Montana Cans", "GOLD 400ml")
        assertEquals(setOf("Montana Cans"), allowed)

        // Turning off the last line turns the brand off, and another brand is left alone.
        allowed = CanFilter.toggleBrand(allowed, "Loop Colors") + emptySet()
        allowed = CanFilter.toggleLine(allowed, catalogue, "Loop Colors", "Colors 400ml")
        assertEquals(setOf("Montana Cans"), allowed)

        allowed = CanFilter.toggleBrand(CanFilter.toggleLine(allowed, catalogue, "Montana Cans", "GOLD 400ml"), "Montana Cans")
        assertEquals(emptySet(), allowed, "an active brand is removed with its lines")
    }

    @Test
    fun savedCansComeBackThroughTheCatalogueAndUnknownKeysAreDropped() {
        val text = PaintPersistence.encodeCans(listOf(blue, pink, silver))
        assertEquals(listOf(blue, pink, silver), PaintPersistence.decodeCans(text, catalogue))

        val stale = text + "\nGone Brand|Gone Line||GONE-1\n\n" + pink.canKey + "\r\n"
        assertEquals(listOf(blue, pink, silver), PaintPersistence.decodeCans(stale, catalogue), "unknown, blank and repeated keys are skipped")
        assertEquals(emptyList(), PaintPersistence.decodeCans("", catalogue))
        assertEquals(emptyList(), PaintPersistence.decodeCans("nonsense", catalogue))

        // A smaller catalogue (a later version without Blue Sky) quietly loses just that can.
        val smaller = PaintCatalogue.parse(fixtureText(row(0, "Shock Pink", "RV-252", "e6007e")))
        assertEquals(listOf(pink.canKey), PaintPersistence.decodeCans(text, smaller).map { it.canKey })
    }

    @Test
    fun savedBrandsAndLinesComeBackAndGoneOnesAreDropped() {
        val allowed = setOf("Montana Cans|GOLD 400ml", "Loop Colors")
        val text = PaintPersistence.encodeAllowed(allowed)
        assertEquals(allowed, PaintPersistence.decodeAllowed(text, catalogue))
        assertEquals(
            setOf("Loop Colors"),
            PaintPersistence.decodeAllowed("Loop Colors\nGone\nMontana Cans|Gone Line\nGone|GOLD 400ml\n", catalogue),
        )
        assertEquals(emptySet(), PaintPersistence.decodeAllowed("", catalogue))
    }

    @Test
    fun learnedBarcodesSurviveTheSameRoundTripAndResolveToTheirCan() {
        val ean = catalogue.named("Shock Pink").gtin!!
        val user = UserBarcodes.EMPTY.learn(ean, blue.canKey)
        val back = UserBarcodes.decode(user.encode())
        assertEquals(user, back)
        assertEquals(blue, catalogue.resolve(ean, back), "a learned barcode beats the catalogue")
        assertNull(catalogue.resolve("0000000000000", back))
    }
}
