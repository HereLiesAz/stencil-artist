package com.hereliesaz.stencilartist.core.vector

import com.hereliesaz.stencilartist.core.Coverage
import com.hereliesaz.stencilartist.core.Separation

/**
 * How a mask becomes cut paths. [tolerance] is the Douglas-Peucker distance in pixels, [minArea]
 * removes specks and pinholes smaller than that many pixels, and [smoothing] is the number of Chaikin
 * passes (0 keeps hard corners). The defaults suit a working image of up to 1600 pixels.
 */
data class VectorOptions(
    val tolerance: Double = 0.75,
    val minArea: Double = 4.0,
    val smoothing: Int = 1,
) {
    init {
        require(tolerance >= 0.0 && tolerance.isFinite()) { "tolerance must be >= 0, got $tolerance" }
        require(minArea >= 0.0 && minArea.isFinite()) { "minArea must be >= 0, got $minArea" }
        require(smoothing in 0..Smooth.MAX_ITERATIONS) { "smoothing must be 0..${Smooth.MAX_ITERATIONS}, got $smoothing" }
    }
}

/**
 * Turns a [Separation] into a [Drawing]: [Tracer], then [Simplify], then [Smooth], one sheet at a time,
 * so only one sheet's mask is alive however many sheets there are. Colours are carried over untouched,
 * alpha included. No bridges are added: an island is a hole's interior, left for a later phase.
 */
object Vectorizer {
    /** Every sheet of [separation], in paint order, one [SheetOutline] each. */
    fun vectorize(
        separation: Separation,
        coverage: Coverage = Coverage.OVERPAINT,
        options: VectorOptions = VectorOptions(),
    ): Drawing = Drawing(
        separation.width.toDouble(),
        separation.height.toDouble(),
        separation.sheets.indices.map { vectorizeSheet(separation, it, coverage, options) },
    )

    /** Just sheet [sheet], for exporting one sheet without vectorising the rest. */
    fun vectorizeSheet(
        separation: Separation,
        sheet: Int,
        coverage: Coverage = Coverage.OVERPAINT,
        options: VectorOptions = VectorOptions(),
    ): SheetOutline {
        require(sheet in separation.sheets.indices) { "no sheet $sheet in a ${separation.sheets.size}-sheet stencil" }
        val traced = Tracer.trace(separation.mask(sheet, coverage), separation.width, separation.height)
        val shapes = Simplify.shapes(traced, options.tolerance, options.minArea)
            .map { Smooth.shape(it, options.smoothing) }
        return SheetOutline(separation.sheets[sheet].color, shapes)
    }
}
