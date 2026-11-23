package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.stencilartist.core.Base
import com.hereliesaz.stencilartist.core.ColorOrder
import com.hereliesaz.stencilartist.core.export.Board
import com.hereliesaz.stencilartist.core.export.BoardGrid
import com.hereliesaz.stencilartist.core.export.MountOptions
import com.hereliesaz.stencilartist.core.export.Paper
import com.hereliesaz.stencilartist.core.export.PrintOptions
import com.hereliesaz.stencilartist.core.export.PrintPdf
import com.hereliesaz.stencilartist.core.export.PrintPlan
import com.hereliesaz.stencilartist.core.export.PrintSizing
import com.hereliesaz.stencilartist.core.export.TileSpec
import com.hereliesaz.stencilartist.core.export.Tiling
import com.hereliesaz.stencilartist.core.Sheet
import com.hereliesaz.stencilartist.core.Separation
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The mats and papers a cutter file can be split across. */
private val CUTTER_FITS = listOf(TileSpec.MAT_12X12, TileSpec.MAT_12X24, TileSpec.US_LETTER, TileSpec.A4, TileSpec.A3)

/** [value] with one decimal place; common Kotlin has no String.format. */
private fun oneDecimal(value: Float): String {
    val tenths = (value * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** Below this width the controls go under the preview instead of beside it. */
private val WIDE_LAYOUT = 720.dp

/** The whole app: one screen, shared by Android and the web. */
@Composable
fun App() {
    StencilArtistTheme {
        val scope = rememberCoroutineScope()
        val models = rememberModelLoader()
        val catalogueLoader = rememberCatalogueLoader()
        val store = rememberKeyValueStore()
        val state = remember { StencilState(scope, SubjectIsolator(models), DepthEstimator(models), catalogueLoader, store) }
        val pick = rememberImagePicker(onPicked = state::open, onError = state::fail)

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxWidth >= WIDE_LAYOUT) {
                    Row(Modifier.fillMaxSize()) {
                        Preview(state, pick, Modifier.weight(1f).fillMaxHeight())
                        Controls(state, pick, Modifier.width(340.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Preview(state, pick, Modifier.weight(1f).fillMaxWidth())
                        Controls(state, pick, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The stencil, on a checkerboard so a white sheet can be told from no sheet at all. */
@Composable
private fun Preview(state: StencilState, pick: () -> Unit, modifier: Modifier) {
    Box(modifier.checkerboard(), contentAlignment = Alignment.Center) {
        val preview = state.preview
        if (preview == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Stencil Artist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light)
                Text("Any image. Any number of sheets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = pick) { Text("Open image") }
            }
        } else {
            Image(
                bitmap = preview,
                contentDescription = if (state.viewProcessedImage) "Processed base image before stencil separation"
                else when (val sheet = state.shownSheet) {
                    null -> "Stencil preview, all sheets"
                    else -> "Stencil preview, sheet ${sheet + 1}"
                },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
        if (state.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
    }
}

@Composable
private fun Controls(state: StencilState, pick: () -> Unit, modifier: Modifier) {
    val tone = state.toneParams
    val colour = state.colorParams
    val saver = rememberFileSaver()
    val stencilSharer = rememberStencilPackSharer()
    val exportScope = rememberCoroutineScope()
    var printWidthMm by remember { mutableFloatStateOf(203.2f) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var cutterFormat by remember { mutableStateOf(CutterFormat.SVG) }
    var cutterFit by remember { mutableStateOf<TileSpec?>(null) }
    var cutterCutLines by remember { mutableStateOf(true) }
    val print = remember { PrintSettings() }

    // The Android picker completes asynchronously. Keep every export control locked until the
    // result callback, not merely until the file bytes have been passed to the picker.
    fun startExport(label: String, build: (Separation) -> ExportFile) {
        if (exporting != null || state.busy) return
        val snapshot = state.separation ?: return
        exporting = label
        exportScope.launch {
            try {
                val file = withContext(Dispatchers.Default) { build(snapshot) }
                if (state.separation !== snapshot) {
                    state.fail("Stencil changed during export. Export the updated stencil instead.")
                    exporting = null
                    return@launch
                }
                saver.save(file) { saved ->
                    if (!saved) state.fail("$label wasn't saved.")
                    exporting = null
                }
            } catch (error: Exception) {
                state.fail("$label export failed: " + (error.message ?: "unknown error"))
                exporting = null
            }
        }
    }

    fun shareStencilPack() {
        if (exporting != null || state.busy) return
        val snapshot = state.separation ?: return
        exporting = "StencilPack share"
        exportScope.launch {
            try {
                val file = withContext(Dispatchers.Default) { RasterExports.stencilPack(snapshot) }
                if (state.separation !== snapshot) {
                    state.fail("Stencil changed before sharing. Export the updated stencil instead.")
                } else {
                    stencilSharer.share(file)
                }
            } catch (error: Exception) {
                state.fail("StencilPack sharing failed: " + (error.message ?: "unknown error"))
            } finally {
                exporting = null
            }
        }
    }

    val canExport = state.separation != null && !state.busy && exporting == null
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.source == null) "Open image" else "Open another image")
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }

        Section("Show") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Stencil", selected = !state.viewProcessedImage, modifier = Modifier.weight(1f)) {
                    state.show(state.shownSheet)
                }
                Choice("Base image", selected = state.viewProcessedImage, modifier = Modifier.weight(1f)) {
                    state.showProcessed(true)
                }
            }
            SheetStrip(state.separation?.sheets.orEmpty(), state.shownSheet, state::show)
        }

        Section("Background") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Remove", selected = state.removeBackground, modifier = Modifier.weight(1f)) { state.removeBackground(true) }
                Choice("Keep", selected = !state.removeBackground, modifier = Modifier.weight(1f)) { state.removeBackground(false) }
            }
            if (state.removeBackground) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Depth-assisted edges", selected = state.depthAssistedBackground,
                        modifier = Modifier.weight(1f)) { state.depthAssistedBackground(true) }
                    Choice("Saliency only", selected = !state.depthAssistedBackground,
                        modifier = Modifier.weight(1f)) { state.depthAssistedBackground(false) }
                }
            }
        }

        Section("Depth + lighting") {
            val light = state.lighting
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("On", selected = light.enabled, modifier = Modifier.weight(1f)) {
                    state.updateLighting(light.copy(enabled = true))
                }
                Choice("Off", selected = !light.enabled, modifier = Modifier.weight(1f)) {
                    state.updateLighting(light.copy(enabled = false))
                }
            }
            if (light.enabled) {
                LabelledSlider("Light azimuth", light.azimuth.roundToInt().toString() + "°",
                    light.azimuth, -180f..180f) { state.updateLighting(light.copy(azimuth = it)) }
                LabelledSlider("Light elevation", light.elevation.roundToInt().toString() + "°",
                    light.elevation, 0f..90f) { state.updateLighting(light.copy(elevation = it)) }
                LabelledSlider("Light intensity", formatLightValue(light.intensity),
                    light.intensity, 0f..2f) { state.updateLighting(light.copy(intensity = it)) }
                LabelledSlider("Ambient light", formatLightValue(light.ambient),
                    light.ambient, 0f..2f) { state.updateLighting(light.copy(ambient = it)) }
                LabelledSlider("Depth strength", formatLightValue(light.depthStrength),
                    light.depthStrength, 0f..10f) { state.updateLighting(light.copy(depthStrength = it)) }
            }
        }

        Section("Separate by") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Tone", selected = state.mode == SeparationMode.TONE, modifier = Modifier.weight(1f)) {
                    state.selectMode(SeparationMode.TONE)
                }
                Choice("Colour", selected = state.mode == SeparationMode.COLOUR, modifier = Modifier.weight(1f)) {
                    state.selectMode(SeparationMode.COLOUR)
                }
            }
        }

        if (state.mode == SeparationMode.COLOUR) PaintSection(state)

        if (!state.snapped) Section("Sheets") {
            Stepper(
                value = state.sheetCount,
                range = 1..state.maxSheets,
                label = "sheets",
                onChange = state::setSheetCount,
            )
        }

        if (state.mode == SeparationMode.TONE) {
            Section("Base") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((base, name) in listOf(Base.AUTO to "Auto", Base.DARK to "Dark", Base.LIGHT to "Light")) {
                        Choice(name, selected = tone.base == base, modifier = Modifier.weight(1f)) {
                            state.update(tone.copy(base = base))
                        }
                    }
                }
            }
        } else {
            Section("Order") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((order, name) in listOf(
                        ColorOrder.AUTO to "Auto", ColorOrder.LIGHT_TO_DARK to "Light to dark",
                        ColorOrder.DARK_TO_LIGHT to "Dark to light", ColorOrder.LARGEST_FIRST to "Largest first",
                    )) {
                        Choice(name, selected = colour.order == order) { state.update(colour.copy(order = order)) }
                    }
                }
            }
        }

        // Smoothing is a median filter and must be odd: the slider counts in steps of two.
        LabelledSlider("Smoothing", "${state.smoothing} px", (state.smoothing - 1) / 2f, 0f..15f) {
            state.setSmoothing(it.roundToInt() * 2 + 1)
        }
        LabelledSlider("Edge closing", "${state.closing} px", state.closing.toFloat(), 1f..25f) {
            state.setClosing(it.roundToInt())
        }
        if (state.mode == SeparationMode.TONE) {
            LabelledSlider("Tonal bias", tone.bias.let { if (it > 0) "+$it" else "$it" }, tone.bias.toFloat(), -100f..100f) {
                state.update(tone.copy(bias = it.roundToInt()))
            }
        }

        if (state.snapped) PaintListSection(state)

        Section("Raster PNG") {
            state.separation?.let { stencil ->
                Text(
                    "Transparent PNGs · ${stencil.width} × ${stencil.height} pixels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { startExport("Composite PNG") { RasterExports.png(it) } },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download composite PNG") }
            state.shownSheet?.let { sheet ->
                OutlinedButton(
                    onClick = { startExport("Sheet ${sheet + 1} PNG") { RasterExports.png(it, sheet) } },
                    enabled = canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download sheet ${sheet + 1} PNG") }
            }
            OutlinedButton(
                onClick = { startExport("StencilPack") { RasterExports.stencilPack(it) } },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export layered StencilPack for Graffux / GraffitiXR") }
            OutlinedButton(
                onClick = ::shareStencilPack,
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send StencilPack to Graffux / GraffitiXR") }
            OutlinedButton(
                onClick = { startExport("PNG archive") { RasterExports.allPngsZip(it) } },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download all sheets + composite ZIP") }
            if (exporting != null) Text(
                "Exporting ${exporting}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Physical size") {
            LabelledSlider("Width", printWidthMm.roundToInt().toString() + " mm",
                printWidthMm, 50f..1000f) { printWidthMm = it }
            state.separation?.let { stencil ->
                val heightMm = printWidthMm * stencil.height / stencil.width
                Text(
                    "${printWidthMm.roundToInt()} × ${heightMm.roundToInt()} mm · " +
                        "${oneDecimal(printWidthMm / 25.4f)} × ${oneDecimal(heightMm / 25.4f)} in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Cutter files") {
            // These rows wrap rather than scroll: a mouse cannot drag a row sideways, and a choice
            // that is off the edge of the panel is a choice nobody finds.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (format in CutterFormat.entries) {
                    Choice(format.label, selected = cutterFormat == format) { cutterFormat = format }
                }
            }
            // What the piece is split across: nothing, or a cutting mat or sheet of paper.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Whole", selected = cutterFit == null) { cutterFit = null }
                for (fit in CUTTER_FITS) {
                    Choice(fit.name, selected = cutterFit == fit) { cutterFit = fit }
                }
            }
            state.separation?.let { stencil ->
                cutterFit?.let { fit ->
                    val plan = Tiling.plan(printWidthMm.toDouble(), printWidthMm.toDouble() * stencil.height / stencil.width, fit)
                    Text(
                        "${plan.tiles.size} ${fit.name} piece" + (if (plan.tiles.size == 1) "" else "s") +
                            " per sheet: ${plan.cols} across, ${plan.rows} down",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cutterFormat == CutterFormat.SVG) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Cut lines", selected = cutterCutLines, modifier = Modifier.weight(1f)) { cutterCutLines = true }
                    Choice("Filled", selected = !cutterCutLines, modifier = Modifier.weight(1f)) { cutterCutLines = false }
                }
            }
            val format = cutterFormat
            val fit = cutterFit
            val lines = cutterCutLines
            val width = printWidthMm.toDouble()
            state.shownSheet?.let { sheet ->
                OutlinedButton(
                    onClick = { startExport("Sheet ${sheet + 1} ${format.label}") { CutterExports.make(it, format, width, sheet, fit, lines) } },
                    enabled = canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download sheet ${sheet + 1} ${format.label}") }
            }
            OutlinedButton(
                onClick = { startExport("All sheets ${format.label}") { CutterExports.make(it, format, width, null, fit, lines) } },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download all sheets ${format.label}") }
        }

        PrintSection(
            state = state,
            settings = print,
            widthMm = printWidthMm.toDouble(),
            canExport = canExport,
            exporting = exporting,
            onWidth = { printWidthMm = it.toFloat() },
            onExport = { label, sheet ->
                // A stencil made from cans carries its shopping list (only the sheets exported); any other has none.
                val wanted = print.options(printWidthMm.toDouble()).getOrNull()?.copy(shopping = state.shoppingFor(sheet))
                if (wanted != null) startExport(label) { stencil -> printPdfFile(stencil, wanted, sheet) }
            },
        )
    }
}

/** The one print PDF as a downloadable file: one sheet, or all of them. */
private fun printPdfFile(stencil: Separation, options: PrintOptions, sheet: Int?): ExportFile {
    val bytes = PrintPdf.make(stencil, options, if (sheet == null) stencil.sheets.indices.toList() else listOf(sheet))
    val name = if (sheet == null) "stencil-print.pdf" else "stencil-print-sheet-${(sheet + 1).toString().padStart(2, '0')}.pdf"
    return ExportFile(name, "application/pdf", bytes)
}

/** What the Print PDF section has been set to: paper, orientation and, optionally, the boards to mount on. */
private class PrintSettings {
    /** The paper preset, or null for Custom. */
    var paper by mutableStateOf<Paper?>(Paper.US_LETTER)
    var customWidthMm by mutableStateOf(200.0)
    var customHeightMm by mutableStateOf(300.0)
    var landscape by mutableStateOf(false)

    /** 0 is paper only, 1 is the poster board preset, 2 is a custom board. */
    var mountMode by mutableStateOf(0)
    var boardWidthIn by mutableStateOf(22.0)
    var boardHeightIn by mutableStateOf(28.0)
    var borderIn by mutableStateOf(MountOptions.DEFAULT_BORDER_MM / 25.4)

    val page: Paper get() = (paper ?: Paper.custom(customWidthMm, customHeightMm)).oriented(landscape)

    /** The mounting asked for, or the reason it cannot be (a board or border below the limits). */
    fun mount(): Result<MountOptions?> = runCatching {
        when (mountMode) {
            0 -> null
            1 -> MountOptions(Board.POSTER_22X28, borderIn * 25.4)
            else -> MountOptions(Board.custom(boardWidthIn * 25.4, boardHeightIn * 25.4), borderIn * 25.4)
        }
    }

    /** The print options at [widthMm], or the reason they cannot be. */
    fun options(widthMm: Double): Result<PrintOptions> = runCatching {
        PrintOptions(widthMm, paper ?: Paper.custom(customWidthMm, customHeightMm), landscape, mount().getOrThrow())
    }
}

/**
 * Everything about the print PDF in one section: paper, orientation, mounting on board, size by sheets or boards,
 * the readout (from the same planning code the exporter uses) and the download.
 */
@Composable
private fun PrintSection(
    state: StencilState,
    settings: PrintSettings,
    widthMm: Double,
    canExport: Boolean,
    exporting: String?,
    onWidth: (Double) -> Unit,
    onExport: (String, Int?) -> Unit,
) {
    val stencil = state.separation
    val options = settings.options(widthMm)
    val mountResult = settings.mount()
    val mount = mountResult.getOrNull()
    val page = settings.page
    val aspect = stencil?.let { it.height.toDouble() / it.width }
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    Section("Print PDF") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in Paper.PRESETS) Choice(preset.name, selected = settings.paper?.name == preset.name) { settings.paper = preset }
            Choice("Custom", selected = settings.paper == null) { settings.paper = null }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Portrait", selected = !settings.landscape, modifier = Modifier.weight(1f)) { settings.landscape = false }
            Choice("Landscape", selected = settings.landscape, modifier = Modifier.weight(1f)) { settings.landscape = true }
        }
        if (settings.paper == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Paper width (mm)", settings.customWidthMm, 1, { if (it > 0) settings.customWidthMm = it }, Modifier.weight(1f))
                NumberField("Paper height (mm)", settings.customHeightMm, 1, { if (it > 0) settings.customHeightMm = it }, Modifier.weight(1f))
            }
        }
        Text(
            "Pages print ${PrintPdf.MARGIN_MM.roundToInt()} mm in from the paper's edge. Nothing is cut: lay each page on top of " +
                "the one before, with its paper edges on the guide marks printed on the page beneath. Every join has a " +
                "${PrintPdf.MARGIN_MM.roundToInt()} mm blank strip; cut straight across it.",
            style = MaterialTheme.typography.bodySmall, color = subdued,
        )

        Text("MOUNT ON", style = MaterialTheme.typography.labelSmall, color = subdued)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Paper only", selected = settings.mountMode == 0) { settings.mountMode = 0 }
            Choice("Poster board 22 x 28", selected = settings.mountMode == 1) { settings.mountMode = 1 }
            Choice("Custom board", selected = settings.mountMode == 2) { settings.mountMode = 2 }
        }
        if (settings.mountMode == 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Board width (in)", settings.boardWidthIn, 2, { if (it > 0) settings.boardWidthIn = it }, Modifier.weight(1f))
                NumberField("Board height (in)", settings.boardHeightIn, 2, { if (it > 0) settings.boardHeightIn = it }, Modifier.weight(1f))
            }
        }
        if (settings.mountMode != 0) {
            NumberField("Border (in)", settings.borderIn, 2, { if (it > 0) settings.borderIn = it }, Modifier.fillMaxWidth())
            mountResult.exceptionOrNull()?.let { Text(it.message ?: "These board settings do not work.", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Pages continue across the boards, laid edge to edge. Start with page A1's paper edges on board 1's top and left edges. " +
                    "Where a page crosses a board edge, cut it on the dotted line and use the offcut to start the next board; " +
                    "that is the only paper you cut. Then cut through paper and board together. " +
                    "Heavyweight poster board or 4-ply railroad board; thin board tears.",
                style = MaterialTheme.typography.bodySmall, color = subdued,
            )
        }

        if (stencil != null && aspect != null && options.isSuccess) {
            if (mount == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Sheets wide", PrintSizing.sheetsWide(widthMm, page), 2, {
                        if (it > 0) onWidth(PrintSizing.widthMmForSheetsWide(it, page))
                    }, Modifier.weight(1f))
                    NumberField("Sheets high", PrintSizing.sheetsHigh(widthMm, aspect, page), 2, {
                        if (it > 0) onWidth(PrintSizing.widthMmForSheetsHigh(it, aspect, page))
                    }, Modifier.weight(1f))
                }
            } else {
                val landscape = BoardGrid.of(widthMm, widthMm * aspect, mount).landscape
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Boards wide", PrintSizing.boardsWide(widthMm, mount, landscape), 2, {
                        if (it > 0) onWidth(PrintSizing.widthMmForBoardsWide(it, mount, landscape))
                    }, Modifier.weight(1f))
                    NumberField("Boards high", PrintSizing.boardsHigh(widthMm, aspect, mount, landscape), 2, {
                        if (it > 0) onWidth(PrintSizing.widthMmForBoardsHigh(it, aspect, mount, landscape))
                    }, Modifier.weight(1f))
                }
            }
            OutlinedButton(
                onClick = { onWidth(PrintSizing.snapToWholePages(widthMm, aspect, page, mount)) },
                enabled = PrintSizing.hasSliver(widthMm, aspect, page, mount),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (mount == null) "Fit to whole pages" else "Fit to whole boards") }
        }
        val failure = options.exceptionOrNull()
        if (failure != null && mountResult.isSuccess) Text(failure.message ?: "These print settings do not work.", style = MaterialTheme.typography.bodySmall)
        val plan by produceState<Result<PrintPlan>?>(null, stencil, options.getOrNull()) {
            value = null
            val wanted = options.getOrNull()
            if (stencil != null && wanted != null) {
                value = withContext(Dispatchers.Default) { runCatching { PrintPdf.plan(stencil, wanted) } }
            }
        }
        plan?.let { result ->
            val ready = result.getOrNull()
            if (ready == null) {
                Text(result.exceptionOrNull()?.message ?: "This cannot be printed.", style = MaterialTheme.typography.bodySmall)
            } else {
                val shown = state.shownSheet?.let { sheet -> ready.sheets.firstOrNull { it.sheet == sheet } }
                val boards = ready.boards
                val line = if (boards != null) {
                    val where = "${boards.cols} across, ${boards.rows} down, ${if (boards.landscape) "landscape" else "portrait"}"
                    if (shown != null) {
                        val tabs = shown.boards.sumOf { it.tabs.size }
                        val lacking = shown.boards.count { it.missingTabs.isNotEmpty() }
                        "${boards.count} board${if (boards.count == 1) "" else "s"} on sheet ${shown.sheet + 1} ($where), ${shown.boards.count { it.blank }} blank; " +
                            "${shown.printed.size} pages, $tabs tab${if (tabs == 1) "" else "s"}" +
                            if (lacking > 0) ", $lacking board${if (lacking == 1) "" else "s"} with no usable tab on a shared side" else ""
                    } else {
                        "${boards.count} board${if (boards.count == 1) "" else "s"} per sheet ($where), ${boards.count * ready.sheets.size} boards in all, " +
                            "${ready.blankBoards} blank; ${ready.printedPages} pages, ${ready.tabCount} tabs" +
                            if (ready.boardsMissingTabs > 0) ", ${ready.boardsMissingTabs} board${if (ready.boardsMissingTabs == 1) "" else "s"} with no usable tab on a shared side" else ""
                    }
                } else if (shown != null) {
                    "${shown.printed.size} pages on sheet ${shown.sheet + 1} (${ready.grid.cols} across, ${ready.grid.rows} down), " +
                        "${shown.blank.size} skipped as blank"
                } else {
                    "${ready.printedPages} pages over ${ready.sheets.size} sheets (${ready.grid.cols} across, ${ready.grid.rows} down each), " +
                        "${ready.blankPages} skipped as blank"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = subdued)
            }
        }
        val ok = canExport && options.isSuccess
        state.shownSheet?.let { sheet ->
            OutlinedButton(
                onClick = { onExport("Sheet ${sheet + 1} print PDF", sheet) },
                enabled = ok,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (exporting != null && exporting.endsWith("print PDF")) "Preparing PDF..." else "Download sheet ${sheet + 1} print PDF") }
        }
        OutlinedButton(
            onClick = { onExport("All sheets print PDF", null) },
            enabled = ok,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download all sheets print PDF") }
    }
}

/**
 * A small numeric text field in the app's monochrome: [value] shown to [decimals] places, [onValue] called
 * with each valid number as it is typed (a comma counts as a point). When [value] changes from outside, as
 * "Fit to whole pages" does, the text follows, but typing "2." is never rewritten under the cursor.
 */
@Composable
private fun NumberField(title: String, value: Double, decimals: Int, onValue: (Double) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(fixedDecimals(value, decimals)) }
    LaunchedEffect(value) {
        val typed = text.replace(',', '.').toDoubleOrNull()
        if (typed == null || abs(typed - value) > 0.5 / 10.0.pow(decimals)) text = fixedDecimals(value, decimals)
    }
    Column(modifier) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        BasicTextField(
            value = text,
            onValueChange = { next ->
                text = next
                next.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fieldBox(title),
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { inner() } },
        )
    }
}

/** The look of a text box: a dark rounded field with a thin outline, named [title] for screen readers. Shared by the number and search fields. */
@Composable
internal fun Modifier.fieldBox(title: String): Modifier {
    val scheme = MaterialTheme.colorScheme
    return fillMaxWidth().padding(top = 4.dp).height(40.dp)
        .clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant)
        .border(1.dp, scheme.outline, RoundedCornerShape(6.dp))
        .semantics { contentDescription = title }
}

/** [value] to [decimals] places without locale or platform formatting. */
private fun fixedDecimals(value: Double, decimals: Int): String {
    val scale = 10.0.pow(decimals)
    val scaled = (abs(value) * scale).roundToInt()
    val whole = scaled / scale.roundToInt()
    val fraction = (scaled % scale.roundToInt()).toString().padStart(decimals, '0')
    return (if (value < 0 && scaled != 0) "-" else "") + whole + if (decimals > 0) ".$fraction" else ""
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** "All", then one swatch per sheet in paint order. It scrolls, so the number of sheets is not its problem. */
@Composable
private fun SheetStrip(sheets: List<Sheet>, shown: Int?, onShow: (Int?) -> Unit) {
    @Composable
    fun swatch(i: Int) {
            val selected = shown == i
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(sheets[i].color))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable(role = Role.RadioButton) { onShow(i) }
                    .semantics { contentDescription = "Sheet ${i + 1} of ${sheets.size}" },
                contentAlignment = Alignment.Center,
            ) {
                // The number is drawn in whichever of black and white reads better against the swatch.
                Text("${i + 1}", color = if (Color(sheets[i].color).isLight()) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelMedium)
            }
    }
    // Up to this many sheets wrap, so a mouse can reach every swatch without dragging a row sideways
    // (30 chosen cans stay in view); beyond it the strip scrolls so a thousand sheets stay cheap.
    if (sheets.size <= WRAPPED_SHEETS) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("All", selected = shown == null) { onShow(null) }
            for (i in sheets.indices) swatch(i)
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Choice("All", selected = shown == null) { onShow(null) } }
            items(sheets.size) { i -> swatch(i) }
        }
    }
}

/** The most sheets the strip wraps over several lines. */
private const val WRAPPED_SHEETS = 48

/**
 * Whether black text reads better on this colour than white. Relative luminance (which weighs
 * green far above blue and works in linear light) crosses over at 0.179, where black and white
 * give equal contrast; a flat 0.5 on gamma values would put white on a bright orange or green.
 */
internal fun Color.isLight(): Boolean = luminance() > 0.179f

@Composable
internal fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun Stepper(value: Int, range: IntRange, label: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > range.first,
            modifier = Modifier.semantics { contentDescription = "Fewer $label" }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < range.last,
            modifier = Modifier.semantics { contentDescription = "More $label" }) { Text("+") }
    }
}

@Composable
private fun LabelledSlider(
    title: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.labelMedium)
        }
        val scheme = MaterialTheme.colorScheme
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            // Material's default track is tinted; this app has no tint to give it.
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.outline,
            ),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** A dim checkerboard, drawn behind: what transparency looks like. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 12.dp.toPx()
    val dark = Color(0xFF161616)
    val light = Color(0xFF202020)
    drawRect(dark)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(light, topLeft = Offset(x, y), size = Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

/** Fixed one decimal place without locale or platform formatting dependencies. */
private fun formatLightValue(value: Float): String =
    (value * 10f).roundToInt().let { (it / 10).toString() + "." + kotlin.math.abs(it % 10) }
