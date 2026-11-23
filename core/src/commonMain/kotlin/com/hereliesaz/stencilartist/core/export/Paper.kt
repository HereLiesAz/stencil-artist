package com.hereliesaz.stencilartist.core.export

/**
 * A sheet of paper. [widthMm] x [heightMm] is how the sheet lies as given: the presets are portrait
 * (width not more than height). [oriented] turns it to portrait or landscape.
 */
data class Paper(val name: String, val widthMm: Double, val heightMm: Double) {
    init {
        require(widthMm > 0.0 && widthMm.isFinite()) { "paper width must be positive, got $widthMm mm" }
        require(heightMm > 0.0 && heightMm.isFinite()) { "paper height must be positive, got $heightMm mm" }
    }

    /** This paper with its longer side across ([landscape]) or down (portrait). */
    fun oriented(landscape: Boolean): Paper {
        val short = minOf(widthMm, heightMm)
        val long = maxOf(widthMm, heightMm)
        return if (landscape) copy(widthMm = long, heightMm = short) else copy(widthMm = short, heightMm = long)
    }

    companion object {
        /** US Letter, 8.5 x 11 in. */
        val US_LETTER = Paper("US Letter", 215.9, 279.4)

        /** US Legal, 8.5 x 14 in. */
        val US_LEGAL = Paper("US Legal", 215.9, 355.6)

        /** US Tabloid, 11 x 17 in. */
        val US_TABLOID = Paper("US Tabloid", 279.4, 431.8)

        /** A4, 210 x 297 mm. */
        val A4 = Paper("A4", 210.0, 297.0)

        /** A3, 297 x 420 mm. */
        val A3 = Paper("A3", 297.0, 420.0)

        /** A2, 420 x 594 mm. */
        val A2 = Paper("A2", 420.0, 594.0)

        /** The presets, in the order a menu lists them. */
        val PRESETS = listOf(US_LETTER, US_LEGAL, US_TABLOID, A4, A3, A2)

        /** Any sheet of [widthMm] by [heightMm]. */
        fun custom(widthMm: Double, heightMm: Double): Paper = Paper("Custom", widthMm, heightMm)
    }
}
