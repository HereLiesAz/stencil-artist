package com.hereliesaz.stencilartist.core

/**
 * What every separator agrees on about an image with a cut-out: which pixels are the subject, and how
 * alpha is folded into a colour. It lives apart from [TonalSeparator] so that a separator by colour
 * draws exactly the same silhouette, and so the base sheet of one is the base sheet of the other.
 */
internal object Subject {

    /** Side of the square that closes gaps in the cut-out before its holes are filled. */
    const val GAP_CLOSING = 5

    /**
     * The complete silhouette of a cut-out: every pixel the cut-out kept ([observed], those with any
     * alpha), its gaps closed with a [GAP_CLOSING] square and its enclosed holes filled. This is the
     * base sheet of every separation.
     */
    fun silhouette(observed: BooleanArray, width: Int, height: Int): BooleanArray =
        Raster.fillHoles(Raster.close(observed, width, height, GAP_CLOSING), width, height)

    /** A channel multiplied by its alpha, rounded as Android's bitmaps round it. */
    fun premultiply(channel: Int, alpha: Int): Int {
        if (alpha == 255) return channel
        val product = channel * alpha + 128
        return (product + (product shr 8)) shr 8
    }

    /**
     * A 256-bin histogram of the luminance (scaled to 0..255) of the premultiplied pixels that are
     * not fully transparent, which is what [Tones.isDarkByArea] takes.
     */
    fun lumaHistogram(image: RgbaImage): IntArray {
        val histogram = IntArray(256)
        for (p in image.pixels) {
            val a = p ushr 24
            if (a == 0) continue
            val r = premultiply((p shr 16) and 0xFF, a)
            val g = premultiply((p shr 8) and 0xFF, a)
            val b = premultiply(p and 0xFF, a)
            histogram[(Tones.luma(r, g, b) * 255.0).toInt().coerceIn(0, 255)]++
        }
        return histogram
    }
}
