package com.hereliesaz.stencilartist.core

/** Which end of the tonal range is the base sheet, the one covering the whole subject. */
enum class Base {
    /** Decide from the image ([Tones.isDarkByArea]). */
    AUTO,

    /** Black base; lighter tones are laid over it. */
    DARK,

    /** White base; darker tones are laid over it. */
    LIGHT,
}

/**
 * The knobs of a tonal separation. The defaults are GraffitiXR's fixed values, so a default
 * [TonalParams] reproduces its stencil.
 *
 * @property sheets how many sheets to cut, 1..256 (one is the bare silhouette).
 * @property smoothing side of the median filter that simplifies brightness before it is split; odd.
 *   Larger merges small features into their surroundings. 1 turns it off.
 * @property closing side of the square that closes each sheet's edge, in pixels. Larger fills wider
 *   notches and gaps. 1 turns it off.
 * @property bias added to every threshold, in brightness levels (-255..255). Positive moves pixels
 *   towards the darker tones, negative towards the lighter.
 * @property base which end is the base sheet.
 * @property colors the sheets' colours in paint order, or null for the grey ramp ([Tones.ramp]).
 *   If given, there must be exactly [sheets] of them.
 */
data class TonalParams(
    val sheets: Int = 3,
    val smoothing: Int = 9,
    val closing: Int = 5,
    val bias: Int = 0,
    val base: Base = Base.AUTO,
    val colors: List<Int>? = null,
) {
    init {
        require(sheets in 1..MAX_SHEETS) { "a tonal stencil has 1..$MAX_SHEETS sheets, not $sheets" }
        require(smoothing >= 1 && smoothing % 2 == 1) { "smoothing must be odd and positive, got $smoothing" }
        require(closing >= 1) { "closing must be positive, got $closing" }
        require(bias in -255..255) { "bias is in brightness levels, -255..255, got $bias" }
        require(colors == null || colors.size == sheets) { "expected $sheets colours, got ${colors?.size}" }
    }

    companion object {
        /**
         * The most sheets a TONAL separation can have: one per level of 8-bit brightness. It is a
         * limit of splitting by tone, not of a stencil; [Separation] has none.
         */
        const val MAX_SHEETS = 256
    }
}

/** A tonal separation, and whether the image was judged dark (dark base) or light. */
class TonalResult(val separation: Separation, val dark: Boolean)

/**
 * Separates an image into sheets by TONE: GraffitiXR's stencil tool, without OpenCV and without
 * its limit of three sheets.
 *
 * The brightness of the image (V of HSV) is simplified with a median filter and split into as many
 * tones as there are sheets ([Tones.thresholds]). The image is judged dark or light
 * ([Tones.isDarkByArea]), which picks the base: the sheet that covers the whole subject. Every
 * further sheet covers its own tone and everything beyond it, and has its edge closed so it can be
 * cut. The sheets are meant to be sprayed in order, each over the last ([Coverage.OVERPAINT]).
 *
 * Transparent pixels are outside the subject: an image whose background has been removed keeps its
 * cut-out, with any hole inside the subject filled in on the base. A fully opaque image is all
 * subject.
 *
 * This is one way to make a [Separation]; a colour separator is another, and shares nothing with
 * this class but that output type.
 */
object TonalSeparator {

    /** Returns null when the image is too small or has no visible pixel to separate. */
    fun separate(image: RgbaImage, params: TonalParams = TonalParams()): TonalResult? {
        val w = image.width
        val h = image.height
        if (w < 2 || h < 2) return null
        val size = w * h

        // GraffitiXR read the bitmap's stored, alpha-premultiplied channels, so a half-transparent
        // edge pixel counted as darker than its colour. Kept, so the split is the same.
        val value = ByteArray(size)
        val observed = BooleanArray(size)
        for (i in 0 until size) {
            val p = image.pixels[i]
            val a = p ushr 24
            val r = Subject.premultiply((p shr 16) and 0xFF, a)
            val g = Subject.premultiply((p shr 8) and 0xFF, a)
            val b = Subject.premultiply(p and 0xFF, a)
            value[i] = maxOf(r, g, b).toByte()
            if (a > 0) observed[i] = true
        }
        val smooth = Raster.medianBlur(value, w, h, params.smoothing)

        val histogram = IntArray(256)
        for (i in 0 until size) if (observed[i]) histogram[smooth[i].toInt() and 0xFF]++
        val thresholds = Tones.thresholds(histogram, params.sheets) ?: return null
        for (i in thresholds.indices) thresholds[i] = (thresholds[i] + params.bias).coerceIn(0, 255)

        val dark = when (params.base) {
            Base.AUTO -> Tones.isDarkByArea(Subject.lumaHistogram(image))
            Base.DARK -> true
            Base.LIGHT -> false
        }

        // The base is the complete silhouette: every pixel the cut-out kept, its gaps closed and its
        // enclosed holes filled.
        val silhouette = Subject.silhouette(observed, w, h)

        // Sheet s (s >= 1) is open where the image is beyond its threshold, moving away from the
        // base: lighter and lighter for a dark image, darker and darker for a light one. A pixel
        // shows the last sheet open over it. Taking the last, not the first that is shut, is what
        // keeps each sheet at least as large as the one after it once their edges are closed.
        val index = IntArray(size) { if (silhouette[it]) 0 else Separation.NONE }
        for (s in 1 until params.sheets) {
            val open = BooleanArray(size) { i ->
                val v = smooth[i].toInt() and 0xFF
                if (dark) v > thresholds[s - 1] else v <= thresholds[params.sheets - 1 - s]
            }
            val closed = Raster.close(open, w, h, params.closing)
            // A filled-in hole was never seen, so it has no tone of its own: it stays on the base.
            for (i in 0 until size) if (closed[i] && observed[i] && silhouette[i]) index[i] = s
        }

        val sheets = params.colors?.map { Sheet(it) } ?: Tones.ramp(params.sheets, dark)
        return TonalResult(Separation(w, h, sheets, index), dark)
    }
}
