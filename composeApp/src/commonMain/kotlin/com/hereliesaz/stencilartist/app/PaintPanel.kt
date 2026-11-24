package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.hereliesaz.stencilartist.core.PaletteColor
import com.hereliesaz.stencilartist.core.paint.Can
import com.hereliesaz.stencilartist.core.paint.CanFilter
import com.hereliesaz.stencilartist.core.paint.HexSource
import com.hereliesaz.stencilartist.core.paint.MatchQuality
import com.hereliesaz.stencilartist.core.paint.PaintMode
import com.hereliesaz.stencilartist.core.paint.PaintPlan
import com.hereliesaz.stencilartist.core.paint.ShoppingList
import com.hereliesaz.stencilartist.core.paint.displayName

/**
 * The Paint section under "Separate by" in Colour mode: Free, Pick for me or My cans, the controls of
 * each, and the one-line caveat about colour values.
 */
@Composable
internal fun PaintSection(state: StencilState) {
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    Section("Paint") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Free", selected = state.paintMode == PaintMode.FREE) { state.selectPaintMode(PaintMode.FREE) }
            Choice("Pick for me", selected = state.paintMode == PaintMode.PICK_FOR_ME) { state.selectPaintMode(PaintMode.PICK_FOR_ME) }
            Choice("My cans", selected = state.paintMode == PaintMode.MY_CANS) { state.selectPaintMode(PaintMode.MY_CANS) }
        }
        if (state.paintMode == PaintMode.FREE) {
            Text("Image colours, or your own palette.", style = MaterialTheme.typography.bodySmall, color = subdued)
            FreePaletteEditor(state)
            return@Section
        }
        val catalogue = state.catalogue
        if (catalogue == null) {
            Text(
                if (state.catalogueLoading) "Loading the paint catalogue..." else state.catalogueError ?: "The paint catalogue isn't loaded.",
                style = MaterialTheme.typography.bodySmall, color = subdued,
            )
            if (!state.catalogueLoading) OutlinedButton(onClick = state::ensureCatalogue, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            return@Section
        }
        when (state.paintMode) {
            PaintMode.PICK_FOR_ME -> PickForMe(state)
            else -> MyCans(state)
        }
        Text(
            "Can colours are approximate. Sampled and personally entered values are not verified paint measurements.",
            style = MaterialTheme.typography.bodySmall, color = subdued,
        )
    }
}

/**
 * Edits the actual sheet colours in Free mode, without modifying any can-based paint selection.
 *
 * Only one layer is edited at a time. The local hex/RGB draft is applied on demand rather than on
 * every slider tick: a colour change regenerates and may reorder the sheets, so an in-flight drag
 * must not keep changing a different sheet. All exporters then consume that completed separation.
 * An additional catalogue browser lets the user select a real can's recorded colour, with the
 * same can-top grid and full-can carousel as My cans. Entries without resolved RGB are hidden.
 * Custom hex/RGB editing remains available. A palette can be restored to the image-derived colours
 * without changing the requested sheet count.
 */
@Composable
private fun FreePaletteEditor(state: StencilState) {
    val sheets = state.separation?.sheets.orEmpty()
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Int?>(null) }

    Text("Tap a swatch to edit its colour. Changes update the stencil and all exports.",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    if (sheets.isEmpty()) {
        Text("Open an image to edit its palette.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(sheets.size) { index ->
                val rgb = sheets[index].color
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                        .border(if (editing == index) 3.dp else 1.dp,
                            if (editing == index) scheme.primary else scheme.outline, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) {
                            editing = if (editing == index) null else index
                            if (editing != null) state.ensureCatalogue()
                        }
                        .semantics { contentDescription = "Edit sheet ${index + 1} colour ${PaletteColor.hex(rgb)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Swatch(rgb, 40.dp)
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                        color = if (Color(rgb).isLight()) Color.Black else Color.White)
                }
            }
        }
    }

    if (state.colorParams.colors != null) {
        OutlinedButton(onClick = {
            editing = null
            state.update(state.colorParams.copy(colors = null))
        }, modifier = Modifier.fillMaxWidth()) { Text("Use image colours again") }
    }

    val index = editing
    if (index != null && index in sheets.indices) {
        val current = sheets[index].color
        var hexText by remember(index, current) { mutableStateOf(PaletteColor.hex(current)) }
        val candidate = PaletteColor.parseHex(hexText)
        val duplicate = candidate != null && sheets.indices.any {
            it != index && (sheets[it].color and 0xFFFFFF) == (candidate and 0xFFFFFF)
        }
        val draft = candidate ?: current
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .border(1.dp, scheme.outline, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SHEET ${index + 1} COLOUR", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text("SPRAY CAN COLOURS", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            if (state.catalogue != null) {
                CanBrowser(
                    state = state,
                    selectedColour = current,
                    requireColour = true,
                    assignLabel = "Use this can's colour",
                    onPick = { can ->
                        val rgb = can.rgb
                        when {
                            rgb == null -> false
                            sheets.indices.any { it != index && (sheets[it].color and 0xFFFFFF) == (rgb and 0xFFFFFF) } -> {
                                state.fail("Another sheet already has that colour.")
                                false
                            }
                            else -> {
                                state.setSheetColor(index, rgb)
                                editing = null
                                true
                            }
                        }
                    },
                )
            } else {
                Text(
                    if (state.catalogueLoading) "Loading spray can colours..."
                    else state.catalogueError ?: "Spray can catalogue unavailable.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                if (!state.catalogueLoading) {
                    OutlinedButton(onClick = state::ensureCatalogue, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }
            Text("CUSTOM RGB COLOUR", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(draft, 52.dp)
                BasicTextField(
                    value = hexText,
                    onValueChange = { hexText = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.weight(1f).fieldBox("Hex colour, six digits"),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { inner() }
                    },
                )
            }
            if (candidate == null) {
                Text("Enter a six-digit hex colour, such as #1A2B3C.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.error)
            } else if (duplicate) {
                Text("Another sheet already uses this colour.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            for ((label, shift) in listOf("Red" to 16, "Green" to 8, "Blue" to 0)) {
                val value = (draft shr shift) and 0xFF
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$label $value", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(74.dp))
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { hexText = PaletteColor.hex(PaletteColor.withChannel(draft, shift, it.roundToInt())) },
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "$label channel" },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                OutlinedButton(
                    onClick = {
                        candidate?.let { state.setSheetColor(index, it) }
                        editing = null
                    },
                    enabled = candidate != null && !duplicate && candidate != current && !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Apply colour") }
            }
        }
    }
}

@Composable
private fun PickForMe(state: StencilState) {
    val catalogue = state.catalogue ?: return
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    Text("LAYERS", style = MaterialTheme.typography.labelSmall, color = subdued)
    Stepper(value = state.pickCount, range = 1..state.pickMax, label = "layers", onChange = state::choosePickCount)
    Text(
        "Up to ${state.pickMax} (the different colours the allowed cans come in).",
        style = MaterialTheme.typography.bodySmall, color = subdued,
    )
    Text("BRANDS", style = MaterialTheme.typography.labelSmall, color = subdued)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("All", selected = state.allowed.isEmpty()) { state.allowAll() }
        for (brand in catalogue.brands) {
            Choice(brand, selected = CanFilter.brandActive(state.allowed, brand)) { state.toggleBrand(brand) }
        }
    }
    for (brand in catalogue.brands) {
        if (!CanFilter.brandActive(state.allowed, brand)) continue
        val lines = catalogue.lines(brand)
        if (lines.size < 2) continue
        Text(brand.uppercase() + " LINES", style = MaterialTheme.typography.labelSmall, color = subdued)
        // Some brands have thirty lines: the chips scroll in a bounded box instead of pushing the panel away.
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (line in lines) {
                    Choice(line, selected = CanFilter.lineActive(state.allowed, brand, line)) { state.toggleLine(brand, line) }
                }
            }
        }
    }
    val chosen = state.paintLayers
    if (state.busy && chosen.isEmpty()) {
        Text("Matching the image's colours to cans...", style = MaterialTheme.typography.bodySmall, color = subdued)
    } else if (chosen.isNotEmpty()) {
        Text("CHOSEN CANS", style = MaterialTheme.typography.labelSmall, color = subdued)
        val cans = chosen.map { it.layer.primary }.filter { it.rgb != null }
        var viewing by remember { mutableStateOf<Int?>(null) }
        val index = viewing
        if (index != null && index in cans.indices) {
            CanCarousel(state, cans, index, readOnly = true, onBack = { viewing = null })
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, can) in cans.withIndex()) CanTile(can, selected = false) { viewing = i }
            }
        }
        Text(
            "${state.layersUsed} layer${if (state.layersUsed == 1) "" else "s"}: " +
                chosen.joinToString(", ") { it.layer.primary.name },
            style = MaterialTheme.typography.bodySmall, color = subdued,
        )
    }
}

@Composable
private fun MyCans(state: StencilState) {
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    var open by remember { mutableStateOf(false) }
    val chosen = state.selected
    val layers = chosen.map { it.rgb }.distinct().size
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(if (open) "Close the can picker" else if (chosen.isEmpty()) "Choose cans" else "Choose cans (${chosen.size} chosen)")
    }
    if (open) CanPicker(state)
    if (chosen.isEmpty()) {
        Text("No cans chosen yet. The stencil needs at least one.", style = MaterialTheme.typography.bodySmall, color = subdued)
    } else {
        Text(
            "${chosen.size} can${if (chosen.size == 1) "" else "s"} chosen: $layers layer${if (layers == 1) "" else "s"}.",
            style = MaterialTheme.typography.bodySmall, color = subdued,
        )
        for (note in PaintPlan.sharedColourNotes(chosen)) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

/** How far, in pixels, a mouse drag must travel to turn a carousel page. */
private const val SWIPE_PX = 40f

/** A set of cans being looked through in the carousel, from [start]. */
private class Browsing(val cans: List<Can>, val start: Int)

/**
 * My cans adds the scanner and selected-can strip to the same can browser used by Free Colour.
 * Scanning never changes a free-colour palette or its selected sheet.
 */
@Composable
private fun CanPicker(state: StencilState) {
    val scheme = MaterialTheme.colorScheme
    val chosen = state.selected
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScannerPanel(onGtin = state::addCanByBarcode, onClose = {}, resultNote = state.barcodeNote)
        val unknown = state.unknownBarcode
        unknown?.let { gtin ->
            Text(
                "Unknown barcode $gtin. Find its can and choose it to remember this code.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        CanBrowser(
            state = state,
            onPick = unknown?.let { gtin ->
                { can: Can -> state.learnBarcode(gtin, can); true }
            },
            assignLabel = "Remember barcode for this can",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED (${chosen.size})", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (chosen.isNotEmpty()) Text(
                "Clear all", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(role = Role.Button) { state.clearCans() }.padding(4.dp),
            )
        }
        if (chosen.none { it.rgb != null }) {
            Text("No selected cans have resolved colours.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Column(Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (can in chosen.filter { it.rgb != null }) SelectedTop(can) { state.removeCan(can) }
                }
            }
        }
    }
}

/**
 * Shared catalogue browser: a top-view grid, search and brand filters, and a full-can carousel.
 * The grid lazily scrolls through every catalogue entry in a fixed-height viewport, so several
 * thousand cans are navigable without inflating the outer controls panel or rendering them all.
 * Opening any can navigates over the complete filtered results, including currently unseen rows.
 *
 * [onPick] applies a can colour to a Free sheet or learns a barcode; null means My cans'
 * usual selection toggle. Unresolved colours are omitted from both rack and carousel;
 * [requireColour] remains for call-site compatibility but no longer affects visibility.
 */
@Composable
private fun CanBrowser(
    state: StencilState,
    onPick: ((Can) -> Boolean)? = null,
    assignLabel: String = "Select can",
    requireColour: Boolean = false,
    selectedColour: Int? = null,
) {
    val catalogue = state.catalogue ?: return
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    var browsing by remember { mutableStateOf<Browsing?>(null) }
    LaunchedEffect(query, brand) { gridState.scrollToItem(0) }
    val open = browsing

    if (open != null) {
        // Catalogue colours can be edited while the carousel is open. Refresh each visible
        // can from its stable key, otherwise a saved colour leaves its action disabled until Back.
        val currentCans = remember(open, catalogue) {
            open.cans.mapNotNull { can ->
                (catalogue.byKey(can.canKey) ?: can).takeIf { it.rgb != null }
            }
        }
        val firstKey = open.cans.getOrNull(open.start)?.canKey
        val firstIndex = currentCans.indexOfFirst { it.canKey == firstKey }
            .takeIf { it >= 0 } ?: open.start.coerceAtMost((currentCans.size - 1).coerceAtLeast(0))
        if (currentCans.isEmpty()) {
            LaunchedEffect(catalogue) { browsing = null }
        } else CanCarousel(
            state, currentCans, firstIndex, readOnly = false, onBack = { browsing = null },
            onAssign = onPick?.let { pick ->
                { can: Can -> if (pick(can)) browsing = null }
            },
            assignLabel = assignLabel,
            requireColour = requireColour,
        )
    } else {
        SearchField(query, onChange = { query = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChoice("All brands", selected = brand == null) { brand = null }
            for (b in catalogue.brands) SmallChoice(b, selected = brand == b) { brand = b }
        }
        val results = remember(query, brand, catalogue) {
            val b = brand
            when {
                query.isNotBlank() -> catalogue.search(
                    query, catalogue.cans.size, if (b == null) emptySet() else setOf(b),
                )
                b != null -> catalogue.cans(b)
                else -> catalogue.cans
            }.filter { it.rgb != null }
        }
        if (results.isEmpty()) {
            Text("No can matches.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Text(
                "${results.size} cans · scroll to browse",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            // The parent control panel is vertically scrollable; the grid must have a finite
            // viewport to remain lazy and avoid infinite-height nested-scroll exceptions.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 68.dp),
                modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.White),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results.size) { i ->
                    val can = results[i]
                    CanTile(
                        can,
                        selected = if (onPick == null) can in state.selected
                            else selectedColour != null && can.rgb == selectedColour,
                    ) { browsing = Browsing(results, i) }
                }
            }
            Text(
                "Tap a can top to view the whole can. Swipe or use the arrows to browse.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** One can top in the grid: only cans with a known colour reach this composable. */
@Composable
private fun CanTile(can: Can, selected: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = can.displayName + (if (selected) ", selected" else "") },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CanTop(can.rgb, selected, can.hexSource == HexSource.SAMPLED, 56.dp)
    }
}

/** A selected can as a small top with a remove control; tapping it removes the can. */
@Composable
private fun SelectedTop(can: Can, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove ${can.displayName}" },
    ) {
        CanTop(can.rgb, selected = false, sampled = can.hexSource == HexSource.SAMPLED, size = 40.dp)
        Box(
            Modifier.align(Alignment.TopEnd).size(16.dp).clip(RoundedCornerShape(8.dp)).background(scheme.background),
            contentAlignment = Alignment.Center,
        ) { Text("×", style = MaterialTheme.typography.labelSmall, color = scheme.onSurface) }
    }
}

/**
 * The carousel: [cans] in pages about 70% of the panel wide, the page at [start] first, with the edge
 * of the previous and next pages showing at the sides as the cue to swipe. Each page is a whole can at
 * left and its maker, colour name, code, barcode and (where known) match quality at right, with a
 * Select toggle unless [readOnly]. Swipe by mouse drag or touch, press the arrow buttons or the
 * keyboard's left and right arrows. Only the pages near the centre are composed and drawn.
 * [onAssign] replaces My cans' selection action with applying a can's colour to a free sheet
 * or teaching an unknown barcode. Entries without resolved RGB are omitted.
 * Both workflows share the same peeking carousel.
 */
@Composable
private fun CanCarousel(
    state: StencilState,
    cans: List<Can>,
    start: Int,
    readOnly: Boolean,
    onBack: () -> Unit,
    onAssign: ((Can) -> Unit)? = null,
    assignLabel: String = "Remember barcode for this can",
    requireColour: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (cans.size - 1).coerceAtLeast(0))) { cans.size }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go(delta: Int) {
        val target = (pager.currentPage + delta).coerceIn(0, cans.size - 1)
        scope.launch { pager.animateScrollToPage(target) }
    }
    val current = cans[pager.currentPage.coerceIn(0, cans.size - 1)]
    Column(
        Modifier.focusRequester(focus).focusable().onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionLeft -> { go(-1); true }
                Key.DirectionRight -> { go(1); true }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(role = Role.Button, onClick = onBack).padding(vertical = 6.dp, horizontal = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} of ${cans.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrowButton("‹", "Previous can", enabled = pager.currentPage > 0) { go(-1) }
            // The pager follows touch by itself. A mouse drag does not move it in the browser, so a drag
            // that no child consumed turns the page by one: the same swipe, by mouse.
            BoxWithConstraints(Modifier.weight(1f).pointerInput(cans) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled < -SWIPE_PX) go(1) else if (travelled > SWIPE_PX) go(-1) },
                    onHorizontalDrag = { _, amount -> travelled += amount },
                )
            }) {
                // Pages are 70% of the pager; the padding on either side is what lets the neighbours peek.
                val page = maxWidth * 0.7f
                val pad = (maxWidth - page) / 2
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = pad),
                    pageSize = PageSize.Fixed(page),
                    pageSpacing = 4.dp,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                ) { index ->
                    // Signed distance from the centre in pages: negative for pages to the left.
                    val signed = index - pager.currentPage - pager.currentPageOffsetFraction
                    val offset = abs(signed).coerceIn(0f, 1f)
                    CanPage(
                        cans[index], state, signed, page,
                        modifier = Modifier.graphicsLayer {
                            val scale = 1f - 0.12f * offset
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.5f * offset
                        },
                    )
                }
            }
            ArrowButton("›", "Next can", enabled = pager.currentPage < cans.size - 1) { go(1) }
        }
        if (!readOnly) {
            // Previously entered colour values can be corrected or cleared. A cleared
            // value disappears from the rack and carousel on the next catalogue update.
            if (current.hexSource == HexSource.USER) {
                var hexDraft by remember(current.canKey, current.rgb) {
                    mutableStateOf(current.rgb?.let(PaletteColor::hex).orEmpty())
                }
                val candidate = PaletteColor.parseHex(hexDraft)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (current.rgb == null) "ADD YOUR COLOUR TO THIS CAN"
                        else "EDIT YOUR SAVED CAN COLOUR",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (candidate != null) Swatch(candidate, 36.dp)
                        BasicTextField(
                            value = hexDraft,
                            onValueChange = { hexDraft = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                            cursorBrush = SolidColor(scheme.primary),
                            modifier = Modifier.weight(1f).fieldBox("Can colour hex"),
                            decorationBox = { inner ->
                                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                    if (hexDraft.isEmpty()) Text("#RRGGBB", color = scheme.onSurfaceVariant)
                                    inner()
                                }
                            },
                        )
                        OutlinedButton(
                            onClick = { candidate?.let { state.saveCanColour(current, it) } },
                            enabled = candidate != null && candidate != current.rgb,
                        ) { Text("Save") }
                    }
                    if (hexDraft.isNotEmpty() && candidate == null) {
                        Text("Use six RGB hex digits.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                    if (current.hexSource == HexSource.USER) {
                        OutlinedButton(onClick = { state.clearCanColour(current) }) { Text("Clear my colour") }
                    }
                    Text(
                        "Your approximation stays on this device, not in the manufacturer's catalogue.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }
            val isSelected = current in state.selected
            val selectable = current.rgb != null
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected && onAssign == null) scheme.primary else scheme.surfaceVariant)
                    .clickable(enabled = selectable, role = if (onAssign != null) Role.Button else Role.Checkbox) {
                        if (onAssign != null) onAssign(current) else state.toggleCan(current)
                    }
                    .semantics {
                        contentDescription = (if (onAssign != null) "$assignLabel: " else if (isSelected) "Remove " else "Select ") +
                            current.displayName
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        onAssign != null -> assignLabel
                        isSelected -> "Selected"
                        else -> "Select"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected && onAssign == null) scheme.onPrimary
                        else if (selectable) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArrowButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else scheme.outline) }
}

/**
 * One page of the carousel: the whole can, and what to know about it. [signed] is how far the page is
 * from the centre in pages (negative on the left). A page beside the centre keeps only its can, slid to
 * the edge that faces the centre, so what peeks out at either side is the edge of a can and not the tail
 * of a line of text.
 */
@Composable
private fun CanPage(can: Can, state: StencilState, signed: Float, pageWidth: Dp, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // How near this can is to the colour it stands in for, where there is one (Pick for me); a can the user chose has no other colour to be near.
    val quality = state.paintLayers.firstOrNull { it.layer.primary == can && it.layer.target != it.layer.color }
        ?.let { MatchQuality.of(it.layer.distance) }
    val travel = pageWidth - 52.dp
    Box(modifier.fillMaxWidth().height(190.dp)) {
        val canImage = Modifier.align(Alignment.CenterStart).graphicsLayer {
            translationX = (-signed).coerceIn(0f, 1f) * travel.toPx()
        }
        if (can.brand == "MTN (Montana Colors)" && can.line == "94") {
            Mtn94Packshot(can.rgb, 44.dp, 120.dp, canImage)
        } else {
            CanSide(can.rgb, 44.dp, 120.dp, canImage)
        }
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 52.dp).graphicsLayer { alpha = (1f - 2f * abs(signed)).coerceIn(0f, 1f) },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(can.brand, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(can.line, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(can.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            can.code?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            can.gtin?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            quality?.let {
                Text(
                    when (it) { MatchQuality.EXACT -> "Exact match"; MatchQuality.CLOSE -> "Close match"; MatchQuality.APPROXIMATE -> "Approximate match" },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            when {
                can.hexSource == HexSource.USER -> Text("your colour · approximate", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                can.hexSource == HexSource.SAMPLED -> Text("colour sampled", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** A compact chip for filters inside the picker. */
@Composable
private fun SmallChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) scheme.primary else scheme.surfaceVariant)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelMedium) }
}

/** The search box, in the same dark field as the number fields. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text("SEARCH", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fieldBox("Search cans"),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Name, code or brand", color = scheme.onSurfaceVariant, fontSize = 14.sp)
                    inner()
                }
            },
        )
    }
}

/**
 * A colour square drawn from [rgb]. With no colour value it is empty and hatched, so it cannot be
 * mistaken for a colour.
 */
@Composable
private fun Swatch(rgb: Int?, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(5.dp)
    val base = Modifier.size(size).clip(shape)
    if (rgb != null) {
        Box(base.background(Color(rgb)).border(1.dp, scheme.outline, shape))
    } else {
        val line = scheme.outline
        Box(base.background(scheme.surfaceVariant).drawBehind {
            val step = 7.dp.toPx()
            var x = -this.size.height
            while (x < this.size.width) {
                drawLine(line, Offset(x, this.size.height), Offset(x + this.size.height, 0f), strokeWidth = 1.5.dp.toPx())
                x += step
            }
        }.border(1.dp, scheme.outline, shape))
    }
}

/**
 * "Paint list": every layer of the stencil as swatch, brand, name and code with how near its can is
 * to the colour it stands for, then a note for each layer the image does not need (with a way to
 * keep it).
 */
@Composable
internal fun PaintListSection(state: StencilState) {
    val layers = state.paintLayers
    if (layers.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val listed = PaintPlan.listed(layers, state.keptUnused)
    val items = ShoppingList.of(listed.map { it.layer })
    Section("Paint list") {
        Text(
            "${listed.size} layer${if (listed.size == 1) "" else "s"} used",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, entry) in listed.withIndex()) {
                val item = items[i]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch(entry.layer.color, 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text("${entry.sheet + 1}  ${item.brand} · ${item.name}", style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(item.code, if (item.sampled) "sampled" else null,
                                if (item.userColour) "your colour" else null,
                                if (item.equivalents.isNotEmpty()) "+${item.equivalents.size} same colour" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when (item.quality) {
                            MatchQuality.EXACT -> "Exact"
                            MatchQuality.CLOSE -> "Close"
                            MatchQuality.APPROXIMATE -> "Approximate"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        for (entry in layers.filter { !it.used }) {
            val keptNow = (entry.layer.color and 0xFFFFFF) in state.keptUnused
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    PaintPlan.unusedNote(entry) + if (keptNow) " (kept on the lists)." else ".",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                SmallChoice(if (keptNow) "Drop" else "Keep", selected = false) { state.keepUnused(entry.layer.color, !keptNow) }
            }
        }
    }
}
