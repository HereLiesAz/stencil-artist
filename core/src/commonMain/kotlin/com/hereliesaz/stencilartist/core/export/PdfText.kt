package com.hereliesaz.stencilartist.core.export

/**
 * Text for the hand-written PDFs: the standard Helvetica fonts in WinAnsi encoding. Printable ASCII is
 * written as it is. The Latin-1 letters and symbols (accents such as e-acute, n-tilde, u-umlaut) and the
 * few typographic marks WinAnsi adds (curly quotes, dashes, the euro sign) are written as octal escapes
 * (`\351`), so the file itself stays 7-bit ASCII and a string index is still a byte offset. The Latin
 * Extended-A letters WinAnsi lacks (Polish l-stroke, Czech c-caron and the like) are folded to their plain
 * ASCII letter; anything else (Greek, Cyrillic, CJK, emoji) becomes "?". Widths come from Helvetica's
 * published metrics (bold is taken as 10% wider, which errs on the safe side), so lines can be wrapped
 * and boxed without a font engine.
 */
internal object PdfText {
    // Helvetica advance widths in 1/1000 em for the characters 32 (space) to 126 (~).
    private val WIDTHS = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    // The ASCII letter whose width stands for each Latin-1 letter 0xC0..0xFF (accents do not change an advance).
    private const val LATIN1_BASE = "AAAAAAWCEEEEIIIIDNOOOOO+OUUUUYPB" + "aaaaaamceeeeiiiionooooo+ouuuuypy"

    // The plain ASCII letter for each Latin Extended-A character U+0100..U+017F; "?" where WinAnsi has the letter itself.
    private const val EXT_A_BASE =
        "AaAaAaCcCcCcCcDd" + "DdEeEeEeEeEeGgGg" + "GgGgHhHhIiIiIiIi" + "IiIiJjKkkLlLlLlL" +
            "lLlNnNnNnnNnOoOo" + "Oo??RrRrRrSsSsSs" + "??TtTtTtUuUuUuUu" + "UuUuWwYy?ZzZz??s"

    /** The WinAnsi codes 0x80..0x9F that Unicode places elsewhere, by character. */
    private fun winAnsiSpecial(c: Char): Int = when (c.code) {
        0x20AC -> 0x80
        0x201A -> 0x82
        0x2026 -> 0x85
        0x2018 -> 0x91
        0x2019 -> 0x92
        0x201C -> 0x93
        0x201D -> 0x94
        0x2022 -> 0x95
        0x2013 -> 0x96
        0x2014 -> 0x97
        0x2122 -> 0x99
        0x0160 -> 0x8A
        0x0152 -> 0x8C
        0x017D -> 0x8E
        0x0161 -> 0x9A
        0x0153 -> 0x9C
        0x017E -> 0x9E
        0x0178 -> 0x9F
        else -> -1
    }

    /**
     * The single byte WinAnsi gives [c], 32..255, or -1 when the font has no such character. The no-break space
     * becomes a plain space and the soft hyphen a hyphen, as neither should show.
     */
    internal fun winAnsi(c: Char): Int {
        val code = c.code
        return when {
            code in 32..126 -> code
            code == 0xA0 -> 32
            code == 0xAD -> 45
            code in 0xA1..0xFF -> code
            else -> winAnsiSpecial(c)
        }
    }

    /**
     * [c] as plain ASCII where the font has no glyph for it: the base letter of a Latin Extended-A letter, or
     * null for a character that has no sensible ASCII form.
     */
    internal fun fold(c: Char): Char? {
        val i = c.code - 0x100
        if (i !in 0..0x7F) return null
        val f = EXT_A_BASE[i]
        return if (f == '?') null else f
    }

    private fun advance(c: Char): Int {
        val code = c.code
        if (code in 32..126) return WIDTHS[code - 32]
        return when (val b = winAnsi(c)) {
            32 -> 278
            in 0xC0..0xFF -> WIDTHS[LATIN1_BASE[b - 0xC0].code - 32]
            0x91, 0x92 -> 222
            0x93, 0x94, 0xB4 -> 333
            0x96 -> 556
            0x97, 0x85, 0x99, 0x8C -> 1000
            0x95 -> 350
            -1 -> fold(c)?.let { WIDTHS[it.code - 32] } ?: 556
            else -> 556
        }
    }

    /** The width in points of [text] at [sizePt]. */
    fun width(text: String, sizePt: Double, bold: Boolean = false): Double {
        var sum = 0
        for (c in text) sum += advance(c)
        return sum * sizePt / 1000.0 * (if (bold) 1.1 else 1.0)
    }

    /**
     * [text] as the inside of a PDF string, in ASCII: printable ASCII as it is (brackets and backslash escaped),
     * characters WinAnsi has above 127 as three-digit octal escapes, Latin Extended-A letters folded to their
     * plain letter, anything else "?".
     */
    fun escape(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val b = winAnsi(c)
            when {
                b in 32..126 -> {
                    if (b == '('.code || b == ')'.code || b == '\\'.code) sb.append('\\')
                    sb.append(b.toChar())
                }
                b > 126 -> sb.append('\\').append(((b shr 6) and 7).digit()).append(((b shr 3) and 7).digit()).append((b and 7).digit())
                else -> sb.append(fold(c) ?: '?')
            }
        }
        return sb.toString()
    }

    private fun Int.digit(): Char = '0' + this

    /** [text] broken into lines no wider than [maxWidthPt] at [sizePt], at spaces. A word wider than a line stays whole. */
    fun wrap(text: String, maxWidthPt: Double, sizePt: Double, bold: Boolean = false): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && width(tryLine, sizePt, bold) > maxWidthPt) {
                lines += line
                line = word
            } else line = tryLine
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}
