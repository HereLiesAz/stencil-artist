package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.PaletteColor

/**
 * User-supplied, device-local can colours. A can is identified by its stable [Can.canKey], never
 * its position in a catalogue. Values are opaque sRGB approximations, not manufacturer claims.
 *
 * Immutable: setting or clearing a colour makes a new map. The bundled catalogue is never edited.
 * This supports cans whose source provides a name and barcode but no redistributable RGB data.
 */
class UserCanColours private constructor(private val byKey: Map<String, Int>) {
    val size: Int get() = byKey.size

    /** User colour for [canKey], if present. */
    fun colourFor(canKey: String): Int? = byKey[canKey]

    /** A new map with [argb] associated with [canKey]. */
    fun withColour(canKey: String, argb: Int): UserCanColours {
        require(canKey.isNotEmpty() && canKey.none { it == '\t' || it == '\n' || it == '\r' }) {
            "a can key must be one non-empty line"
        }
        require(argb ushr 24 == 0xFF) { "user can colours must be opaque ARGB" }
        return UserCanColours(byKey + (canKey to argb))
    }

    /** A new map without [canKey], or this map if no colour was saved for that can. */
    fun withoutColour(canKey: String): UserCanColours =
        if (canKey in byKey) UserCanColours(byKey - canKey) else this

    /** Apply a local value to [can], marking its origin without changing its stable identity. */
    fun applyTo(can: Can): Can = colourFor(can.canKey)?.let {
        can.copy(rgb = it, hexSource = HexSource.USER)
    } ?: can

    /** Deterministic, tab-separated text for the local platform store; no source artwork is copied. */
    fun encode(): String = byKey.keys.sorted().joinToString("") { key ->
        "$key\t${PaletteColor.hex(byKey.getValue(key))}\n"
    }

    override fun equals(other: Any?): Boolean = other is UserCanColours && byKey == other.byKey
    override fun hashCode(): Int = byKey.hashCode()
    override fun toString(): String = "UserCanColours($size)"

    companion object {
        val EMPTY = UserCanColours(emptyMap())

        /** Malformed entries are ignored; one damaged line does not discard the rest. */
        fun decode(text: String): UserCanColours {
            val parsed = LinkedHashMap<String, Int>()
            for (line in text.lineSequence()) {
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                if (key.any { it == '\t' || it == '\r' }) continue
                val colour = PaletteColor.parseHex(line.substring(tab + 1)) ?: continue
                parsed[key] = colour
            }
            return if (parsed.isEmpty()) EMPTY else UserCanColours(parsed)
        }
    }
}
