package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.SheetOutline

/**
 * What an exporter writes out: the artwork, or one [tile] of it, in millimetres with the origin at the
 * top-left of what is written (for a tile, the top-left of its clip rectangle, so the file describes
 * the mat it goes on). [widthMm] and [heightMm] are the extent of the file.
 */
internal class Frame(drawing: Drawing, size: PhysicalSize, val tile: Tile?) {
    val placement = Placement(drawing, size)
    val widthMm: Double = tile?.clip?.width ?: placement.widthMm
    val heightMm: Double = tile?.clip?.height ?: placement.heightMm
    private val originX = tile?.clip?.x0 ?: 0.0
    private val originY = tile?.clip?.y0 ?: 0.0

    /**
     * Each cut region from [outline] as closed rings in mm, y down, relative to this frame.
     * Without tiling, input rings retain their original order and coordinates. For tiles, clip
     * each shape's outer ring AND holes together: one concave shape may yield multiple disconnected
     * outer loops, and a tile wholly inside a hole yields no cut at all. There are no artificial
     * retraced boundary edges in the resulting cutter paths.
     */
    fun shapes(outline: SheetOutline): List<List<List<Point>>> = outline.shapes.mapNotNull { shape ->
        val original = shape.rings.map { ring ->
            ring.points.map { Point(placement.x(it.x), placement.y(it.y)) }
        }
        val rings = tile?.clip?.let { rect ->
            Clip.shape(original.first(), original.drop(1), rect)
        } ?: original
        if (rings.isEmpty()) return@mapNotNull null
        rings.map { ring -> ring.map { Point(it.x - originX, it.y - originY) } }
    }

    /** [y] measured from the top, as measured up from the bottom. */
    fun up(y: Double): Double = heightMm - y

    companion object {
        /** The sheet indices to write: just [sheet], or all of them. */
        fun sheets(drawing: Drawing, sheet: Int?): List<Int> {
            if (sheet == null) return drawing.sheets.indices.toList()
            require(sheet in drawing.sheets.indices) { "no sheet $sheet in a ${drawing.sheets.size}-sheet drawing" }
            return listOf(sheet)
        }
    }
}
