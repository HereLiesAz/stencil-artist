package com.hereliesaz.stencilartist.core

/** A physical cutting problem, anchored to a position in the original stencil image. */
enum class PrintTipKind { ISLAND, FINE_CUT, SOFT_GRADIENT }

/** Pixel coordinates are in the full stencil, never in a cropped print tile. */
data class PrintTip(val kind: PrintTipKind, val x: Int, val y: Int, val direction: LiftDirection? = null)

/**
 * Finds physical cutting problems without modifying the cuts.
 *
 * An island is an uncut four-connected region that cannot reach the image boundary, so it would
 * fall out once the surrounding opening is removed. Fine cuts are separate openings whose
 * narrowest bounding dimension is under the requested physical threshold. The same full-sheet
 * analysis is used for every tile, avoiding false islands created by a page boundary.
 * A tip is anchored to a pixel belonging to its own component, never an empty bounding box center.
 */
object StencilPrintTips {
    fun detect(
        mask: BooleanArray,
        width: Int,
        height: Int,
        physicalWidthMm: Double,
        fineCutMm: Double = 1.5,
        maxTips: Int = Int.MAX_VALUE,
    ): List<PrintTip> {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(physicalWidthMm > 0.0 && fineCutMm > 0.0)
        require(maxTips >= 0)
        val mmPerPixel = physicalWidthMm / width
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val tips = ArrayList<PrintTip>()

        for (start in mask.indices) {
            if (visited[start]) continue
            val opening = mask[start]
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var boundary = false
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) boundary = true
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)

                fun visit(next: Int) {
                    if (!visited[next] && mask[next] == opening) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }

            val island = !opening && !boundary
            val fine = opening &&
                minOf(maxX - minX + 1, maxY - minY + 1) * mmPerPixel < fineCutMm
            if (island || fine) {
                // Reject impossible outputs before allocating millions of notes or looking for
                // printable positions. Rejecting is preferable to silently losing guidance.
                require(tips.size < maxTips) {
                    "Too many stencil details for a printable PDF; simplify the stencil or use fewer sheets."
                }
                val centerX = (minX + maxX) / 2
                val centerY = (minY + maxY) / 2
                var anchor = queue[0]
                var distance = Long.MAX_VALUE
                for (i in 0 until tail) {
                    val pixel = queue[i]
                    val dx = (pixel % width - centerX).toLong()
                    val dy = (pixel / width - centerY).toLong()
                    val d = dx * dx + dy * dy
                    if (d < distance) {
                        distance = d
                        anchor = pixel
                    }
                }
                tips += PrintTip(
                    if (island) PrintTipKind.ISLAND else PrintTipKind.FINE_CUT,
                    anchor % width,
                    anchor / width,
                )
            }
        }
        return tips
    }
}
