package com.hereliesaz.stencilartist.core.paint

/**
 * How near a can's colour is to the colour it stands in for, as one word for a shopping list.
 * Judged by the Oklab distance ([PaintColour.distance]), where about 0.02 is the smallest difference
 * a person sees side by side.
 */
enum class MatchQuality {
    /** Within [EXACT_MAX]: the same colour to the eye, a single 8-bit step or none. */
    EXACT,

    /** Within [CLOSE_MAX]: a difference you can see if you look for it, the same colour at a glance. */
    CLOSE,

    /** Further than [CLOSE_MAX]: the nearest the chosen paints come, but noticeably different. */
    APPROXIMATE;

    companion object {
        /** The largest distance still [EXACT]. */
        const val EXACT_MAX = 0.01

        /** The largest distance still [CLOSE]. */
        const val CLOSE_MAX = 0.05

        /** The quality of a match [distance] apart. */
        fun of(distance: Double): MatchQuality = when {
            distance <= EXACT_MAX -> EXACT
            distance <= CLOSE_MAX -> CLOSE
            else -> APPROXIMATE
        }
    }
}

/**
 * One line of the shopping list: what to buy for one layer.
 *
 * @property layerNumber the layer's place in the paint order, counting from 1.
 * @property can the can to buy.
 * @property equivalents other cans of exactly the same colour value that would do as well.
 * @property distance Oklab distance from [can]'s colour to the colour it stands in for.
 * @property quality [distance] as a word.
 * @property sampled true if the can's colour value is our own sampling rather than the maker's, so
 *   the list should say the colour is less certain.
 */
data class ShoppingItem(
    val layerNumber: Int,
    val can: Can,
    val equivalents: List<Can>,
    val distance: Double,
    val quality: MatchQuality,
    val sampled: Boolean,
) {
    val brand: String get() = can.brand
    val line: String get() = can.line
    val name: String get() = can.name
    val code: String? get() = can.code
    val gtin: String? get() = can.gtin
}

/** The shopping list for the PDF instructions page and the screen. */
object ShoppingList {
    /** One [ShoppingItem] per layer, in the order given, which is the paint order, numbered from 1. */
    fun of(layers: List<PaintLayer>): List<ShoppingItem> = layers.mapIndexed { i, layer ->
        val distance = layer.distance
        ShoppingItem(
            layerNumber = i + 1,
            can = layer.primary,
            equivalents = layer.equivalents,
            distance = distance,
            quality = MatchQuality.of(distance),
            sampled = layer.primary.hexSource == HexSource.SAMPLED,
        )
    }
}
