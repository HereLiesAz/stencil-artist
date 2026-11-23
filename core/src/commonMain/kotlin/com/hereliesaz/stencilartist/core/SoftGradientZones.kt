package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Direction from a painted stencil opening towards the unpainted gradient tail. */
enum class LiftDirection { LEFT, RIGHT, UP, DOWN }

/**
 * An edge where the *source photograph* contains a gradual tonal transition, but the cut sheet
 * currently has a hard boundary. The artist can shim the retained material at this edge to soften
 * the spray. It is an instruction, not an extra cut or an automatic promise of spray coverage.
 */
data class LiftZone(val sheet: Int, val x: Int, val y: Int, val towards: LiftDirection)

/**
 * Locates gradual source-image gradients along actual stencil edges. An edge qualifies only if
 * the luminance change is spread across several samples, not concentrated in one sharp step.
 * This distinguishes a photograph's soft shadow from a crisp silhouette or printed lettering.
 *
 * One highest-contrast example is marked per local grid cell, with no artificial global
 * truncation. Stacks larger than sixteen sheets are left unannotated: scanning every cut
 * edge for hundreds of full-resolution layers would freeze the browser. The PDF's normal full-sheet cutting-note machinery places these on safe stock or
 * on adjacent reference pages; it must never put instructions over the cutting geometry.
 *
 * Only supplied source pixels with substantial alpha are examined. The tool does not infer
 * gradients from flat stencil masks, or invent a blur direction where none was measured.
 */
object SoftGradientZones {
    fun locate(source: RgbaImage, stencil: Separation, cellSize: Int = 64): List<LiftZone> {
        require(source.width == stencil.width && source.height == stencil.height)
        require(cellSize >= 8)
        val width = source.width
        val height = source.height
        if (width < 11 || height < 11 || stencil.sheets.size > 16) return emptyList()
        val samples = IntArray(source.pixels.size)
        val visible = BooleanArray(samples.size)
        for (i in samples.indices) {
            val p = source.pixels[i]
            visible[i] = p ushr 24 >= 224
            samples[i] = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 +
                (p and 255) * 114 + 500) / 1000
        }
        val columns = (width + cellSize - 1) / cellSize
        val rows = (height + cellSize - 1) / cellSize
        val zones = ArrayList<LiftZone>()
        // One immutable set of edge directions; no per-pixel object allocations.
        val edges = arrayOf(
            Triple(1, 0, LiftDirection.RIGHT),
            Triple(-1, 0, LiftDirection.LEFT),
            Triple(0, 1, LiftDirection.DOWN),
            Triple(0, -1, LiftDirection.UP),
        )
        // A representative from each spatial region is more useful than a label on each
        // pixel along a long gradient. There is no dependence on the number of colours.
        for (sheet in 0 until stencil.sheets.size) {
            val mask = stencil.mask(sheet)
            val scores = IntArray(columns * rows) { -1 }
            val best = arrayOfNulls<LiftZone>(columns * rows)
            for (y in 5 until height - 5) {
                for (x in 5 until width - 5) {
                    val p = y * width + x
                    if (!mask[p]) continue
                    for ((dx, dy, dir) in edges) {
                        if (mask[p + dy * width + dx]) continue
                        var lo = 255
                        var hi = 0
                        var rising = 0
                        var falling = 0
                        var largestStep = 0
                        var last = -1
                        var valid = true
                        for (k in -4..4) {
                            val q = (y + k * dy) * width + x + k * dx
                            if (!visible[q]) { valid = false; break }
                            val v = samples[q]
                            lo = min(lo, v)
                            hi = max(hi, v)
                            if (last >= 0) {
                                val difference = v - last
                                if (difference >= 2) rising++
                                if (difference <= -2) falling++
                                largestStep = max(largestStep, abs(difference))
                            }
                            last = v
                        }
                        val range = hi - lo
                        if (!valid || range < 36 || largestStep * 2 >= range ||
                            max(rising, falling) < 5) continue
                        val cell = (y / cellSize) * columns + (x / cellSize)
                        val score = range - largestStep * 2
                        if (score > scores[cell]) {
                            scores[cell] = score
                            best[cell] = LiftZone(sheet, x, y, dir)
                        }
                    }
                }
            }
            for (zone in best) if (zone != null) zones += zone
        }
        return zones
    }
}
