package com.hereliesaz.stencilartist.core.paint

/** Whose measurement a can's colour value is. */
enum class HexSource {
    /** The maker published the value. */
    OFFICIAL,

    /** We sampled it from the maker's swatch image, so it is our own measurement, not a published figure. */
    SAMPLED,

    /** The user entered this approximate RGB locally; not a maker or app-supplied value. */
    USER,
}

/**
 * One spray can in the catalogue: a colour of a [line] of a [brand].
 *
 * @property brand the maker, e.g. "Montana Cans".
 * @property line the product line the colour belongs to, e.g. "GOLD 400ml Colors". A line is only
 *   unique within its brand.
 * @property name the colour's name as the maker gives it.
 * @property finish the sheen or effect ("Gloss", "Metallic"), or null when the source does not say.
 * @property code the maker's colour code or SKU, or null when there is none.
 * @property rgb the colour as an OPAQUE ARGB int (alpha 0xFF), ready to be a sheet colour, or null
 *   when no value is known (effect finishes, makers whose sites could not be read). It is how a
 *   screen approximates the paint, not a measurement of the paint.
 * @property hexSource whose value [rgb] is; null exactly when [rgb] is null.
 * @property gtin the barcode as printed (8, 12, 13 or 14 digits, check digit valid), or null.
 */
data class Can(
    val brand: String,
    val line: String,
    val name: String,
    val finish: String?,
    val code: String?,
    val rgb: Int?,
    val hexSource: HexSource?,
    val gtin: String?,
) {
    init {
        require(brand.isNotEmpty() && line.isNotEmpty() && name.isNotEmpty()) {
            "a can needs a brand, a line and a name, got '$brand' / '$line' / '$name'"
        }
        require((rgb == null) == (hexSource == null)) { "$name has a colour or a source of it, but not both" }
        require(rgb == null || rgb ushr 24 == 0xFF) { "$name: the colour must be opaque ARGB, got ${rgb?.toUInt()?.toString(16)}" }
        require(gtin == null || GtinCheck.isValid(gtin)) { "$name: '$gtin' is not a valid barcode" }
    }

    /**
     * A string that names this can for good: brand, line, finish and the code (or the name, when
     * there is no code) joined by `|`. It is made of the can's own text only, never of its position
     * in the catalogue, so it is the same after the catalogue is parsed again. Selections, shopping
     * lists and learned barcodes all refer to a can by it. The bundled catalogue has no two cans with
     * the same key; the build refuses to write one.
     */
    val canKey: String
        get() = "$brand|$line|${finish.orEmpty()}|${if (code.isNullOrEmpty()) name else code}"
}
