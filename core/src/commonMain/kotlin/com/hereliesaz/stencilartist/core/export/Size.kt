package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point

/**
 * How big the finished stencil is. Only the width is chosen; the height follows the drawing's aspect
 * ratio, so the artwork is never stretched. Millimetres are the unit of everything inside the library.
 */
data class PhysicalSize(val widthMm: Double) {
    init {
        require(widthMm > 0.0 && widthMm.isFinite()) { "width must be positive, got $widthMm mm" }
    }

    /** The width in inches. */
    val widthInches: Double get() = widthMm / MM_PER_INCH

    /** The height in millimetres of [drawing] at this width. */
    fun heightMm(drawing: Drawing): Double = widthMm * drawing.height / drawing.width

    /** The height in inches of [drawing] at this width. */
    fun heightInches(drawing: Drawing): Double = heightMm(drawing) / MM_PER_INCH

    companion object {
        const val MM_PER_INCH = 25.4

        /** A size given as a width in inches. */
        fun inches(widthInches: Double): PhysicalSize = PhysicalSize(widthInches * MM_PER_INCH)
    }
}

/**
 * Maps drawing pixels to millimetres: one uniform scale, origin at the drawing's top-left, y growing
 * downward like the pixels. Formats whose y axis points up (DXF, HPGL, G-code) use the [yUp] variants,
 * which measure from the bottom edge instead.
 */
class Placement(val drawing: Drawing, val size: PhysicalSize) {
    /** Millimetres per source pixel. */
    val mmPerPixel: Double = size.widthMm / drawing.width

    /** The artwork's width in mm. */
    val widthMm: Double = size.widthMm

    /** The artwork's height in mm. */
    val heightMm: Double = size.heightMm(drawing)

    /** [px] pixels to mm along x. */
    fun x(px: Double): Double = px * mmPerPixel

    /** [py] pixels down from the top to mm down from the top. */
    fun y(py: Double): Double = py * mmPerPixel

    /** [py] pixels down from the top to mm UP from the bottom edge. */
    fun yUp(py: Double): Double = heightMm - py * mmPerPixel

    /** [p] in mm, y down. */
    fun point(p: Point): Point = Point(x(p.x), y(p.y))
}
