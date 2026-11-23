package com.hereliesaz.stencilartist.core.export

import com.hereliesaz.stencilartist.core.vector.Drawing
import com.hereliesaz.stencilartist.core.vector.Point
import com.hereliesaz.stencilartist.core.vector.Ring
import com.hereliesaz.stencilartist.core.vector.Shape
import com.hereliesaz.stencilartist.core.vector.SheetOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val ORANGE = 0xFFCC3300.toInt()
const val TEAL_HALF = 0x8033AA66.toInt()
const val NEAR_WHITE = 0xFFF8F8F0.toInt()

private fun r(vararg xy: Double, hole: Boolean = false) = Ring((xy.indices step 2).map { Point(xy[it], xy[it + 1]) }, hole)

/**
 * 100 x 60 pixels, three sheets in non-grey colours (one translucent, one near white): a frame with a hole
 * and an island in the hole, a triangle at fractional coordinates, and a thin bar.
 */
fun sampleDrawing(): Drawing = Drawing(
    100.0, 60.0,
    listOf(
        SheetOutline(
            ORANGE,
            listOf(
                Shape(
                    r(10.0, 10.0, 60.0, 10.0, 60.0, 50.0, 10.0, 50.0),
                    listOf(r(20.0, 20.0, 20.0, 40.0, 40.0, 40.0, 40.0, 20.0, hole = true)),
                ),
                Shape(r(25.0, 25.0, 35.0, 25.0, 35.0, 35.0, 25.0, 35.0)),
            ),
        ),
        SheetOutline(TEAL_HALF, listOf(Shape(r(70.5, 5.25, 95.0, 5.25, 82.75, 30.125)))),
        SheetOutline(NEAR_WHITE, listOf(Shape(r(5.0, 52.0, 95.0, 52.0, 95.0, 58.0, 5.0, 58.0)))),
    ),
)

/** A 200 mm wide placement of [sampleDrawing]: 2 mm per pixel, 120 mm high. */
val sampleSize = PhysicalSize(200.0)

/** The rings of sheet [i] of [d], in mm, y down, in file order. */
fun expectedRings(d: Drawing, i: Int, size: PhysicalSize): List<List<Point>> {
    val k = size.widthMm / d.width
    return d.sheets[i].shapes.flatMap { s -> s.rings.map { ring -> ring.points.map { Point(it.x * k, it.y * k) } } }
}

fun assertRingsEqual(expected: List<List<Point>>, actual: List<List<Point>>, tolerance: Double, message: String = "") {
    assertEquals(expected.size, actual.size, "$message ring count")
    for (i in expected.indices) {
        assertEquals(expected[i].size, actual[i].size, "$message ring $i size")
        for (j in expected[i].indices) {
            assertTrue(abs(expected[i][j].x - actual[i][j].x) <= tolerance, "$message ring $i point $j x: ${expected[i][j].x} vs ${actual[i][j].x}")
            assertTrue(abs(expected[i][j].y - actual[i][j].y) <= tolerance, "$message ring $i point $j y: ${expected[i][j].y} vs ${actual[i][j].y}")
        }
    }
}

/** A single wiggly shape with [n] vertices, to exercise long paths. */
fun roundDrawing(n: Int): Drawing = Drawing(
    100.0, 100.0,
    listOf(
        SheetOutline(
            0xFF2244AA.toInt(),
            listOf(Shape(Ring((0 until n).map { i -> val a = 2 * PI * i / n; Point(50 + 40 * cos(a) + 3 * sin(9 * a), 50 + 40 * sin(a)) }, false))),
        ),
    ),
)

/** [n] one-square sheets in distinct colours. */
fun manySheets(n: Int): Drawing = Drawing(
    100.0, 50.0,
    List(n) { i ->
        SheetOutline(
            0xFF000000.toInt() or ((30 + i * 17) shl 16) or ((200 - i * 11) shl 8) or (20 + i * 9),
            listOf(Shape(r(5.0 + i, 5.0, 15.0 + i, 5.0, 15.0 + i, 15.0, 5.0 + i, 15.0))),
        )
    },
)
