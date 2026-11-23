package com.hereliesaz.stencilartist.core.vector

/** A small deterministic generator (a 64-bit LCG), so masks are the same on every target and run. */
class Lcg(private var state: Long) {
    fun next(): Int {
        state = state * 6364136223846793005L + 1442695040888963407L
        return (state ushr 33).toInt()
    }

    /** A value in 0 until [n]. */
    fun below(n: Int): Int = next() % n
}

/** A mask from rows of text: '#' is foreground, anything else background. All rows must be the same length. */
fun maskOf(vararg rows: String): Triple<BooleanArray, Int, Int> {
    val w = rows[0].length
    require(rows.all { it.length == w })
    return Triple(BooleanArray(w * rows.size) { rows[it / w][it % w] == '#' }, w, rows.size)
}

fun traceRows(vararg rows: String): List<Shape> {
    val (m, w, h) = maskOf(*rows)
    return Tracer.trace(m, w, h)
}

/**
 * Even-odd fill of every ring of [shapes] sampled at pixel centres. Nested shapes (an island in a hole)
 * work because the rule is applied across all rings together.
 */
fun rasterize(shapes: List<Shape>, w: Int, h: Int): BooleanArray {
    val rings = shapes.flatMap { it.rings }
    return BooleanArray(w * h) { i ->
        val cx = i % w + 0.5
        val cy = i / w + 0.5
        rings.count { it.contains(cx, cy) } % 2 == 1
    }
}

/** A blobby random mask: noise at [percent] density, then one majority-vote pass to clump it. */
fun randomMask(seed: Long, w: Int, h: Int, percent: Int): BooleanArray {
    val rnd = Lcg(seed)
    val noise = BooleanArray(w * h) { rnd.below(100) < percent }
    return BooleanArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var votes = 0
        var cells = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) {
                cells++
                if (noise[ny * w + nx]) votes++
            }
        }
        votes * 2 > cells || (votes * 2 == cells && noise[i])
    }
}

fun square(x0: Double, y0: Double, x1: Double, y1: Double, hole: Boolean = false): Ring =
    Ring(
        if (hole) listOf(Point(x0, y0), Point(x0, y1), Point(x1, y1), Point(x1, y0))
        else listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1)),
        hole,
    )
