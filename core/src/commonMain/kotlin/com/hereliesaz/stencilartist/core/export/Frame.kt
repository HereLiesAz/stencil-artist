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
     * Each shape of [outline] as its rings (outer first) in mm, y down, with the origin at the frame's
     * top-left. Rings are clipped to the tile if there is one; shapes with nothing inside are dropped.
     */
    fun shapes(outline: SheetOutline): List<List<List<Point>>> = outline.shapes.mapNotNull { shape ->
        val rings = ArrayList<List<Point>>()
        for ((i, ring) in shape.rings.withIndex()) {
            var pts = ring.points.map { Point(placement.x(it.x), placement.y(it.y)) }
            val clip = tile?.clip
            if (clip != null) pts = Clip.ring(pts, clip)
            if (pts.size < 3) {
                if (i == 0) return@mapNotNull null
                continue
            }
            rings.add(pts.map { Point(it.x - originX, it.y - originY) })
        }
        rings
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
