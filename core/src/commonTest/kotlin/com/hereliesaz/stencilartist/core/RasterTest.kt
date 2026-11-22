package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun String.unhexMask(): BooleanArray = unhex().let { bytes -> BooleanArray(bytes.size) { bytes[it].toInt() != 0 } }

/** Holds the raster operations to what OpenCV produced for the same input ([Golden]). */
class RasterTest {

    private val w = Golden.PLANE_W
    private val h = Golden.PLANE_H

    @Test
    fun medianBlurMatchesOpenCv() {
        val plane = Golden.PLANE.unhex()
        assertContentEquals(Golden.MEDIAN_3.unhex(), Raster.medianBlur(plane, w, h, 3))
        assertContentEquals(Golden.MEDIAN_5.unhex(), Raster.medianBlur(plane, w, h, 5))
        assertContentEquals(Golden.MEDIAN_9.unhex(), Raster.medianBlur(plane, w, h, 9))
    }

    @Test
    fun closingMatchesOpenCv() {
        val mask = Golden.MASK.unhexMask()
        assertContentEquals(Golden.CLOSE_2.unhexMask(), Raster.close(mask, w, h, 2), "2x2")
        assertContentEquals(Golden.CLOSE_3.unhexMask(), Raster.close(mask, w, h, 3), "3x3")
        assertContentEquals(Golden.CLOSE_4.unhexMask(), Raster.close(mask, w, h, 4), "4x4")
        assertContentEquals(Golden.CLOSE_5.unhexMask(), Raster.close(mask, w, h, 5), "5x5")
        assertContentEquals(Golden.CLOSE_7.unhexMask(), Raster.close(mask, w, h, 7), "7x7")
    }

    @Test
    fun fillingHolesMatchesOpenCv() {
        assertContentEquals(Golden.FILLED.unhexMask(), Raster.fillHoles(Golden.MASK.unhexMask(), w, h))
    }

    @Test
    fun aKernelOfOneChangesNothing() {
        val plane = Golden.PLANE.unhex()
        val mask = Golden.MASK.unhexMask()
        assertContentEquals(plane, Raster.medianBlur(plane, w, h, 1))
        assertContentEquals(mask, Raster.close(mask, w, h, 1))
    }

    @Test
    fun aRingIsFilledAndAnOpenRingIsNot() {
        fun mask(vararg rows: String) = BooleanArray(rows.size * rows[0].length) { rows[it / rows[0].length][it % rows[0].length] == '#' }
        val ring = mask(
            ".....",
            ".###.",
            ".#.#.",
            ".###.",
            ".....",
        )
        assertEquals(9, Raster.fillHoles(ring, 5, 5).count { it })
        val open = mask(
            ".....",
            ".#.#.",
            ".#.#.",
            ".###.",
            ".....",
        )
        assertContentEquals(open, Raster.fillHoles(open, 5, 5))
    }
}
