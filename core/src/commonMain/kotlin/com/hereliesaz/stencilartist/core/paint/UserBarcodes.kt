package com.hereliesaz.stencilartist.core.paint

/**
 * Barcodes the user has taught the app: a scanned code the catalogue does not know, matched by hand
 * to a can ([Can.canKey]). The app keeps one of these on the device and consults it before the
 * catalogue (see [PaintCatalogue.resolve]), so a correction beats the bundled data.
 *
 * Immutable: [learn] and [forget] return a new map. Barcodes are held in [GtinCheck.canonical]
 * form, so a UPC-A taught once is found again as its EAN-13.
 */
class UserBarcodes private constructor(private val canonicalToKey: Map<String, String>) {

    /** How many barcodes are taught. */
    val size: Int get() = canonicalToKey.size

    /** The key of the can taught for [gtin], or null if none, or if [gtin] is not a valid barcode. */
    fun keyFor(gtin: String): String? {
        val canonical = GtinCheck.canonical(gtin) ?: return null
        return canonicalToKey[canonical]
    }

    /** A copy that also maps [gtin] to the can [canKey]; any earlier mapping of [gtin] is replaced. */
    fun learn(gtin: String, canKey: String): UserBarcodes {
        val canonical = requireNotNull(GtinCheck.canonical(gtin)) { "'$gtin' is not a valid barcode, so it cannot be learned" }
        require(canKey.isNotEmpty() && canKey.none { it == '\t' || it == '\n' || it == '\r' }) {
            "a can key is one non-empty line of text"
        }
        return UserBarcodes(canonicalToKey + (canonical to canKey))
    }

    /** A copy without [gtin]; this itself if it was not taught. */
    fun forget(gtin: String): UserBarcodes {
        val canonical = GtinCheck.canonical(gtin) ?: return this
        return if (canonical in canonicalToKey) UserBarcodes(canonicalToKey - canonical) else this
    }

    /** The map as text to store: one `barcode<TAB>can key` line each, ordered by barcode. */
    fun encode(): String = canonicalToKey.keys.sorted().joinToString("") { "$it\t${canonicalToKey.getValue(it)}\n" }

    override fun equals(other: Any?): Boolean = other is UserBarcodes && other.canonicalToKey == canonicalToKey

    override fun hashCode(): Int = canonicalToKey.hashCode()

    override fun toString(): String = "UserBarcodes($size)"

    companion object {
        /** No barcode taught. */
        val EMPTY = UserBarcodes(emptyMap())

        /**
         * Reads what [encode] wrote. A line that does not parse (damaged storage, an invalid
         * barcode) is skipped instead of failing, so one bad line cannot lose the rest.
         */
        fun decode(text: String): UserBarcodes {
            val map = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.length - 1) continue
                val canonical = GtinCheck.canonical(line.substring(0, tab)) ?: continue
                map[canonical] = line.substring(tab + 1)
            }
            return if (map.isEmpty()) EMPTY else UserBarcodes(map)
        }
    }
}
