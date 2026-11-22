package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.stencilartist.core.Base
import com.hereliesaz.stencilartist.core.Sheet
import com.hereliesaz.stencilartist.core.StencilPrintPdf
import com.hereliesaz.stencilartist.core.StencilPrintOptions
import com.hereliesaz.stencilartist.core.TonalParams
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Below this width the controls go under the preview instead of beside it. */
private val WIDE_LAYOUT = 720.dp

/** The whole app: one screen, shared by Android and the web. */
@Composable
fun App() {
    StencilArtistTheme {
        val scope = rememberCoroutineScope()
        val models = rememberModelLoader()
        val state = remember { StencilState(scope, SubjectIsolator(models)) }
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
                contentDescription = when (val sheet = state.shownSheet) {
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
    val params = state.params
    val saver = rememberFileSaver()
    val exportScope = rememberCoroutineScope()
    var printWidthMm by remember { mutableFloatStateOf(203.2f) }
    var exporting by remember { mutableStateOf(false) }
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.source == null) "Open image" else "Open another image")
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }

        Section("Show") {
            SheetStrip(state.separation?.sheets.orEmpty(), state.shownSheet, state::show)
        }

        Section("Background") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Remove", selected = state.removeBackground, modifier = Modifier.weight(1f)) { state.removeBackground(true) }
                Choice("Keep", selected = !state.removeBackground, modifier = Modifier.weight(1f)) { state.removeBackground(false) }
            }
        }

        Section("Sheets") {
            Stepper(
                value = params.sheets,
                range = 1..TonalParams.MAX_SHEETS,
                label = "sheets",
                onChange = { state.update(params.copy(sheets = it, colors = null)) },
            )
        }

        Section("Base") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((base, name) in listOf(Base.AUTO to "Auto", Base.DARK to "Dark", Base.LIGHT to "Light")) {
                    Choice(name, selected = params.base == base, modifier = Modifier.weight(1f)) {
                        state.update(params.copy(base = base))
                    }
                }
            }
        }

        // Smoothing is a median filter and must be odd: the slider counts in steps of two.
        LabelledSlider("Smoothing", "${params.smoothing} px", (params.smoothing - 1) / 2f, 0f..15f) {
            state.update(params.copy(smoothing = it.roundToInt() * 2 + 1))
        }
        LabelledSlider("Edge closing", "${params.closing} px", params.closing.toFloat(), 1f..25f) {
            state.update(params.copy(closing = it.roundToInt()))
        }
        LabelledSlider("Tonal bias", params.bias.let { if (it > 0) "+$it" else "$it" }, params.bias.toFloat(), -100f..100f) {
            state.update(params.copy(bias = it.roundToInt()))
        }

        Section("Print") {
            LabelledSlider("Physical width", printWidthMm.roundToInt().toString() + " mm",
                printWidthMm, 50f..1000f) { printWidthMm = it }
            OutlinedButton(
                onClick = {
                    val separation = state.separation ?: return@OutlinedButton
                    val width = printWidthMm.toDouble()
                    exportScope.launch {
                        exporting = true
                        try {
                            val pdf = withContext(Dispatchers.Default) {
                                StencilPrintPdf.make(separation, StencilPrintOptions(widthMm = width))
                            }
                            saver.save(ExportFile("stencil-print.pdf", "application/pdf", pdf)) {
                                if (!it) state.fail("PDF wasn't saved.")
                            }
                        } catch (error: Exception) {
                            state.fail("PDF export failed: " + (error.message ?: "unknown error"))
                        } finally {
                            exporting = false
                        }
                    }
                },
                enabled = state.separation != null && !state.busy && !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exporting) "Preparing PDF..." else "Download stencil PDF")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** "All", then one swatch per sheet in paint order. It scrolls, so the number of sheets is not its problem. */
@Composable
private fun SheetStrip(sheets: List<Sheet>, shown: Int?, onShow: (Int?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { Choice("All", selected = shown == null) { onShow(null) } }
        items(sheets.size) { i ->
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
                // The number is drawn in whichever of black and white reads against the swatch.
                Text("${i + 1}", color = if (Color(sheets[i].color).isLight()) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun Color.isLight(): Boolean = 0.299f * red + 0.587f * green + 0.114f * blue > 0.5f

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
private fun Stepper(value: Int, range: IntRange, label: String, onChange: (Int) -> Unit) {
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
