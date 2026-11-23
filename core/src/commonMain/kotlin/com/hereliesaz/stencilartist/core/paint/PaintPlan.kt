package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.ColorSeparator
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.Separation

/**
 * Where a colour stencil's sheet colours come from.
 *
 * The first is today's behaviour and the other two tie every sheet to a real can, so a layer is
 * something the user can buy and spray.
 */
enum class PaintMode {
    /** Any colours: the separator finds them in the image. No cans are involved. */
    FREE,

    /** The user sets how many layers and which brands or lines are allowed; the app picks the cans that best fit the image. */
    PICK_FOR_ME,

    /** The user chooses the cans; the layers are exactly their distinct colours. */
    MY_CANS;

    /** Whether the sheet colours are those of real cans (every mode but [FREE]). */
    val snapped: Boolean get() = this != FREE
}

/**
 * One sheet of a stencil made from cans, in paint terms.
 *
 * @property sheet the sheet's place in the separation (paint order), counting from 0. The layer
 *   number a person reads is `sheet + 1`, which is also the sheet number on every export.
 * @property layer the cans that supply the sheet's colour.
 * @property used whether any pixel of the finished piece shows this colour. A sheet no pixel
 *   shows is empty: its can can stay on the shelf.
 */
data class SnappedLayer(val sheet: Int, val layer: PaintLayer, val used: Boolean)

/** A stencil made from cans: the [separation] and, in sheet order, the [layers] that made it. */
class PaintResult(val separation: Separation, val layers: List<SnappedLayer>)

/** A short name for a can in sentences: its brand and its name, e.g. "Montana Cans Frog Green". */
val Can.displayName: String get() = "$brand $name"

/**
 * The logic of choosing cans for a stencil, kept apart from the screen so it can be tested: which
 * layers a [PaintMode] gives, how the separator is made to use exactly them, which of them an
 * image does not need, and what to put on the shopping list.
 */
object PaintPlan {

    /**
     * The layers for [mode], in the order the cans were chosen or matched (not yet paint order).
     *  - [PaintMode.FREE]: none, because the colours are the separator's to find.
     *  - [PaintMode.PICK_FOR_ME]: [PaintCatalogue.pickFor] on [image] with [pickCount] colours, from
     *    the cans [allowed] (empty means every can). No [image] gives none.
     *  - [PaintMode.MY_CANS]: the distinct colours of [chosen] ([Selection.distinctColours]); a can
     *    with no colour value gives nothing.
     *
     * Empty means "no stencil can be made from this": the caller says why (nothing chosen, or no
     * allowed can has a colour value).
     */
    fun layersFor(
        mode: PaintMode,
        catalogue: PaintCatalogue,
        image: RgbaImage?,
        pickCount: Int,
        allowed: Set<String>,
        chosen: List<Can>,
    ): List<PaintLayer> = when (mode) {
        PaintMode.FREE -> emptyList()
        PaintMode.PICK_FOR_ME ->
            if (image == null) emptyList()
            else catalogue.pickFor(image, pickCount.coerceIn(1, ColorParams.MAX_SHEETS), allowed)
        PaintMode.MY_CANS -> Selection(chosen).distinctColours().take(ColorParams.MAX_SHEETS)
    }

    /**
     * [base] (its smoothing, closing and order) with the sheet colours set to [layers] and the sheet
     * count to their number: the pair [ColorParams] insists on. [layers] must not be empty.
     */
    fun paramsFor(base: ColorParams, layers: List<PaintLayer>): ColorParams {
        require(layers.isNotEmpty()) { "a stencil needs at least one layer" }
        return base.copy(sheets = layers.size, colors = layers.map { it.color })
    }

    /**
     * Separates [image] into exactly the colours of [layers] and says which sheet is which can and
     * which sheets no pixel shows. Null when the image cannot be separated at all (it is too small
     * or has no subject). Layers come back in sheet (paint) order, whatever order they were given in.
     */
    fun separate(image: RgbaImage, base: ColorParams, layers: List<PaintLayer>): PaintResult? {
        val result = ColorSeparator.separate(image, paramsFor(base, layers)) ?: return null
        return PaintResult(result.separation, snap(result.separation, layers))
    }

    /**
     * Pairs every sheet of [separation] with the layer of its colour, in sheet order, marking the
     * sheets that no pixel shows (see [unusedLayers]). A sheet whose colour is none of [layers]
     * (a separation not made from them) is left out.
     */
    fun snap(separation: Separation, layers: List<PaintLayer>): List<SnappedLayer> {
        val byColour = layers.associateBy { it.color and 0xFFFFFF }
        val idle = unusedLayers(separation).toSet()
        return separation.sheets.mapIndexedNotNull { i, sheet ->
            byColour[sheet.color and 0xFFFFFF]?.let { SnappedLayer(i, it, i !in idle) }
        }
    }

    /** The layers that are on the lists: those a pixel shows, and the unused ones the user [kept] (by 24-bit colour). */
    fun listed(layers: List<SnappedLayer>, kept: Set<Int> = emptySet()): List<SnappedLayer> =
        layers.filter { it.used || (it.layer.color and 0xFFFFFF) in kept }

    /** The layers an image does not need and the user has not [kept]: what the screen says is not needed. */
    fun unneeded(layers: List<SnappedLayer>, kept: Set<Int> = emptySet()): List<SnappedLayer> =
        layers.filter { !it.used }.filter { (it.layer.color and 0xFFFFFF) !in kept }

    /** How many layers the stencil really uses: the [listed] ones. This is the count the screen shows. */
    fun layerCount(layers: List<SnappedLayer>, kept: Set<Int> = emptySet()): Int = listed(layers, kept).size

    /** "Layer 3 (Montana Cans Frog Green) isn't needed for this image". */
    fun unusedNote(layer: SnappedLayer): String =
        "Layer ${layer.sheet + 1} (${layer.layer.primary.displayName}) isn't needed for this image"

    /**
     * The shopping list for an export of [sheets] (sheet indices; null for every sheet): one item
     * per [listed] layer among them, in paint order. An item's `layerNumber` is its SHEET number
     * (`sheet + 1`), so it matches the sheet numbers on the printed pages even when an unused
     * layer is left out and the numbers have a gap.
     */
    fun shopping(layers: List<SnappedLayer>, sheets: Collection<Int>? = null, kept: Set<Int> = emptySet()): List<ShoppingItem> {
        val wanted = listed(layers, kept).filter { sheets == null || it.sheet in sheets }
        return ShoppingList.of(wanted.map { it.layer }).mapIndexed { i, item -> item.copy(layerNumber = wanted[i].sheet + 1) }
    }

    /**
     * Sentences naming chosen cans that are the same colour and so make one layer, in the order the
     * colours were first chosen: "Montana Cans X and Loop Colors Y are the same colour: one layer."
     */
    fun sharedColourNotes(chosen: List<Can>): List<String> {
        val groups = LinkedHashMap<Int, MutableList<Can>>()
        for (can in chosen) {
            val rgb = can.rgb ?: continue
            val group = groups.getOrPut(rgb) { ArrayList(2) }
            if (can !in group) group.add(can)
        }
        return groups.values.filter { it.size > 1 }.map { group ->
            val names = group.map { it.displayName }
            val joined = names.dropLast(1).joinToString(", ") + " and " + names.last()
            "$joined are the same colour: one layer."
        }
    }

    /**
     * How many different colours the cans [allowed] offer (empty means all): the most layers
     * [PaintMode.PICK_FOR_ME] can ask for, since no two layers may be the same colour. Cans with no
     * colour value count for nothing.
     */
    fun availableColours(catalogue: PaintCatalogue, allowed: Set<String>): Int {
        val colours = HashSet<Int>()
        for (can in catalogue.cans) {
            val rgb = can.rgb ?: continue
            if (CanFilter.allows(allowed, can)) colours.add(rgb)
        }
        return colours.size
    }
}

/**
 * The brand and line filter of [PaintMode.PICK_FOR_ME], as the set of entries
 * [PaintCatalogue.search] and [PaintCatalogue.bestCans] take: a brand name allows the whole brand
 * and `brand|line` allows one of its lines. The empty set allows everything, which is what "All" is.
 *
 * A brand is active if it, or any of its lines, is in the set. Its line chips show as selected
 * when the whole brand is allowed or the line is named.
 */
object CanFilter {
    /** Whether [can] passes [allowed]; the empty set allows every can. */
    fun allows(allowed: Set<String>, can: Can): Boolean =
        allowed.isEmpty() || can.brand in allowed || (can.brand + "|" + can.line) in allowed

    /** Whether [brand] is allowed whole or in part. */
    fun brandActive(allowed: Set<String>, brand: String): Boolean =
        brand in allowed || allowed.any { it.startsWith("$brand|") }

    /** Whether [line] of [brand] is allowed. */
    fun lineActive(allowed: Set<String>, brand: String, line: String): Boolean =
        brand in allowed || "$brand|$line" in allowed

    /** Taps a brand chip: an active brand (and any of its lines) is removed, an inactive one is allowed whole. */
    fun toggleBrand(allowed: Set<String>, brand: String): Set<String> {
        val rest = allowed.filterTo(LinkedHashSet()) { it != brand && !it.startsWith("$brand|") }
        if (brandActive(allowed, brand)) return rest
        rest.add(brand)
        return rest
    }

    /**
     * Taps a line chip of [brand]. From a whole brand this allows every other line and not this
     * one; from a part of a brand it toggles the line. Allowing every line is the same as allowing
     * the brand, so the set is written that way; allowing none removes the brand.
     */
    fun toggleLine(allowed: Set<String>, catalogue: PaintCatalogue, brand: String, line: String): Set<String> {
        val lines = catalogue.lines(brand)
        val active: Set<String> = when {
            brand in allowed -> lines.toSet()
            else -> lines.filterTo(LinkedHashSet()) { "$brand|$it" in allowed }
        }
        val next = if (line in active) active - line else active + line
        val rest = allowed.filterTo(LinkedHashSet()) { it != brand && !it.startsWith("$brand|") }
        when {
            next.isEmpty() -> {}
            next.size == lines.size -> rest.add(brand)
            else -> for (l in lines) if (l in next) rest.add("$brand|$l")
        }
        return rest
    }
}

/**
 * What the app keeps between runs, as text: the chosen cans, the allowed brands and lines. Cans are
 * stored by [Can.canKey] and resolved through the catalogue when it loads, and a key the catalogue no
 * longer has is dropped without a word, so a catalogue update cannot break a saved choice. (Learned
 * barcodes are stored with [UserBarcodes.encode] and [UserBarcodes.decode].)
 */
object PaintPersistence {
    /** The cans as text: one [Can.canKey] per line, in the order chosen. */
    fun encodeCans(cans: List<Can>): String = cans.joinToString("\n") { it.canKey }

    /**
     * The cans [text] names that [catalogue] still has, in the order stored, each once. Blank lines
     * and keys it does not know are skipped.
     */
    fun decodeCans(text: String, catalogue: PaintCatalogue): List<Can> {
        val out = ArrayList<Can>()
        for (line in text.lineSequence()) {
            val can = catalogue.byKey(line.trimEnd('\r')) ?: continue
            if (can !in out) out.add(can)
        }
        return out
    }

    /** The allowed brands and lines as text, one entry a line, sorted. */
    fun encodeAllowed(allowed: Set<String>): String = allowed.sorted().joinToString("\n")

    /**
     * The entries of [text] that name a brand of [catalogue], or a line of one as `brand|line`. An
     * entry for a brand or line that is gone is dropped.
     */
    fun decodeAllowed(text: String, catalogue: PaintCatalogue): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val entry = raw.trimEnd('\r')
            val bar = entry.indexOf('|')
            if (bar < 0) {
                if (entry in catalogue.brands) out.add(entry)
            } else {
                val brand = entry.substring(0, bar)
                if (entry.substring(bar + 1) in catalogue.lines(brand)) out.add(entry)
            }
        }
        return out
    }
}
