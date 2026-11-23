package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.ColorSeparator
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.distinctColors
import com.hereliesaz.stencilartist.core.flatRegions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun hex(argb: Int): String = (argb and 0xFFFFFF).toString(16).padStart(6, '0')

/** A catalogue of one can per colour in [colours], named "c<index>" in line 2 (Loop Colors). */
private fun catalogueOf(colours: List<Int>, extra: List<String> = emptyList()): PaintCatalogue =
    PaintCatalogue.parse(
        fixtureText(
            *colours.mapIndexed { i, c -> row(2, "c$i", "C$i", hex(c)) }.toTypedArray(),
            *extra.toTypedArray(),
        ),
    )

class PaintLayersTest {
    private val catalogue = fixtureCatalogue()

    @Test
    fun nearestSkipsColourlessCansAndBreaksTiesByKey() {
        val a = Can("Z", "L", "Z can", null, "1", 0xFFFF0000.toInt(), HexSource.OFFICIAL, null)
        val b = Can("A", "L", "A can", null, "1", 0xFFFF0000.toInt(), HexSource.OFFICIAL, null)
        val far = Can("M", "L", "Far", null, "1", 0xFF0000FF.toInt(), HexSource.OFFICIAL, null)
        val none = Can("N", "L", "No colour", null, "1", null, null, null)
        assertEquals(b, PaintColour.nearest(0xFFFE0000.toInt(), listOf(a, b, far, none)))
        assertEquals(b, PaintColour.nearest(0xFFFE0000.toInt(), listOf(none, far, b, a)), "list order does not matter")
        assertEquals(far, PaintColour.nearest(0xFF0000FE.toInt(), listOf(a, b, far)))
        assertEquals(null, PaintColour.nearest(0xFF000000.toInt(), listOf(none)))
        assertEquals(null, PaintColour.nearest(0xFF000000.toInt(), emptyList()))
    }

    @Test
    fun distanceIsZeroForTheSameColourAndIgnoresAlpha() {
        assertEquals(0.0, PaintColour.distance(0xFF336699.toInt(), 0x80336699.toInt()))
        assertTrue(PaintColour.distance(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) > 0.9)
    }

    @Test
    fun distinctColoursCollapsesSharedColoursAndRemembersTheCans() {
        val pink = catalogue.named("Shock Pink")
        val loopPink = catalogue.named("Pink Fantasy")
        val blue = catalogue.named("Blue Sky")
        val silver = catalogue.named("Marble Silver")
        val selection = Selection(listOf(pink, blue, loopPink, pink, silver))

        val layers = selection.distinctColours()
        assertEquals(2, layers.size, "two distinct colours, five cans chosen")
        assertEquals(listOf(pink, loopPink), layers[0].cans)
        assertEquals(pink, layers[0].primary)
        assertEquals(listOf(loopPink), layers[0].equivalents)
        assertEquals(listOf(blue), layers[1].cans)
        assertEquals(listOf(silver), selection.colourless)
        assertEquals(layers.map { it.color }, selection.colors())
        assertEquals(0.0, layers[0].distance)
    }

    @Test
    fun colorParamsNeedsExactlyAsManyDistinctColoursAsSheets() {
        val pink = catalogue.named("Shock Pink")
        val loopPink = catalogue.named("Pink Fantasy")
        val blue = catalogue.named("Blue Sky")
        // The trap: three cans, but two are one colour.
        assertFailsWith<IllegalArgumentException> {
            ColorParams(sheets = 3, colors = listOf(pink.rgb!!, loopPink.rgb!!, blue.rgb!!))
        }
        val params = Selection(listOf(pink, loopPink, blue)).colorParams()
        assertEquals(2, params.sheets)
        assertEquals(listOf(pink.rgb!!, blue.rgb!!), params.colors)
        val kept = Selection(listOf(pink, blue)).colorParams(ColorParams(smoothing = 5, closing = 3))
        assertEquals(5 to 3, kept.smoothing to kept.closing, "the other settings are kept")
        assertFailsWith<IllegalArgumentException> { Selection(listOf(catalogue.named("Marble Silver"))).colorParams() }
        assertFailsWith<IllegalArgumentException> { Selection(emptyList()).colorParams() }

        // And the separator accepts them.
        val image = flatRegions(listOf(pink.rgb!!, blue.rgb!!), columns = 2)
        val result = assertNotNull(ColorSeparator.separate(image, params))
        assertEquals(setOf(pink.rgb!!, blue.rgb!!), result.separation.sheets.map { it.color }.toSet())
    }

    @Test
    fun bestCansPicksTheNearestAndResolvesCollisions() {
        val near = listOf(0xFFFF0000.toInt(), 0xFFF00000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        val all = catalogueOf(near)
        // Both colours are nearest to c0 (red). The one nearer to it keeps it; the other gets the next best.
        val targets = listOf(0xFFFE0000.toInt(), 0xFFFF0002.toInt())
        val layers = all.bestCans(targets)
        assertEquals(2, layers.size)
        assertEquals(2, layers.map { it.color }.toSet().size, "distinct colours, as the separator needs")
        assertEquals(targets, layers.map { it.target })
        val winner = if (PaintColour.distance(targets[0], near[0]) <= PaintColour.distance(targets[1], near[0])) 0 else 1
        assertEquals(near[0], layers[winner].color)
        assertEquals(near[1], layers[1 - winner].color)

        // The result follows the order of the colours given.
        val ordered = all.bestCans(listOf(0xFF0000F0.toInt(), 0xFF00F000.toInt()))
        assertEquals(listOf("c2", "c3"), ordered.map { it.primary.name })

        // Fewer distinct candidates than colours: the leftovers are dropped.
        val two = catalogueOf(near.take(1))
        assertEquals(1, two.bestCans(listOf(0xFFFF0000.toInt(), 0xFFFE0000.toInt(), 0xFFFD0000.toInt())).size)
        assertEquals(emptyList(), PaintCatalogue.EMPTY.bestCans(listOf(0xFFFF0000.toInt())))
        assertEquals(emptyList(), all.bestCans(emptyList()))
        assertEquals(1, all.bestCans(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), n = 1).size)
        assertFailsWith<IllegalArgumentException> { all.bestCans(listOf(0xFFFF0000.toInt()), n = 2) }
    }

    @Test
    fun bestCansMakesOneLayerOfCansThatShareAColour() {
        val layers = catalogue.bestCans(listOf(0xFFE60080.toInt()))
        assertEquals(1, layers.size)
        assertEquals(listOf("Shock Pink", "Pink Fantasy"), layers[0].cans.map { it.name })
        // Restricted to a brand, only that brand's cans are offered.
        val loop = catalogue.bestCans(listOf(0xFFE60080.toInt()), allowed = setOf("Loop Colors"))
        assertEquals(listOf("Pink Fantasy"), loop[0].cans.map { it.name })
        // Cans without a colour value are never picked.
        val onlyColourless = catalogue.bestCans(listOf(0xFFC0C0C0.toInt()), allowed = setOf("MARBLE 400ml"))
        assertEquals(emptyList(), onlyColourless)
    }

    @Test
    fun bestCansIsDeterministicWhateverTheTies() {
        // Two cans of different brands, same colour, same distance from the target: one layer, both cans.
        val twin = PaintCatalogue.parse(fixtureText(row(0, "Twin", "T1", "808080"), row(2, "Twin", "T1", "808080")))
        assertEquals(1, twin.bestCans(listOf(0xFF7F7F7F.toInt())).size)
        val again = twin.bestCans(listOf(0xFF7F7F7F.toInt()))
        assertEquals(twin.bestCans(listOf(0xFF7F7F7F.toInt())), again)
    }

    @Test
    fun pickForReturnsExactlyTheCansWhoseColoursTheImageHas() {
        val palette = distinctColors(8)
        val all = catalogueOf(palette)
        val chosen = listOf(0, 2, 4, 6)
        val image = flatRegions(chosen.map { palette[it] }, columns = 2)

        val layers = all.pickFor(image, 4)
        assertEquals(4, layers.size)
        assertEquals(chosen.map { "c$it" }.toSet(), layers.map { it.primary.name }.toSet())
        assertEquals(chosen.map { palette[it] }.toSet(), layers.map { it.color }.toSet())
        for (item in ShoppingList.of(layers)) assertEquals(MatchQuality.EXACT, item.quality, item.name)

        // Asking for fewer colours than the image has still returns cans of colours it has.
        val fewer = all.pickFor(image, 2)
        assertEquals(2, fewer.size)
        // A fully transparent image has nothing to paint.
        assertEquals(emptyList(), all.pickFor(RgbaImage(8, 8, IntArray(64)), 3))
        assertFailsWith<IllegalArgumentException> { all.pickFor(image, 0) }
    }

    @Test
    fun pickForNeedsNoExactCan() {
        val palette = distinctColors(4)
        // A catalogue whose cans are each a little off the image's colours.
        val off = palette.map { (0xFF shl 24) or ((((it shr 16) and 0xFF) xor 3) shl 16) or ((((it shr 8) and 0xFF) xor 3) shl 8) or ((it and 0xFF) xor 3) }
        val layers = catalogueOf(off).pickFor(flatRegions(palette, columns = 2), 4)
        assertEquals(setOf("c0", "c1", "c2", "c3"), layers.map { it.primary.name }.toSet())
        assertTrue(layers.all { it.distance > 0.0 })
    }

    @Test
    fun unusedLayersAreThePaintsTheImageDoesNotNeed() {
        val palette = distinctColors(3)
        val cans = catalogueOf(palette).cans
        val selection = Selection(cans)
        val layers = selection.distinctColours()
        val flat = ColorParams(smoothing = 1, closing = 1)
        // The image only has the first two colours; the third can is never nearest to anything.
        val image = flatRegions(palette.take(2), columns = 2)
        val separation = assertNotNull(ColorSeparator.separate(image, selection.colorParams(flat))).separation
        assertEquals(3, separation.sheets.size, "a colour no pixel is nearest to still gets its sheet")

        val unused = unusedLayers(separation)
        assertEquals(1, unused.size)
        assertEquals(palette[2], separation.sheets[unused[0]].color)
        assertEquals(listOf(layers[2]), unusedLayers(separation, layers))

        // With every colour present nothing is unused.
        val full = assertNotNull(ColorSeparator.separate(flatRegions(palette, columns = 3), selection.colorParams(flat))).separation
        assertEquals(emptyList(), unusedLayers(full))
        assertEquals(emptyList(), unusedLayers(full, layers))
    }

    @Test
    fun shoppingListBucketsByOklabDistance() {
        val grey = Can("B", "L", "Grey", null, "G", 0xFF808080.toInt(), HexSource.OFFICIAL, "4006381333931")
        val twin = Can("C", "L", "Twin", null, "T", 0xFF808080.toInt(), HexSource.SAMPLED, null)
        fun layer(target: Int) = PaintLayer(0xFF808080.toInt(), listOf(grey, twin), target)

        val same = layer(0xFF808080.toInt())
        val oneStep = layer(0xFF818181.toInt())
        val near = layer(0xFF868686.toInt())
        val far = layer(0xFFA0A0A0.toInt())
        assertEquals(0.0, same.distance)
        assertTrue(oneStep.distance in 0.0..MatchQuality.EXACT_MAX, "one 8-bit step is below the exact limit: ${oneStep.distance}")
        assertTrue(near.distance in MatchQuality.EXACT_MAX..MatchQuality.CLOSE_MAX, "six steps is close: ${near.distance}")
        assertTrue(far.distance > MatchQuality.CLOSE_MAX, "sixteen steps is approximate: ${far.distance}")

        val list = ShoppingList.of(listOf(same, oneStep, near, far))
        assertEquals(listOf(1, 2, 3, 4), list.map { it.layerNumber })
        assertEquals(listOf(MatchQuality.EXACT, MatchQuality.EXACT, MatchQuality.CLOSE, MatchQuality.APPROXIMATE), list.map { it.quality })
        val first = list[0]
        assertEquals("B", first.brand)
        assertEquals("L", first.line)
        assertEquals("Grey", first.name)
        assertEquals("G", first.code)
        assertEquals("4006381333931", first.gtin)
        assertEquals(listOf(twin), first.equivalents)
        assertFalse(first.sampled, "the shown can's value is official")
        assertEquals(far.distance, list[3].distance)

        val sampled = ShoppingList.of(listOf(PaintLayer(0xFF808080.toInt(), listOf(twin, grey))))
        assertTrue(sampled[0].sampled)
        assertEquals(emptyList(), ShoppingList.of(emptyList()))
    }

    @Test
    fun qualityThresholds() {
        assertEquals(MatchQuality.EXACT, MatchQuality.of(0.0))
        assertEquals(MatchQuality.EXACT, MatchQuality.of(0.01))
        assertEquals(MatchQuality.CLOSE, MatchQuality.of(0.0101))
        assertEquals(MatchQuality.CLOSE, MatchQuality.of(0.05))
        assertEquals(MatchQuality.APPROXIMATE, MatchQuality.of(0.0501))
        assertEquals(MatchQuality.APPROXIMATE, MatchQuality.of(1.0))
    }

    @Test
    fun aLayerRejectsCansOfAnotherColour() {
        val pink = catalogue.named("Shock Pink")
        assertFailsWith<IllegalArgumentException> { PaintLayer(0xFF000000.toInt(), listOf(pink)) }
        assertFailsWith<IllegalArgumentException> { PaintLayer(pink.rgb!!, emptyList()) }
        assertContentEquals(listOf(pink), PaintLayer(pink.rgb!!, listOf(pink)).cans)
    }
}
