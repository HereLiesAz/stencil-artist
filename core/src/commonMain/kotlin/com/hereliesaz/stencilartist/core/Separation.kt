package com.hereliesaz.stencilartist.core

/**
 * One sheet of a stencil: a single cut, sprayed in a single [color] (straight-alpha ARGB). A sheet
 * is not assumed to be a grey, and nothing downstream may assume it is.
 */
data class Sheet(val color: Int)

/**
 * How a sheet's cut relates to the sheets painted after it.
 *  - [OVERPAINT]: sheets are sprayed in order onto the same surface, each over the last, so a sheet
 *    also covers everything that later sheets will cover. The first sheet is the whole subject.
 *    This is how GraffitiXR's tonal stencil is laid, and it is why its sheets need no bridges
 *    between tones.
 *  - [EXCLUSIVE]: a sheet covers only the pixels that end up its own colour.
 */
enum class Coverage { OVERPAINT, EXCLUSIVE }

/**
 * An image separated into sheets.
 *
 * [sheets] are in PAINT ORDER: `sheets[0]` goes down first. There may be any number of them, in any
 * colours. [index] holds, for every pixel, the position in [sheets] of the sheet whose colour the
 * finished piece shows there, or [NONE] where the piece is not painted at all (outside the subject).
 *
 * Every consumer (preview, PNG, vectoriser, cutter export) reads a separation through [covers],
 * [mask] and [render], so how the separation was made (by tone today, by colour later) is not its
 * concern.
 */
class Separation(
    val width: Int,
    val height: Int,
    val sheets: List<Sheet>,
    val index: IntArray,
) {
    init {
        require(index.size == width * height) { "expected ${width * height} pixels, got ${index.size}" }
    }

    /** Whether the cut of sheet [sheet] is open over pixel [pixel]. */
    fun covers(sheet: Int, pixel: Int, coverage: Coverage = Coverage.OVERPAINT): Boolean {
        val shown = index[pixel]
        if (shown == NONE) return false
        return when (coverage) {
            Coverage.OVERPAINT -> shown >= sheet
            Coverage.EXCLUSIVE -> shown == sheet
        }
    }

    /** The cut of sheet [sheet] as a mask: true where paint goes through. */
    fun mask(sheet: Int, coverage: Coverage = Coverage.OVERPAINT): BooleanArray {
        require(sheet in sheets.indices) { "no sheet $sheet in a ${sheets.size}-sheet stencil" }
        return BooleanArray(index.size) { covers(sheet, it, coverage) }
    }

    /**
     * ARGB pixels of the stencil. With [sheet] null, the finished piece: every pixel in the colour of
     * the sheet it shows. With a sheet, that one sheet alone, in its colour, the rest transparent.
     * [NONE] is always transparent.
     */
    fun render(sheet: Int? = null, coverage: Coverage = Coverage.OVERPAINT): IntArray {
        if (sheet == null) return IntArray(index.size) { if (index[it] == NONE) 0 else sheets[index[it]].color }
        require(sheet in sheets.indices) { "no sheet $sheet in a ${sheets.size}-sheet stencil" }
        val color = sheets[sheet].color
        return IntArray(index.size) { if (covers(sheet, it, coverage)) color else 0 }
    }

    companion object {
        /** [index] value of a pixel no sheet paints. */
        const val NONE = -1
    }
}
