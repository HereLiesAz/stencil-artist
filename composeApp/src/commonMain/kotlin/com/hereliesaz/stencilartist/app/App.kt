package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.stencilartist.core.Base
import com.hereliesaz.stencilartist.core.ColorOrder
import com.hereliesaz.stencilartist.core.export.Board
import com.hereliesaz.stencilartist.core.export.BoardGrid
import com.hereliesaz.stencilartist.core.export.GcodeOptions
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
import com.hereliesaz.stencilartist.core.StrokeStyler
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** One decision per stage; all stages use the same in-memory processing and export state. */
private val STUDIO_STEPS = listOf(
    "Source" to "Choose your image and decide what belongs in the stencil. Removing the background changes the subject's silhouette, not its colours.",
    "Light" to "Lighting changes the source before any sheet is cut. This is estimated surface shading, not a true 3D extrusion or cast-shadow renderer.",
    "Layers" to "Each sheet is one pass of paint. Fewer sheets are easier to align; more sheets preserve additional tones or colours.",
    "Edges" to "Choose how paint openings are constructed. Keep fine detail intact, then adjust edge cleanup only where necessary.",
    "Inspect" to "Compare the source with your result, then inspect each cut sheet. Tiny unsupported islands may need special handling.",
    "Images" to "Save a finished image, an individual sheet, or an aligned layer pack for another app.",
    "Print" to "Set the physical width, then print tiled pages at actual size. Their alignment marks preserve the cut across page joins.",
    "Cutter" to "Set the physical width and your cutter or mat. Check the machine settings before sending any toolpath to hardware.",
)

/** Short loading advice; corresponding fine-cut, island and gradient notes also print by location. */
private val LOADING_TIPS = listOf(
    "CUT" to "Cut fine details last and lift their waste slowly. A clean blade is more useful than heroic optimism.",
    "PAINT" to "For a soft gradient, lift the uncut stencil edge slightly. Try the overspray on scrap first.",
    "MAKE" to "Fewer sheets are easier to register; more sheets can preserve delicate changes in value.",
    "CUT" to "Check enclosed islands before cutting. A bridge or a later paint layer can preserve the detail.",
    "PAINT" to "Use light, even passes. Too much paint finds its way under even a firmly held stencil.",
    "MAKE" to "Compare each sheet against the original at high zoom before printing at full size.",
    "PAINT" to "Align every layer to the same marks, not to the last colour you happened to spray.",
    "CUT" to "Turn the stencil material around tight corners instead of forcing the blade to twist.",
)

/** The whole app: one screen, shared by Android and the web. */
@Composable
fun App() {
    StencilArtistTheme {
        val scope = rememberCoroutineScope()
        val models = rememberModelLoader()
        val catalogueLoader = rememberCatalogueLoader()
        val store = rememberKeyValueStore()
        val state = remember { StencilState(scope, SubjectIsolator(models), DepthEstimator(models), catalogueLoader, store, models) }
        val pick = rememberImagePicker(onPicked = state::open, onError = state::fail)
        var loadingTip by remember { mutableIntStateOf(-1) }
        LaunchedEffect(state.busy) {
            if (state.busy) {
                loadingTip = (loadingTip + 1) % LOADING_TIPS.size
                while (true) {
                    delay(4200)
                    loadingTip = (loadingTip + 1) % LOADING_TIPS.size
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().then(if (state.busy) Modifier.blur(8.dp) else Modifier),
                ) {
                    if (maxWidth >= WIDE_LAYOUT) {
                        Row(Modifier.fillMaxSize()) {
                            Preview(state, pick, Modifier.weight(1f).fillMaxHeight())
                            Controls(state, pick, Modifier.width(370.dp).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Preview(state, pick, Modifier.weight(1f).fillMaxWidth())
                            Controls(state, pick, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
                if (state.busy) {
                    // A complete modal scrim: blur and dim BOTH the preview and the
                    // settings. Do not let a tap reach outdated controls mid-generation.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.67f)).clickable { })
                    val advice = LOADING_TIPS[loadingTip.coerceAtLeast(0)]
                    Column(
                        Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("PREPARING STENCIL · ${advice.first}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(advice.second, style = MaterialTheme.typography.bodyMedium)
                        // This measures completed pipeline stages, not estimated time.
                        LinearProgressIndicator(
                            progress = { state.processingProgress },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A permanent comparison workspace. Source and generated result share the same zoom and pan,
 * so a detail is never found on one side and lost on the other.
 */
@Composable
private fun Preview(state: StencilState, pick: () -> Unit, modifier: Modifier) {
    var split by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var paneBounds by remember { mutableStateOf(IntSize.Zero) }
    val sourceBitmap = remember(state.source) {
        state.source?.let { argbToImageBitmap(it.pixels, it.width, it.height) }
    }
    LaunchedEffect(state.source) {
        zoom = 1f
        pan = Offset.Zero
    }
    // ContentScale.Fit may letterbox images. The content rather than the containing pane
    // determines how far an image can move without disappearing under the clipping rectangle.
    val clampPan: (Offset, Float, IntSize) -> Offset = { offset, scale, bounds ->
        val source = state.source
        if (source == null || bounds.width <= 0 || bounds.height <= 0 || scale <= 1f) Offset.Zero
        else {
            val fittedScale = minOf(bounds.width.toFloat() / source.width, bounds.height.toFloat() / source.height)
            val maxX = maxOf(0f, (source.width * fittedScale * scale - bounds.width) / 2f)
            val maxY = maxOf(0f, (source.height * fittedScale * scale - bounds.height) / 2f)
            Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
        }
    }
    val applyTransform: (Offset, Float, Offset, IntSize) -> Unit = { centroid, factor, delta, bounds ->
        val next = (zoom * factor).coerceIn(1f, 8f)
        val ratio = next / zoom
        // Keep the pixel under the gesture's centroid in place while zooming.
        val focus = centroid - Offset(bounds.width / 2f, bounds.height / 2f)
        val nextPan = pan * ratio + focus * (1f - ratio) + delta
        zoom = next
        pan = clampPan(nextPan, next, bounds)
    }
    val zoomTo: (Float) -> Unit = { target ->
        val next = target.coerceIn(1f, 8f)
        pan = clampPan(pan * (next / zoom), next, paneBounds)
        zoom = next
    }
    LaunchedEffect(split, paneBounds) { pan = clampPan(pan, zoom, paneBounds) }

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("WORKSPACE", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (state.source != null) {
                    Choice("Compare", selected = split) { split = true }
                    Choice("Single", selected = !split) { split = false }
                }
            }
            val result = state.preview
            if (result == null) {
                Box(Modifier.weight(1f).fillMaxWidth().checkerboard(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Stencil Artist", style = MaterialTheme.typography.displaySmall)
                        Text("From image to paint-ready layers",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = pick) { Text("Open image") }
                    }
                }
            } else {
                val resultLabel = if (state.viewProcessedImage) "PREPARED IMAGE"
                    else state.shownSheet?.let { "SHEET ${it + 1}" } ?: "FINISHED STENCIL"
                if (split && sourceBitmap != null) {
                    Row(Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewPane("SOURCE", sourceBitmap, zoom, pan, applyTransform,
                            { paneBounds = it }, Modifier.weight(1f).fillMaxHeight())
                        PreviewPane(resultLabel, result, zoom, pan, applyTransform,
                            { paneBounds = it }, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    PreviewPane(resultLabel, result, zoom, pan, applyTransform,
                        { paneBounds = it }, Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (result != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pinch to zoom · drag to pan", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { zoomTo(zoom / 1.5f) },
                        enabled = zoom > 1f,
                        modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
                    OutlinedButton(onClick = { zoomTo(1f) },
                        modifier = Modifier.semantics { contentDescription = "Reset image zoom and position" }) {
                        Text("${(zoom * 100).roundToInt()}%")
                    }
                    OutlinedButton(onClick = { zoomTo(zoom * 1.5f) },
                        enabled = zoom < 8f,
                        modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
                }
            }
        }
    }
}

/** The viewer owns no image processing: transform gestures alter only its clipped presentation. */
@Composable
private fun PreviewPane(
    title: String,
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    zoom: Float,
    pan: Offset,
    onTransform: (Offset, Float, Offset, IntSize) -> Unit,
    onBounds: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val gestures = rememberTransformableState { centroid, factor, movement, _ ->
        onTransform(centroid, factor, movement, bounds)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .checkerboard().onSizeChanged { bounds = it; onBounds(it) }.transformable(gestures),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
            )
        }
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
    var safeBridgeWidthMm by remember { mutableFloatStateOf(1.5f) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var cutterFormat by remember { mutableStateOf(CutterFormat.SVG) }
    // -1: whole, 0..presets.lastIndex: preset, presets.size: custom mat, +1: roll.
    var cutterFitChoice by remember { mutableIntStateOf(-1) }
    var cutterCustomWidthMm by remember { mutableDoubleStateOf(304.8) }
    var cutterCustomHeightMm by remember { mutableDoubleStateOf(304.8) }
    var cutterRollWidthMm by remember { mutableDoubleStateOf(304.8) }
    var cutterEdgeMm by remember { mutableDoubleStateOf(6.35) }
    var cutterOverlapMm by remember { mutableDoubleStateOf(10.0) }
    var cutterCutLines by remember { mutableStateOf(true) }
    var gcodeLaser by remember { mutableStateOf(true) }
    var gcodeFeed by remember { mutableDoubleStateOf(1200.0) }
    var gcodeTravel by remember { mutableDoubleStateOf(6000.0) }
    var gcodePower by remember { mutableDoubleStateOf(1000.0) }
    var gcodePasses by remember { mutableIntStateOf(1) }
    var gcodeSafeZ by remember { mutableDoubleStateOf(5.0) }
    var gcodeCutZ by remember { mutableDoubleStateOf(-1.0) }
    // A temporarily blank or malformed number must not silently export its previous value.
    var invalidCutterDrafts by remember { mutableStateOf(emptySet<String>()) }
    val recordCutterDraft: (String, String, Boolean) -> Unit = { field, raw, whole ->
        val number = raw.replace(',', '.').toDoubleOrNull()
        val valid = number != null && number.isFinite() &&
            (!whole || (number in 1.0..1000.0 && number == number.toInt().toDouble()))
        invalidCutterDrafts = if (valid) invalidCutterDrafts - field else invalidCutterDrafts + field
    }
    val print = remember { PrintSettings() }
    var step by remember { mutableIntStateOf(0) }
    var advancedLight by remember { mutableStateOf(false) }
    var advancedEdges by remember { mutableStateOf(false) }
    val stepScroll = rememberScrollState()
    LaunchedEffect(step) { stepScroll.scrollTo(0) }
    val openStep: (Int) -> Unit = { next ->
        step = next.coerceIn(STUDIO_STEPS.indices)
        if (step <= 1) state.showProcessed(true) else state.show(state.shownSheet)
    }
    LaunchedEffect(state.source) {
        // A new image begins at preparation, where its isolated base is the useful comparison.
        if (state.source != null) openStep(0)
    }

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
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(stepScroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("MAKE A STENCIL", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { (step + 1).toFloat() / STUDIO_STEPS.size },
            modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(STUDIO_STEPS.size) { index ->
                Choice("${index + 1} ${STUDIO_STEPS[index].first}", selected = step == index) {
                    openStep(index)
                }
            }
        }
        Text(STUDIO_STEPS[step].first, style = MaterialTheme.typography.headlineMedium)
        Text(STUDIO_STEPS[step].second, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openStep(step - 1) }, enabled = step > 0,
                modifier = Modifier.weight(1f)) { Text("← Back") }
            Button(onClick = { openStep(step + 1) }, enabled = step < STUDIO_STEPS.lastIndex,
                modifier = Modifier.weight(1f)) {
                Text(if (step < STUDIO_STEPS.lastIndex) "Next: ${STUDIO_STEPS[(step + 1).coerceAtMost(STUDIO_STEPS.lastIndex)].first}" else "All steps")
            }
        }

        if (step == 0) {
        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.source == null) "Open image" else "Open another image")
        }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }

        if (step == 4) Section("Show") {
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

        if (step == 0) Section("Background") {
            Text("Removing the background usually means fewer cuts. Depth assistance can help uncertain edges; the app keeps confident foreground/background decisions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionCard("Cut out subject", "Keep the subject; remove surrounding scenery.",
                    state.removeBackground, Modifier.weight(1f)) { state.removeBackground(true) }
                DecisionCard("Keep the frame", "Stencil the entire image, including its background.",
                    !state.removeBackground, Modifier.weight(1f)) { state.removeBackground(false) }
            }
            if (state.removeBackground) {
                Text("AT THE OUTLINE", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecisionCard("Depth-assisted", "Refine uncertain edges using estimated distance.",
                        state.depthAssistedBackground, Modifier.weight(1f)) { state.depthAssistedBackground(true) }
                    DecisionCard("Mask only", "Use only the foreground mask; no depth refinement.",
                        !state.depthAssistedBackground, Modifier.weight(1f)) { state.depthAssistedBackground(false) }
                }
            }
        }

        if (step == 1) Section("Depth + lighting") {
            Text("Use this only when a change in light would make the eventual paint layers clearer. The current tool shades the photograph from estimated depth; it cannot cast shadows or infer hidden surfaces.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val light = state.lighting
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionCard("Keep original", "Use the photograph's existing highlights and shadows.",
                    !light.enabled, Modifier.weight(1f)) {
                    state.updateLighting(light.copy(enabled = false))
                }
                DecisionCard("Relight surface", "Change apparent light using estimated depth.",
                    light.enabled, Modifier.weight(1f)) {
                    state.updateLighting(light.copy(enabled = true))
                }
            }
            if (light.enabled) {
                OutlinedButton(onClick = { advancedLight = !advancedLight },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (advancedLight) "Hide lighting adjustments" else "Adjust direction and strength")
                }
            }
            if (light.enabled && advancedLight) {
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

        if (step == 2) {
        Section("Separate by") {
            Text("Tone makes a brightness-based stack. Colour matches the source's hues or the real cans you choose.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionCard("By tone", "Build a dark-to-light or light-to-dark set of values.",
                    state.mode == SeparationMode.TONE, Modifier.weight(1f)) {
                    state.selectMode(SeparationMode.TONE)
                }
                DecisionCard("By colour", "Preserve hues, or match real cans of paint.",
                    state.mode == SeparationMode.COLOUR, Modifier.weight(1f)) {
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
                Text("The base is the first coat. Auto chooses its direction from the image; force dark or light when your wall or paint plan calls for it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Paint order changes which colours can cover earlier cuts. Auto chooses; the other options put the sequence in your hands.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        }
        if (step == 3) {
        Text("Brush strokes shape broad openings with directional marks. Classic preserves the original cut geometry.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Smoothing is a median filter and must be odd: the slider counts in steps of two.
        if (advancedEdges) Text("Smoothing reduces speckled edges; closing seals tiny gaps. Bias moves tonal thresholds. Inspect small details after each change.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (advancedEdges) LabelledSlider("Smoothing", "${state.smoothing} px", (state.smoothing - 1) / 2f, 0f..15f) {
            state.setSmoothing(it.roundToInt() * 2 + 1)
        }
        if (advancedEdges) LabelledSlider("Edge closing", "${state.closing} px", state.closing.toFloat(), 1f..25f) {
            state.setClosing(it.roundToInt())
        }
        Section("Stroke construction") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecisionCard("Classic cuts", "Retain the original contiguous mask openings.",
                    !state.strokeBased, Modifier.weight(1f)) {
                    state.useStrokeBased(false)
                }
                DecisionCard("Brush marks", "Use fine directional openings in large regions.",
                    state.strokeBased, Modifier.weight(1f)) {
                    state.useStrokeBased(true)
                }
            }
            if (state.strokeBased && state.supportsNeuralStrokes) {
                val importModel = rememberNeuralModelImporter({ state.selectStrokeEngine(state.strokeEngine) }, state::fail)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (engine in StrokeEngine.entries) {
                        Choice(engine.label, selected = state.strokeEngine == engine) { state.selectStrokeEngine(engine) }
                    }
                }
                val parts = when (state.strokeEngine) {
                    StrokeEngine.TRANSFORMER -> listOf(NeuralModelPart.PAINT_TRANSFORMER)
                    StrokeEngine.COMPOSITIONAL -> listOf(NeuralModelPart.COMPOSITIONAL_REGION, NeuralModelPart.COMPOSITIONAL_PAINTER)
                    StrokeEngine.PROCEDURAL -> emptyList()
                }
                if (parts.isNotEmpty()) Text("Single-pass stencil strokes from the pretrained model.", style = MaterialTheme.typography.bodySmall)
                for (part in parts) OutlinedButton(onClick = { importModel(part) }) { Text("Import ${part.fileName}") }
            }
            if (state.strokeBased && state.strokeEngine == StrokeEngine.PROCEDURAL) {
                Text(
                    "Adjust the width of the paint marks. Any area narrower than the brush keeps its original solid fill, rather than forcing in a stroke.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabelledSlider(
                    "Brush size",
                    "${state.strokeWidthPixels} px",
                    state.strokeWidthPixels.toFloat(),
                    StrokeStyler.MIN_BRUSH_WIDTH_PIXELS.toFloat()..StrokeStyler.MAX_BRUSH_WIDTH_PIXELS.toFloat(),
                ) { state.chooseStrokeWidth(it.roundToInt()) }
            }
        }
        OutlinedButton(onClick = { advancedEdges = !advancedEdges }, modifier = Modifier.fillMaxWidth()) {
            Text(if (advancedEdges) "Hide edge adjustments" else "Fine-tune smoothing, closing and tonal bias")
        }
        if (state.mode == SeparationMode.TONE && advancedEdges) {
            LabelledSlider("Tonal bias", tone.bias.let { if (it > 0) "+$it" else "$it" }, tone.bias.toFloat(), -100f..100f) {
                state.update(tone.copy(bias = it.roundToInt()))
            }
        }

        }
        if (step == 4 && state.snapped) PaintListSection(state)

        if (step == 5) Section("Raster PNG") {
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

        if (step == 6 || step == 7) Section("Physical size") {
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
                LabelledSlider(
                    "Minimum safe stock bridge",
                    "${oneDecimal(safeBridgeWidthMm)} mm",
                    safeBridgeWidthMm, 0.5f..5f,
                ) { safeBridgeWidthMm = it }
                val nominalBridgeMm = printWidthMm * NOMINAL_BRIDGE_PIXELS / stencil.width
                val belowMaterialMinimum = nominalBridgeMm < safeBridgeWidthMm
                Text(
                    if (belowMaterialMinimum) {
                        "Bridge warning: the planner's ${NOMINAL_BRIDGE_PIXELS}px nominal support is about " +
                            "${oneDecimal(nominalBridgeMm)} mm at this print size, below your " +
                            "${oneDecimal(safeBridgeWidthMm)} mm material minimum. Inspect and widen " +
                            "any generated bridges before cutting."
                    } else {
                        "The planner's ${NOMINAL_BRIDGE_PIXELS}px nominal support is about " +
                            "${oneDecimal(nominalBridgeMm)} mm at this size. This is a pixel-scale " +
                            "estimate, not a guarantee of material strength."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (belowMaterialMinimum) MaterialTheme.colorScheme.error else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (step == 7) Section("Cutter files") {
            // These rows wrap rather than scroll: a mouse cannot drag a row sideways, and a choice
            // that is off the edge of the panel is a choice nobody finds.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (format in CutterFormat.entries) {
                    Choice(format.label, selected = cutterFormat == format) { cutterFormat = format }
                }
            }
            // All cutter formats share the same fit: a preset, user mat, or unlimited roll.
            // Validate through TileSpec and Tiling so an invalid or enormous plan cannot
            // reach a save request. A roll is always one row of full-length strips.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Whole", selected = cutterFitChoice == -1) { cutterFitChoice = -1 }
                for ((index, preset) in CUTTER_FITS.withIndex()) {
                    Choice(preset.name, selected = cutterFitChoice == index) { cutterFitChoice = index }
                }
                Choice("Custom mat", selected = cutterFitChoice == CUTTER_FITS.size) {
                    cutterFitChoice = CUTTER_FITS.size
                }
                Choice("Roll", selected = cutterFitChoice == CUTTER_FITS.size + 1) {
                    cutterFitChoice = CUTTER_FITS.size + 1
                }
            }
            val customMat = cutterFitChoice == CUTTER_FITS.size
            val roll = cutterFitChoice == CUTTER_FITS.size + 1
            if (customMat || roll) {
                if (customMat) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Mat width (mm)", cutterCustomWidthMm, 1,
                            { cutterCustomWidthMm = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("matWidth", it, false) })
                        NumberField("Mat height (mm)", cutterCustomHeightMm, 1,
                            { cutterCustomHeightMm = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("matHeight", it, false) })
                    }
                } else {
                    NumberField("Roll width (mm)", cutterRollWidthMm, 1, { cutterRollWidthMm = it },
                        onDraft = { recordCutterDraft("rollWidth", it, false) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Edge margin (mm)", cutterEdgeMm, 2,
                        { cutterEdgeMm = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("edge", it, false) })
                    NumberField("Tile overlap (mm)", cutterOverlapMm, 2,
                        { cutterOverlapMm = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("overlap", it, false) })
                }
            }
            val fitResult = runCatching {
                when (cutterFitChoice) {
                    -1 -> null
                    in CUTTER_FITS.indices -> CUTTER_FITS[cutterFitChoice]
                    CUTTER_FITS.size -> TileSpec.custom(
                        cutterCustomWidthMm, cutterCustomHeightMm, cutterEdgeMm, cutterOverlapMm,
                    )
                    else -> TileSpec.roll(cutterRollWidthMm, cutterEdgeMm, cutterOverlapMm)
                }
            }
            val fit = fitResult.getOrNull()
            val stencil = state.separation
            val tilePlan = if (fit != null && stencil != null) runCatching {
                Tiling.plan(
                    printWidthMm.toDouble(),
                    printWidthMm.toDouble() * stencil.height / stencil.width,
                    fit,
                )
            } else null
            val relevantFitDrafts = when {
                customMat -> setOf("matWidth", "matHeight", "edge", "overlap")
                roll -> setOf("rollWidth", "edge", "overlap")
                else -> emptySet()
            }
            val badFitDraft = invalidCutterDrafts.any { it in relevantFitDrafts }
            val fitError = if (badFitDraft) "Enter a valid number in each visible size field."
                else fitResult.exceptionOrNull()?.message ?: tilePlan?.exceptionOrNull()?.message
            if (fitError != null) {
                Text("Invalid cutter size: $fitError", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                tilePlan?.getOrNull()?.let { plan ->
                    Text(
                        "${plan.tiles.size} ${fit?.name ?: "cutting"} piece" +
                            (if (plan.tiles.size == 1) "" else "s") +
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
            if (cutterFormat == CutterFormat.GCODE) {
                Text("G-CODE MACHINE SETTINGS", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Laser", selected = gcodeLaser, modifier = Modifier.weight(1f)) { gcodeLaser = true }
                    Choice("Router", selected = !gcodeLaser, modifier = Modifier.weight(1f)) { gcodeLaser = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Cut feed (mm/min)", gcodeFeed, 0, { gcodeFeed = it }, Modifier.weight(1f),
                        onDraft = { recordCutterDraft("feed", it, false) })
                    NumberField("Travel (mm/min)", gcodeTravel, 0, { gcodeTravel = it }, Modifier.weight(1f),
                        onDraft = { recordCutterDraft("travel", it, false) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(if (gcodeLaser) "Laser power (S)" else "Spindle speed (S)",
                        gcodePower, 0, { gcodePower = it }, Modifier.weight(1f),
                        onDraft = { recordCutterDraft("power", it, false) })
                    NumberField("Passes", gcodePasses.toDouble(), 0, {
                        if (it.isFinite() && it in 1.0..1000.0 && it == it.toInt().toDouble()) {
                            gcodePasses = it.toInt()
                        }
                    }, Modifier.weight(1f),
                        onDraft = { recordCutterDraft("passes", it, true) })
                }
                if (!gcodeLaser) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Safe Z (mm)", gcodeSafeZ, 2, { gcodeSafeZ = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("safeZ", it, false) })
                        NumberField("Cut Z (mm)", gcodeCutZ, 2, { gcodeCutZ = it }, Modifier.weight(1f),
                            onDraft = { recordCutterDraft("cutZ", it, false) })
                    }
                }
                Text(
                    "Power S uses your controller's range, not a universal percentage. " +
                        "Verify feed, power, depth and tool-off moves with a dry run before cutting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val gcodeResult = if (cutterFormat == CutterFormat.GCODE) runCatching {
                GcodeOptions(
                    feedMmPerMin = gcodeFeed,
                    travelMmPerMin = gcodeTravel,
                    power = gcodePower,
                    passes = gcodePasses,
                    laserMode = gcodeLaser,
                    // Z is unused for lasers: a previous router draft must not block laser exports.
                    safeZMm = if (gcodeLaser) 5.0 else gcodeSafeZ,
                    cutZMm = if (gcodeLaser) -1.0 else gcodeCutZ,
                )
            } else Result.success(GcodeOptions())
            val relevantGcodeDrafts = if (gcodeLaser) setOf("feed", "travel", "power", "passes")
                else setOf("feed", "travel", "power", "passes", "safeZ", "cutZ")
            val badGcodeDraft = cutterFormat == CutterFormat.GCODE &&
                invalidCutterDrafts.any { it in relevantGcodeDrafts }
            val gcodeError = if (badGcodeDraft) "Enter a valid number in each visible G-code field."
                else gcodeResult.exceptionOrNull()?.message
            gcodeError?.let { error ->
                Text("Invalid G-code settings: $error", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            val format = cutterFormat
            val lines = cutterCutLines
            val width = printWidthMm.toDouble()
            val options = gcodeResult.getOrNull()
            val cutterReady = canExport && fitError == null && gcodeError == null && options != null
            state.shownSheet?.let { sheet ->
                OutlinedButton(
                    onClick = {
                        if (cutterReady && options != null) startExport("Sheet ${sheet + 1} ${format.label}") {
                            CutterExports.make(it, format, width, sheet, fit, lines, options)
                        }
                    },
                    enabled = cutterReady,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download sheet ${sheet + 1} ${format.label}") }
            }
            OutlinedButton(
                onClick = {
                    if (cutterReady && options != null) startExport("All sheets ${format.label}") {
                        CutterExports.make(it, format, width, null, fit, lines, options)
                    }
                },
                enabled = cutterReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download all sheets ${format.label}") }
        }

        if (step == 6) PrintSection(
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openStep(step - 1) }, enabled = step > 0,
                modifier = Modifier.weight(1f)) { Text("Previous") }
            Button(onClick = { openStep(step + 1) }, enabled = step < STUDIO_STEPS.lastIndex,
                modifier = Modifier.weight(1f)) {
                Text(if (step < STUDIO_STEPS.lastIndex) "Next: ${STUDIO_STEPS[(step + 1).coerceAtMost(STUDIO_STEPS.lastIndex)].first}" else "Finished")
            }
        }
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
 * Optional [onDraft] also sees incomplete text so cutter downloads can be disabled while a
 * numeric field is invalid, rather than exporting a stale last-valid number.
 */
@Composable
private fun NumberField(
    title: String,
    value: Double,
    decimals: Int,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onDraft: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(fixedDecimals(value, decimals)) }
    LaunchedEffect(value) {
        val typed = text.replace(',', '.').toDoubleOrNull()
        if (typed == null || abs(typed - value) > 0.5 / 10.0.pow(decimals)) {
            text = fixedDecimals(value, decimals)
            onDraft?.invoke(text)
        }
    }
    // Hidden fields are disposed when switching formats. On return their text is recreated
    // from the last valid value, so invalidate no export based on a stale prior draft.
    LaunchedEffect(Unit) { onDraft?.invoke(text) }
    Column(modifier) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        BasicTextField(
            value = text,
            onValueChange = { next ->
                text = next
                onDraft?.invoke(next)
                // Kotlin parses NaN and Infinity; storing either would crash fixedDecimals
                // when this field is recreated and could leak invalid machine parameters.
                next.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onValue)
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

/**
 * A primary creative decision is a named outcome with its trade-off, not an unexplained
 * on/off switch. Keep secondary numeric controls in their focused stage.
 */
@Composable
private fun DecisionCard(
    title: String,
    explanation: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) scheme.primary else scheme.outline, RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface)
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
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
