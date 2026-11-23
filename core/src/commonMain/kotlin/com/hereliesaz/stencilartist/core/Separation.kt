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
 * [mask] and [render]. Legacy index-based sheets use nested masks. Independent cut masks may
 * intentionally repaint earlier colours, including areas cut away as structural bridges; their
 * finished pixel still belongs to the latest sheet that paints it. Consumers must never derive
 * cut geometry directly from [index].
 */
class Separation(
    val width: Int,
    val height: Int,
    val sheets: List<Sheet>,
    val index: IntArray,
    private val independentMasks: List<BooleanArray>? = null,
    /** Local soft-edge locations for controlled lift-and-overspray guidance. */
    val liftZones: List<LiftZone> = emptyList(),
) {
    init {
        require(index.size == width * height) { "expected ${width * height} pixels, got ${index.size}" }
        require(independentMasks == null || (independentMasks.size == sheets.size &&
            independentMasks.all { it.size == index.size })) { "independent mask count or dimensions differ from the sheets" }
    }

    /** Whether the cut of sheet [sheet] is open over pixel [pixel]. */
    fun covers(sheet: Int, pixel: Int, coverage: Coverage = Coverage.OVERPAINT): Boolean {
        val shown = index[pixel]
        if (independentMasks != null) return when (coverage) {
            Coverage.OVERPAINT -> independentMasks[sheet][pixel]
            Coverage.EXCLUSIVE -> shown == sheet && independentMasks[sheet][pixel]
        }
        if (shown == NONE) return false
        return when (coverage) {
            Coverage.OVERPAINT -> shown >= sheet
            Coverage.EXCLUSIVE -> shown == sheet
        }
    }

    /** The cut of sheet [sheet] as a mask: true where paint goes through. */
    fun mask(sheet: Int, coverage: Coverage = Coverage.OVERPAINT): BooleanArray {
        require(sheet in sheets.indices) { "no sheet $sheet in a ${sheets.size}-sheet stencil" }
        if (coverage == Coverage.OVERPAINT && independentMasks != null) return independentMasks[sheet].copyOf()
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

    /** Attach gradient guidance without changing a single opening or its finished colour. */
    fun withLiftZones(zones: List<LiftZone>): Separation = Separation(
        width, height, sheets.toList(), index.copyOf(), independentMasks?.map { it.copyOf() }, zones.toList(),
    )

    companion object {
        /** [index] value of a pixel no sheet paints. */
        const val NONE = -1

        /**
         * Builds a separation from independent, aligned paint openings in spraying order.
         * Later paint may overlap or extend beyond earlier openings, unlike index-derived
         * nested masks. This is necessary for stroke-shaped bridges and cross-layer repairs:
         * the finished visible colour alone cannot describe where each sheet was cut.
         *
         * Each input mask is copied so subsequent editing cannot silently change the plan.
         * Unpainted pixels remain transparent; the last opening to cover a pixel is visible.
         */
        fun fromMasks(
            width: Int,
            height: Int,
            sheets: List<Sheet>,
            masks: List<BooleanArray>,
            liftZones: List<LiftZone> = emptyList(),
        ): Separation {
            require(width >= 0 && height >= 0) { "negative stencil dimensions" }
            require(masks.size == sheets.size) { "expected ${sheets.size} masks, got ${masks.size}" }
            val size = width * height
            require(masks.all { it.size == size }) { "independent masks must be ${width}x$height" }
            val copies = masks.map { it.copyOf() }
            val index = IntArray(size) { NONE }
            for (sheet in copies.indices) for (pixel in 0 until size) {
                if (copies[sheet][pixel]) index[pixel] = sheet
            }
            return Separation(width, height, sheets.toList(), index, copies, liftZones.toList())
        }
    }
}
