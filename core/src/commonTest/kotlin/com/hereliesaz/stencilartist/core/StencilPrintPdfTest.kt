package com.hereliesaz.stencilartist.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StencilPrintPdfTest {
    private val ink = Sheet(0xFF000000.toInt())

    private fun stencil(width: Int, height: Int, cut: (Int, Int) -> Boolean): Separation =
        Separation(width, height, listOf(ink), IntArray(width * height) { i ->
            if (cut(i % width, i / width)) 0 else Separation.NONE
        })

    @Test
    fun printsPaperAtTrueScaleAndIncludesAnInStencilTipForFineDetail() {
        val s = stencil(240, 240) { x, y -> x == 100 && y == 110 }
        val pdf = StencilPrintPdf.make(s, StencilPrintOptions(widthMm = 200.0)).decodeToString()
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.contains("/MediaBox [0 0 612.0 792.0]"))
        assertTrue(pdf.contains("(PRINT AT 100% - ACTUAL SIZE)"))
        assertTrue(pdf.contains("(FINE CUT 83,92mm)"))

        // Independently read the first word's PDF position and verify that its complete
        // conservative printed footprint lies on material, not on the one-pixel cut.
        val match = assertNotNull(
            Regex("""BT /F2 7.4 Tf ([0-9.]+) ([0-9.]+) Td \\(FINE CUT""").find(pdf),
        )
        val textX = match.groupValues[1].toDouble()
        val textY = match.groupValues[2].toDouble()
        val pointPerPixel = (200.0 * 72.0 / 25.4) / 240.0
        val cutLeft = 24.0 + 100.0 * pointPerPixel
        val cutRight = cutLeft + pointPerPixel
        val cutTop = 792.0 - 55.0 - 110.0 * pointPerPixel
        val cutBottom = cutTop - pointPerPixel
        val labelLeft = textX - 2.0
        val labelRight = labelLeft + 146.0
        val labelTop = textY + 11.0
        val labelBottom = labelTop - 49.0
        assertTrue(labelRight < cutLeft || labelLeft > cutRight ||
            labelTop < cutBottom || labelBottom > cutTop,
            "Label must never touch the cut")
    }

    @Test
    fun whenThereIsNoUncutRoomNoAnnotationIsPrintedOverACut() {
        val s = stencil(30, 30) { x, y -> x !in 12..17 || y !in 12..17 }
        val pdf = StencilPrintPdf.make(s, StencilPrintOptions(widthMm = 100.0)).decodeToString()
        assertFalse(pdf.contains("(ISLAND "))
    }

    @Test
    fun tiledPagesGetJoinAdviceOnlyWhenThereIsSafeMaterial() {
        val clear = stencil(400, 400) { _, _ -> false }
        val pdf = StencilPrintPdf.make(clear, StencilPrintOptions(widthMm = 300.0)).decodeToString()
        assertTrue(pdf.contains("/Count 4"))
        assertTrue(pdf.contains("(TILE JOIN)"))
        val allCut = stencil(400, 400) { _, _ -> true }
        val noRoom = StencilPrintPdf.make(allCut, StencilPrintOptions(widthMm = 300.0))
            .decodeToString()
        assertFalse(noRoom.contains("(TILE JOIN)"))
    }

    @Test
    fun separateSheetsGetSeparatePagesWithoutAssumingThreeOrGreyLayers() {
        val s = Separation(10, 10, List(4) { Sheet(0xFF113399.toInt()) },
            IntArray(100) { it % 4 })
        val pdf = StencilPrintPdf.make(s, StencilPrintOptions(widthMm = 100.0))
            .decodeToString()
        assertTrue(pdf.contains("/Count 4"))
        for (sheet in 1..4) assertTrue(pdf.contains("(SHEET " + sheet + " / 4"))
    }
}
