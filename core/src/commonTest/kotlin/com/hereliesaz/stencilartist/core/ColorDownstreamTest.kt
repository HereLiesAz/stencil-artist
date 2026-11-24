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
import com.hereliesaz.stencilartist.core.vector.VectorOptions
import com.hereliesaz.stencilartist.core.vector.Vectorizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun customPaintSetSurvivesOriginalFlatAndExtrudedImagePreparation() {
        val palette = listOf(0xFF203050.toInt(), 0xFFD84935.toInt(), 0xFFF4C84A.toInt(),
            0xFF329D8C.toInt(), 0xFF895AB2.toInt())
        val image = flatRegions(palette, 5, 12)
        val depth = FloatArray(image.pixels.size) { (it % image.width).toFloat() / (image.width - 1) }
        val light = LightingParams(enabled = true, ambient = .15f, intensity = .8f, flatMatteMix = .6f)
        val adjusted = ImageAdjustments(contrast = .25f, saturation = .8f)
        val images = listOf(image, DepthLighting.relight(image, depth, light),
            ReliefScene.prepare(image, depth, light).renderSource(light),
            ReliefScene.prepare(image, depth, light.copy(useSourceTexture = false)).renderSource(light.copy(useSourceTexture = false)))
        for (render in images) {
            val prepared = adjusted.apply(render)
            val colour = ColorSeparator.separate(prepared, ColorParams(sheets = 5, smoothing = 1, closing = 1,
                colors = palette))!!.separation
            val tone = TonalSeparator.separate(prepared, TonalParams(sheets = 5, smoothing = 1, closing = 1,
                base = Base.DARK, colors = palette))!!.separation
            assertEquals(palette.toSet(), colour.sheets.map { it.color }.toSet())
            assertEquals(palette, tone.sheets.map { it.color })
            for (stencil in listOf(colour, tone)) {
                assertTrue(stencil.render().all { it in palette })
                assertEquals(stencil.sheets.map { it.color }, Vectorizer.vectorize(stencil).sheets.map { it.color })
            }
        }
    }

    /** Exercise the core handoff used by StencilState, without pretending to drive its UI or inference. */
    @Test
    fun correctedSubjectAndEditedDepthSurviveRepairInspectionAndExport() {
        val w = 24; val h = 16
        val dark = 0xFF602010.toInt(); val bright = 0xFF40D0F0.toInt()
        val palette = listOf(dark, bright)
        val pixels = IntArray(w * h) { if (it % w < 12) dark else bright }
        val original = RgbaImage(w, h, pixels.copyOf())
        val hole = 7 * w + 5
        val raised = 7 * w + 18
        val correction = MaskCorrections.apply(original, original, listOf(
            MaskStroke(listOf(MaskPoint(5.5f, 7.5f)), .5f, false)))
        assertContentEquals(BooleanArray(w * h) { it == hole }, correction.excluded)

        val inferred = FloatArray(w * h) { .25f }
        val edited = DepthEdits(strokes = listOf(
            DepthStroke(listOf(MaskPoint(18.5f, 7.5f)), 2f, DepthBrush.SET,
                strength = 1f, hardness = 1f, target = .75f),
            DepthStroke(listOf(MaskPoint(5.5f, 7.5f)), .5f, DepthBrush.SET,
                strength = 1f, hardness = 1f, target = 1f)
        )).apply(inferred, correction.image)
        assertEquals(.75f, edited[raised])
        assertEquals(.25f, edited[hole])
        val source = ImageAdjustments(saturation = .9f).apply(correction.image)
        val lighting = LightingParams(enabled = true, elevation = 60f, intensity = .5f, ambient = .5f)
        val scene = ReliefScene.prepare(source, edited, lighting)
        val solid = lighting.copy(useSourceTexture = false, surfaceColor = 0xB0C0D0)
        val frontal = scene.renderSource(lighting)
        val orbit = scene.renderOrbit(lighting, 45f, 20f, shadowOnly = true)
        assertTrue(orbit.pixels.any { it ushr 24 != 0 })
        assertContentEquals(frontal.pixels, scene.renderSource(lighting).pixels)
        val rendered = listOf(source, DepthLighting.relight(source, edited, lighting),
            frontal, scene.renderSource(solid))
        val exact = VectorOptions(tolerance = 0.0, minArea = 0.0, smoothing = 0)

        for ((mode, image) in rendered.withIndex()) {
            val prepared = ImageAdjustments(contrast = .15f).apply(image)
            assertEquals(0, prepared.pixels[hole] ushr 24, "lighting mode $mode")
            val separated = listOf(
                ColorSeparator.separate(prepared, ColorParams(sheets = 2, colors = palette,
                    smoothing = 1, closing = 3))!!.separation,
                TonalSeparator.separate(prepared, TonalParams(sheets = 2, colors = palette,
                    base = Base.DARK, smoothing = 1, closing = 3))!!.separation)
            for (raw in separated) {
                val repaired = IslandPlanner.repair(raw.excluding(correction.excluded), prepared).separation
                val committed = repaired.withLiftZones(SoftGradientZones.locate(prepared, repaired))
                assertEquals(palette.toSet(), committed.sheets.map { it.color }.toSet())
                assertEquals(0, committed.render()[hole])
                assertTrue(committed.render()[0] ushr 24 != 0)
                assertTrue(committed.render()[w * h - 1] ushr 24 != 0)
                val masks = committed.sheets.indices.map { committed.mask(it) }
                val drawing = Vectorizer.vectorize(committed, options = exact)
                for (sheet in committed.sheets.indices) {
                    val inspection = StencilInspection.inspect(committed, sheet, 240.0, 2.0)
                    assertEquals(w, inspection.image.width)
                    assertEquals(h, inspection.image.height)
                    assertEquals(false, masks[sheet][hole])
                    assertEquals(0, committed.render(sheet)[hole])
                    val shapes = drawing.sheets[sheet].shapes
                    // Independent point-in-polygon evaluation at pixel centres; no vectorizer round-trip helper.
                    for (y in 0 until h) for (x in 0 until w) {
                        val inside = shapes.any { shape -> shape.outer.contains(x + .5, y + .5) &&
                            shape.holes.none { it.contains(x + .5, y + .5) } }
                        assertEquals(masks[sheet][y * w + x], inside,
                            "mode=$mode sheet=$sheet pixel=($x,$y)")
                    }
                }
                val recoloured = committed.withSheetColor(0, 0xFF7A3B91.toInt())
                val recolouredDrawing = Vectorizer.vectorize(recoloured, options = exact)
                assertContentEquals(committed.index, recoloured.index)
                assertEquals(committed.liftZones, recoloured.liftZones)
                for (sheet in committed.sheets.indices) {
                    assertContentEquals(masks[sheet], committed.mask(sheet))
                    assertContentEquals(masks[sheet], recoloured.mask(sheet))
                    assertEquals(drawing.sheets[sheet].shapes, recolouredDrawing.sheets[sheet].shapes)
                }
                val svg = SvgExporter.export(recolouredDrawing, PhysicalSize(240.0))
                assertTrue("fill=\"#7A3B91\"" in svg || "fill=\"#7a3b91\"" in svg)
                assertEquals(2, Regex("<g id=\"sheet-").findAll(svg).count())
                val plan = PrintPdf.plan(recoloured, PrintOptions(widthMm = 240.0))
                assertEquals(2, plan.sheets.size)
            }
        }
        assertContentEquals(pixels, original.pixels)
        assertContentEquals(FloatArray(w * h) { .25f }, inferred)
    }
}
