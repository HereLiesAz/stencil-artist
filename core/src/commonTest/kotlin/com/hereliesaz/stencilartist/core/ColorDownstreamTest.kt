package com.hereliesaz.stencilartist.core

import com.hereliesaz.stencilartist.core.export.Board
import com.hereliesaz.stencilartist.core.export.Decimal
import com.hereliesaz.stencilartist.core.export.DxfExporter
import com.hereliesaz.stencilartist.core.export.GcodeExporter
import com.hereliesaz.stencilartist.core.export.HpglExporter
import com.hereliesaz.stencilartist.core.export.MountOptions
import com.hereliesaz.stencilartist.core.export.PhysicalSize
import com.hereliesaz.stencilartist.core.export.PrintOptions
import com.hereliesaz.stencilartist.core.export.PrintPdf
import com.hereliesaz.stencilartist.core.export.SvgExporter
import com.hereliesaz.stencilartist.core.vector.Vectorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A colour separation, with colours that are not grey and sheets that are not three, goes through
 * everything downstream and comes out the other side as itself: the claim that [Separation] is the
 * only seam is checked here, not assumed.
 */
class ColorDownstreamTest {

    private val s: Separation = ColorSeparator.separate(
        flatRegions(distinctColors(6), 3, 20), ColorParams(sheets = 6, order = ColorOrder.LIGHT_TO_DARK),
    )!!.separation

    private val size = PhysicalSize(120.0)

    @Test
    fun theSeparationIsSixColoursNoneOfThemGrey() {
        assertEquals(6, s.sheets.size)
        for (sheet in s.sheets) {
            val c = sheet.color
            assertTrue((c shr 16 and 255) != (c shr 8 and 255) || (c shr 8 and 255) != (c and 255), "grey sheet")
        }
    }

    @Test
    fun theVectorizerKeepsEverySheetInOrderInItsColour() {
        val drawing = Vectorizer.vectorize(s)
        assertEquals(s.sheets.map { it.color }, drawing.sheets.map { it.color })
        assertTrue(drawing.sheets.all { it.shapes.isNotEmpty() })
    }

    @Test
    fun svgFillsAreTheSheetColoursInPaintOrder() {
        val svg = SvgExporter.export(Vectorizer.vectorize(s), size)
        var from = 0
        for ((i, sheet) in s.sheets.withIndex()) {
            val at = svg.indexOf("<g id=\"sheet-${i + 1}\" fill=\"${Decimal.rgbHex(sheet.color)}\"", from)
            assertTrue(at >= from, "sheet ${i + 1} is missing or out of order in the SVG")
            from = at
        }
        assertEquals(6, Regex("<g id=\"sheet-").findAll(svg).count())
    }

    @Test
    fun everyOtherCutterFormatWritesEverySheet() {
        val drawing = Vectorizer.vectorize(s)
        val dxf = DxfExporter.export(drawing, size)
        for (i in s.sheets.indices) assertTrue(DxfExporter.layerName(i) in dxf, "DXF layer ${i + 1}")
        val gcode = GcodeExporter.export(drawing, size)
        for ((i, sheet) in s.sheets.withIndex()) {
            assertTrue("sheet ${i + 1} of 6 (colour ${Decimal.rgbHex(sheet.color)})" in gcode, "G-code sheet ${i + 1}")
        }
        assertEquals(5, Regex("M0 ; pause").findAll(gcode).count())
        val hpgl = HpglExporter.export(drawing, size)
        // One pen select per sheet, and the closing SP0.
        assertEquals(listOf("SP1;", "SP2;", "SP3;", "SP4;", "SP5;", "SP6;", "SP0;"), Regex("SP\\d+;").findAll(hpgl).map { it.value }.toList())
    }

    private fun fillOf(sheet: Sheet) = listOf(sheet.color shr 16 and 255, sheet.color shr 8 and 255, sheet.color and 255)
        .joinToString(" ") { Decimal.format(it / 255.0, 4) } + " rg"

    @Test
    fun thePrintPdfPaintsEverySheetInItsColourWithAndWithoutMounting() {
        for (mount in listOf(null, MountOptions(Board.POSTER_22X28))) {
            val options = PrintOptions(widthMm = if (mount == null) 150.0 else 400.0, mount = mount)
            val plan = PrintPdf.plan(s, options)
            assertEquals(6, plan.sheets.size, "plan covers every sheet (mount=$mount)")
            assertTrue(plan.sheets.all { it.printed.isNotEmpty() || it.boards.isNotEmpty() })
            val pdf = PrintPdf.make(s, options)
            val text = pdf.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
            assertTrue(text.startsWith("%PDF-1.4"))
            for ((i, sheet) in s.sheets.withIndex()) assertTrue(fillOf(sheet) in text, "sheet ${i + 1} fill missing (mount=$mount)")
        }
    }
}
