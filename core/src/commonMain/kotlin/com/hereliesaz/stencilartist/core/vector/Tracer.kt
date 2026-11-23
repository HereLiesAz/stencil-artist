package com.hereliesaz.stencilartist.core.vector

/**
 * Turns a boolean pixel mask into exact outlines.
 *
 * The tracer follows the cracks between pixels, so every vertex is a pixel CORNER with integer
 * coordinates: pixel (x, y) occupies the square from (x, y) to (x + 1, y + 1). Two shapes that border
 * each other share edges exactly, with no gap and no overlap. Only corners are kept; straight runs
 * are single edges.
 *
 * Connectivity: foreground is 4-connected, so two pixels that touch only diagonally are separate
 * shapes (a cutter cannot cut a join of zero width). Background is therefore 8-connected: a hole is
 * background that cannot escape even diagonally. Where two background pixels meet only at a corner,
 * they are the same region.
 *
 * Winding, y-down: outer rings have positive [Ring.signedArea] (clockwise on screen), holes negative,
 * and the foreground is always on the right of travel. A hole is assigned to the smallest outer ring
 * that contains it, so a shape inside a hole is its own [Shape].
 *
 * Memory is one byte per pixel corner, however many sheets there are; trace one sheet at a time.
 */
object Tracer {
    // Directions in y-down space, clockwise on screen: east, south, west, north. A right turn is +1.
    private val DX = intArrayOf(1, 0, -1, 0)
    private val DY = intArrayOf(0, 1, 0, -1)

    private class Ints {
        var data = IntArray(16)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
    }

    private class Outer(val ring: Ring, val area: Double, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
    private class Hole(val ring: Ring, val probeX: Double, val probeY: Double)

    /** Traces [mask] (row-major, [width] by [height]) into shapes, in the order their outer rings are met scanning rows top to bottom. */
    fun trace(mask: BooleanArray, width: Int, height: Int): List<Shape> {
        require(width > 0 && height > 0) { "mask must be at least 1x1, got ${width}x$height" }
        require(mask.size == width * height) { "expected ${width * height} pixels, got ${mask.size}" }

        // out[v] holds one bit per outgoing boundary edge at corner v; bit d is direction d.
        val stride = width + 1
        val out = ByteArray(stride * (height + 1))
        fun mark(x: Int, y: Int, d: Int) {
            val i = y * stride + x
            out[i] = (out[i].toInt() or (1 shl d)).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                if (y == 0 || !mask[(y - 1) * width + x]) mark(x, y, 0)
                if (x == width - 1 || !mask[y * width + x + 1]) mark(x + 1, y, 1)
                if (y == height - 1 || !mask[(y + 1) * width + x]) mark(x + 1, y + 1, 2)
                if (x == 0 || !mask[y * width + x - 1]) mark(x, y + 1, 3)
            }
        }

        val outers = ArrayList<Outer>()
        val holes = ArrayList<Hole>()
        val xs = Ints()
        val ys = Ints()
        for (vy in 0..height) {
            for (vx in 0..width) {
                while (true) {
                    val bits = out[vy * stride + vx].toInt()
                    if (bits == 0) break
                    var startDir = 0
                    while (bits and (1 shl startDir) == 0) startDir++
                    val area2 = follow(out, stride, vx, vy, startDir, xs, ys)
                    val points = ArrayList<Point>(xs.size)
                    var minX = Double.MAX_VALUE
                    var minY = Double.MAX_VALUE
                    var maxX = -Double.MAX_VALUE
                    var maxY = -Double.MAX_VALUE
                    for (i in 0 until xs.size) {
                        val px = xs.data[i].toDouble()
                        val py = ys.data[i].toDouble()
                        points.add(Point(px, py))
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                    if (area2 > 0) {
                        outers.add(Outer(Ring(points, false), area2 / 2.0, minX, minY, maxX, maxY))
                    } else {
                        // The pixel just right of the first edge is foreground and belongs to the shape
                        // this hole is in. Its centre is never on a ring, unlike any corner.
                        val (px, py) = pixelRightOf(vx, vy, startDir)
                        holes.add(Hole(Ring(points, true), px + 0.5, py + 0.5))
                    }
                }
            }
        }

        val holesOf = Array(outers.size) { ArrayList<Ring>() }
        val bySmallest = outers.indices.sortedBy { outers[it].area }
        for (hole in holes) {
            val owner = bySmallest.firstOrNull { i ->
                val o = outers[i]
                hole.probeX > o.minX && hole.probeX < o.maxX && hole.probeY > o.minY && hole.probeY < o.maxY &&
                    o.ring.contains(hole.probeX, hole.probeY)
            }
            checkNotNull(owner) { "hole without an enclosing outline" }
            holesOf[owner].add(hole.ring)
        }
        return outers.indices.map { Shape(outers[it].ring, holesOf[it]) }
    }

    /** Which turn to take at a corner, given arrival heading [d]: right first, then straight, then left. -1 if none. */
    private fun pick(bits: Int, d: Int): Int {
        val right = (d + 1) and 3
        if (bits and (1 shl right) != 0) return right
        if (bits and (1 shl d) != 0) return d
        val left = (d + 3) and 3
        if (bits and (1 shl left) != 0) return left
        return -1
    }

    /**
     * Walks one loop from corner ([sx], [sy]) leaving in [startDir], consuming its edges, and fills
     * [xs]/[ys] with the corners where it turns. Returns twice the signed area. Turning right first at a
     * corner where two foreground pixels meet diagonally keeps each of them in its own loop, which is
     * the 4-connected foreground rule.
     */
    private fun follow(out: ByteArray, stride: Int, sx: Int, sy: Int, startDir: Int, xs: Ints, ys: Ints): Long {
        xs.size = 0
        ys.size = 0
        xs.add(sx)
        ys.add(sy)
        var x = sx
        var y = sy
        var d = startDir
        var area2 = 0L
        var first = true
        while (true) {
            // The start edge stays marked until the loop closes, so arriving back can recognise it.
            if (!first) out[y * stride + x] = (out[y * stride + x].toInt() and (1 shl d).inv()).toByte()
            first = false
            val nx = x + DX[d]
            val ny = y + DY[d]
            area2 += x.toLong() * ny - nx.toLong() * y
            val nd = pick(out[ny * stride + nx].toInt(), d)
            check(nd >= 0) { "open contour at ($nx, $ny)" }
            if (nx == sx && ny == sy && nd == startDir) {
                out[sy * stride + sx] = (out[sy * stride + sx].toInt() and (1 shl startDir).inv()).toByte()
                return area2
            }
            if (nd != d) {
                xs.add(nx)
                ys.add(ny)
            }
            x = nx
            y = ny
            d = nd
        }
    }

    /** The pixel on the right-hand side of the edge leaving corner ([x], [y]) in direction [d]. */
    private fun pixelRightOf(x: Int, y: Int, d: Int): Pair<Int, Int> = when (d) {
        0 -> x to y
        1 -> (x - 1) to y
        2 -> (x - 1) to (y - 1)
        else -> x to (y - 1)
    }
}
