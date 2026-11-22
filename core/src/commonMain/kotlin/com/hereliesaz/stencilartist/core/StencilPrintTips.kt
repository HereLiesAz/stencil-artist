package com.hereliesaz.stencilartist.core

/** A physical cutting problem, anchored to a position in the original stencil image. */
enum class PrintTipKind { ISLAND, FINE_CUT }

/** Pixel coordinates are in the full stencil, never in a cropped print tile. */
data class PrintTip(val kind: PrintTipKind, val x: Int, val y: Int)

/**
 * Finds physical cutting problems without modifying the cuts.
 *
 * An island is an uncut four-connected region that cannot reach the image boundary, so it would
 * fall out once the surrounding opening is removed. Fine cuts are separate openings whose
 * narrowest bounding dimension is under the requested physical threshold. The same full-sheet
 * analysis is used for every tile, avoiding false islands created by a page boundary.
 */
object StencilPrintTips {
    fun detect(
        mask: BooleanArray,
        width: Int,
        height: Int,
        physicalWidthMm: Double,
        fineCutMm: Double = 1.5,
    ): List<PrintTip> {
        require(width > 0 && height > 0 && mask.size == width * height)
        require(physicalWidthMm > 0.0 && fineCutMm > 0.0)
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

            val centerX = (minX + maxX) / 2
            val centerY = (minY + maxY) / 2
            if (!opening && !boundary) {
                tips += PrintTip(PrintTipKind.ISLAND, centerX, centerY)
            } else if (opening &&
                minOf(maxX - minX + 1, maxY - minY + 1) * mmPerPixel < fineCutMm
            ) {
                tips += PrintTip(PrintTipKind.FINE_CUT, centerX, centerY)
            }
        }
        return tips
    }
}
