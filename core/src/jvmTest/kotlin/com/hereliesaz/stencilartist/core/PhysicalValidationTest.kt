package com.hereliesaz.stencilartist.core

import com.hereliesaz.stencilartist.core.export.PhysicalSize
import com.hereliesaz.stencilartist.core.export.PrintOptions
import com.hereliesaz.stencilartist.core.export.PrintPdf
import com.hereliesaz.stencilartist.core.export.SvgExporter
import com.hereliesaz.stencilartist.core.export.SvgOptions
import com.hereliesaz.stencilartist.core.vector.Vectorizer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Produce actual application exports for controlled physical trials. The synthetic artwork is
 * deliberately repeatable; its printed and painted behavior must be measured by an operator.
 * Output is optional, so ordinary unit tests do not write outside their test environment.
 */
class PhysicalValidationTest {
    @Test
    fun exportControlledArtworkForPhysicalTrials() {
        val output = System.getenv("STENCIL_PHYSICAL_OUTPUT")?.let { File(it).apply { mkdirs() } }
        val w = 320
        val h = 240
        val palettes = listOf(
            "tonal" to listOf(0xFF161616.toInt(), 0xFF888888.toInt(), 0xFFF5F5F5.toInt()),
            "colour" to listOf(0xFF173C43.toInt(), 0xFFB64A35.toInt(), 0xFFF0C449.toInt()),
        )
        val report = StringBuilder("artwork,style,width_mm,sheet,paint,islands,fine_cuts,narrow_stock_pixels,paint_open_pixels,concealed_bridges,reconstructed_islands,source_guided_islands,unresolved_islands,print_tiles\n")
        for ((name, palette) in palettes) {
            // A stylized pair of goggles: pupils surrounded by paint, highlights, an asymmetrical
            // reflection, and a line/stock ladder of one, two, three, four and six working pixels.
            val pixels = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                if (x !in 20 until 300 || y !in 20 until 220) 0 else {
                    var rank = 0
                    for (cx in listOf(100, 220)) {
                        val dx = x - cx
                        val dy = y - 86
                        if (dx * dx * 4 + dy * dy * 9 <= 10000) rank = 1
                        if (dx * dx + dy * dy <= 16 * 16) rank = 0
                        if (x in cx - 5..cx + 5 && y in 46..75) rank = 2
                        if ((dx + 8) * (dx + 8) + (dy + 6) * (dy + 6) <= 25) rank = 2
                    }
                    for ((n, thickness) in listOf(1, 2, 3, 4, 6).withIndex()) {
                        val left = 35 + n * 50
                        if (x in left until left + 28 && y in 151 until 151 + thickness) rank = 2
                        if ((x in left until left + 8 || x in left + 8 + thickness until left + 16 + thickness) &&
                            y in 174 until 202) rank = 1
                    }
                    palette[rank]
                }
            }
            val source = RgbaImage(w, h, pixels.copyOf())
            val raw = if (name == "tonal") {
                requireNotNull(TonalSeparator.separate(source, TonalParams(sheets = 3,
                    smoothing = 1, closing = 1, base = Base.DARK, colors = palette))).separation
            } else {
                requireNotNull(ColorSeparator.separate(source, ColorParams(sheets = 3,
                    smoothing = 1, closing = 1, order = ColorOrder.DARK_TO_LIGHT, colors = palette))).separation
            }
            assertEquals(palette.toSet(), raw.sheets.map { it.color }.toSet())
            assertEquals(0, raw.render()[0])
            assertEquals(3, raw.render().filter { it != 0 }.toSet().size)
            if (output != null) writePng(File(output, "$name-source.png"), w, h, pixels)

            for (style in listOf("classic", "brush")) {
                val styled = if (style == "brush") StrokeStyler.stylize(source, raw, strokeWidthPixels = 2) else raw
                val repair = IslandPlanner.repair(styled, source, minimumBridgePixels = 2)
                val committed = repair.separation.withLiftZones(SoftGradientZones.locate(source, repair.separation))
                assertEquals(raw.sheets, committed.sheets)
                assertEquals(0, committed.render()[0])
                val snapshot = committed.render()
                val drawing = Vectorizer.vectorize(committed)
                assertEquals(raw.sheets.map { it.color }, drawing.sheets.map { it.color })
                assertTrue(drawing.sheets.all { it.shapes.isNotEmpty() })
                if (output != null) writePng(File(output, "$name-$style-preview.png"), w, h, snapshot)
                for (width in listOf(160, 320)) {
                    val dir = output?.let { File(it, "$name-$style-${width}mm").apply { mkdirs() } }
                    val print = PrintOptions(widthMm = width.toDouble())
                    val plan = PrintPdf.plan(committed, print)
                    assertEquals(3, plan.sheets.size)
                    if (width == 160) assertEquals(1, plan.tilesPerSheet)
                    else assertTrue(plan.tilesPerSheet > 1)
                    val pdf = PrintPdf.make(committed, print)
                    assertEquals("%PDF-1.4", pdf.copyOfRange(0, 8).decodeToString())
                    dir?.let { File(it, "print.pdf").writeBytes(pdf) }
                    val size = PhysicalSize(width.toDouble())
                    val svg = SvgExporter.export(drawing, size, options = SvgOptions(cutLines = true))
                    assertTrue("width=\"${width}mm\"" in svg)
                    dir?.let { File(it, "all-cuts.svg").writeText(svg) }
                    for (sheet in committed.sheets.indices) {
                        val mask = committed.mask(sheet)
                        val inspection = StencilInspection.inspect(committed, sheet, width.toDouble(), 2.0)
                        assertEquals(w, inspection.image.width)
                        assertEquals(h, inspection.image.height)
                        assertContentEquals(mask, committed.mask(sheet))
                        val sheetSvg = SvgExporter.export(drawing, size, sheet = sheet, options = SvgOptions(cutLines = true))
                        dir?.let {
                            File(it, "sheet-${sheet + 1}.svg").writeText(sheetSvg)
                            writePng(File(it, "sheet-${sheet + 1}-inspection.png"), w, h, inspection.image.pixels)
                            writePng(File(it, "sheet-${sheet + 1}-mask.png"), w, h,
                                IntArray(mask.size) { p -> if (mask[p]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
                        }
                        report.append(listOf(name, style, width, sheet + 1,
                            committed.sheets[sheet].color.toUInt().toString(16).padStart(8, '0'),
                            inspection.islands, inspection.fineCuts, inspection.narrowStockPixels,
                            mask.count { it }, repair.concealedBridges, repair.reconstructedIslands,
                            repair.sourceGuidedIslands, repair.unresolved, plan.tilesPerSheet).joinToString(","))
                            .append('\n')
                    }
                    assertContentEquals(snapshot, committed.render())
                }
            }
            assertContentEquals(pixels, source.pixels)
        }
        output?.let { File(it, "digital-inspection.csv").writeText(report.toString()) }
    }

    /** Lossless reference image; no additional separation or geometry conversion. */
    private fun writePng(file: File, w: Int, h: Int, pixels: IntArray) {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        check(ImageIO.write(image, "png", file))
    }
}
