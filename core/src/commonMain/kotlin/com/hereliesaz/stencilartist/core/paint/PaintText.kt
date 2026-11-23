package com.hereliesaz.stencilartist.core.paint

/**
 * How catalogue text is compared: case, accents and punctuation do not matter. "Rouge Écarlate",
 * "ROUGE ECARLATE" and "rouge-ecarlate" are one thing; "RV-252", "rv 252" and "rv252" are one code.
 */
internal object PaintText {
    private val accents = listOf(
        'a' to "àáâãäåāăą", 'c' to "çćĉċč", 'd' to "ďđ", 'e' to "èéêëēĕėęě", 'g' to "ĝğġģ", 'h' to "ĥħ",
        'i' to "ìíîïĩīĭįı", 'j' to "ĵ", 'k' to "ķ", 'l' to "ĺļľłŀ", 'n' to "ñńņň", 'o' to "òóôõöøōŏő",
        'r' to "ŕŗř", 's' to "śŝşš", 't' to "ţťŧ", 'u' to "ùúûüũūŭůűų", 'w' to "ŵ", 'y' to "ýÿŷ", 'z' to "źżž",
    )

    /** Each lower-case Latin-1 and Latin Extended-A letter's plain form; every other character maps to itself. */
    private val plain = CharArray(0x180) { it.toChar() }.also { table ->
        for ((letter, accented) in accents) for (c in accented) table[c.code] = letter
    }

    private fun fold(c: Char): Char {
        val lower = c.lowercaseChar()
        return if (lower.code < plain.size) plain[lower.code] else lower
    }

    /**
     * [text] lower-cased, without accents, as words of letters and digits separated by single
     * spaces. Anything else is a separator; a combining accent on its own is dropped.
     */
    fun normalise(text: String): String {
        val out = StringBuilder(text.length)
        var gap = false
        for (ch in text) {
            if (ch.code in 0x300..0x36F) continue
            val c = fold(ch)
            val expansion = when (c) {
                'ß' -> "ss"
                'æ' -> "ae"
                'œ' -> "oe"
                else -> null
            }
            if (expansion == null && !c.isLetterOrDigit()) {
                gap = true
                continue
            }
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            if (expansion != null) out.append(expansion) else out.append(c)
        }
        return out.toString()
    }

    /** [normalise]d [text] with the spaces removed, which is how codes are compared. */
    fun compact(text: String): String {
        val n = normalise(text)
        return if (n.indexOf(' ') < 0) n else n.replace(" ", "")
    }
}
