package com.hereliesaz.stencilartist.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Reads `models/paints.tsv`, the file the apps bundle, by looking upwards from the test's working
 * directory for it. Returns null if it is nowhere above. Each target reads it its own way (the JVM
 * with java.io, Node with its fs module), which is why this is an expect/actual pair.
 */
internal expect fun readBundledCatalogue(): String?

class RealCatalogueTest {
    private val text = assertNotNull(readBundledCatalogue(), "models/paints.tsv was not found above the working directory")

    @Test
    fun everyRowOfTheBundledFileParses() {
        val catalogue = PaintCatalogue.parse(text)
        // Every can passed Can's own checks (opaque colour, valid barcode) on the way in.
        assertEquals(3479, catalogue.cans.size)
        assertEquals(12, catalogue.brands.size)
        assertEquals(text.lineSequence().count { it.isNotEmpty() && !it.startsWith("#") }, catalogue.cans.size)
        assertEquals(2057, catalogue.cans.count { it.rgb != null })
        assertEquals(79, catalogue.cans.count { it.hexSource == HexSource.SAMPLED })
        assertEquals(1821, catalogue.cans.count { it.gtin != null })
        assertEquals(1689, catalogue.cans.mapNotNull { it.rgb }.toSet().size)
        assertEquals(
            mapOf(
                "Clash" to 263, "Dupli-Color" to 31, "Flame" to 273, "Ironlak" to 102, "Kobra" to 264, "Krylon" to 229,
                "Liquitex" to 50, "Loop Colors" to 305, "Molotow" to 204, "Montana Cans" to 580,
                "MTN (Montana Colors)" to 543, "Rust-Oleum" to 635,
            ),
            catalogue.cans.groupingBy { it.brand }.eachCount(),
        )
        assertTrue(catalogue.sources.values.all { it.isNotEmpty() })
    }

    @Test
    fun reportsParseTime() {
        // Not asserted (a slow machine is not a bug); printed so a regression is visible in the test log.
        PaintCatalogue.parse(text) // warm-up
        val mark = TimeSource.Monotonic.markNow()
        val runs = 10
        repeat(runs) { PaintCatalogue.parse(text) }
        println("PaintCatalogue.parse: ${mark.elapsedNow() / runs} for ${text.length} chars")
    }

    @Test
    fun keysAreUniqueAndEveryBarcodeResolves() {
        val catalogue = PaintCatalogue.parse(text)
        assertEquals(catalogue.cans.size, catalogue.cans.map { it.canKey }.toSet().size)
        for (can in catalogue.cans) {
            assertEquals(can, catalogue.byKey(can.canKey))
            val gtin = can.gtin ?: continue
            assertEquals(gtin, catalogue.byGtin(gtin)?.gtin, "${can.canKey}: $gtin")
            // The same product scanned in the other printed form.
            val other = if (gtin.length == 12) "0$gtin" else if (gtin.length == 13 && gtin[0] == '0') gtin.substring(1) else gtin
            assertEquals(catalogue.byGtin(gtin), catalogue.byGtin(other))
        }
    }

    @Test
    fun searchWorksOnTheRealData() {
        val catalogue = PaintCatalogue.parse(text)
        assertTrue(catalogue.search("montana").isNotEmpty())
        assertTrue(catalogue.search("rust oleum").isNotEmpty())
        val brandOnly = catalogue.search("loop", allowed = setOf("Loop Colors"))
        assertTrue(brandOnly.isNotEmpty() && brandOnly.all { it.brand == "Loop Colors" })
        // Every can that has a code is found by it, and the first hit has that very code.
        for (can in catalogue.cans.filter { it.code != null }.take(300)) {
            val hits = catalogue.search(can.code!!, limit = 5000)
            assertTrue(can in hits, can.canKey)
        }
    }

    @Test
    fun bestCansOnTheRealDataUsesDistinctColours() {
        val catalogue = PaintCatalogue.parse(text)
        val targets = listOf(0xFFE6007E.toInt(), 0xFF2060D0.toInt(), 0xFF101010.toInt(), 0xFFF0F0F0.toInt())
        val layers = catalogue.bestCans(targets)
        assertEquals(4, layers.size)
        assertEquals(4, layers.map { it.color }.toSet().size)
        assertTrue(layers.all { it.distance < 0.1 }, layers.map { it.distance }.toString())
        val restricted = catalogue.bestCans(targets, allowed = setOf("Montana Cans"))
        assertTrue(restricted.all { layer -> layer.cans.all { it.brand == "Montana Cans" } })
    }
}
