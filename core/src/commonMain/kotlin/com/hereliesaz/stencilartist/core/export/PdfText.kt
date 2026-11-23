package com.hereliesaz.stencilartist.core.export

/**
 * Text for the hand-written PDFs: the standard Helvetica fonts, ASCII only. Widths come from
 * Helvetica's published metrics (bold is taken as 10% wider, which errs on the safe side), so lines can
 * be wrapped and boxed without a font engine.
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

    /** The width in points of [text] at [sizePt]. */
    fun width(text: String, sizePt: Double, bold: Boolean = false): Double {
        var sum = 0
        for (c in text) sum += if (c.code in 32..126) WIDTHS[c.code - 32] else 556
        return sum * sizePt / 1000.0 * (if (bold) 1.1 else 1.0)
    }

    /** [text] as the inside of a PDF string: anything but printable ASCII becomes "?", brackets are escaped. */
    fun escape(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c.code !in 32..126) sb.append('?') else {
                if (c == '(' || c == ')' || c == '\\') sb.append('\\')
                sb.append(c)
            }
        }
        return sb.toString()
    }

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
