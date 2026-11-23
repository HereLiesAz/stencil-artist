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
            Text("Any colours: the picture's own.", style = MaterialTheme.typography.bodySmall, color = subdued)
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
            "Colour values are on-screen approximations; cans marked sampled are approximate; some makers have no colour value yet.",
            style = MaterialTheme.typography.bodySmall, color = subdued,
        )
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
        val cans = chosen.map { it.layer.primary }
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

/** How many can tops show at first, and how many more each "Show more" tile adds. */
private const val TOPS = 60

/**
 * The can picker, inline in the panel: a search box and brand chips over a wrapping grid of can tops.
 * Tapping a top opens the carousel at that can, over the same results; the selected cans are a row of
 * small tops below.
 */
@Composable
private fun CanPicker(state: StencilState) {
    val catalogue = state.catalogue ?: return
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(TOPS) }
    var browsing by remember { mutableStateOf<Browsing?>(null) }
    val chosen = state.selected

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, scheme.outline, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val open = browsing
        if (open != null) {
            CanCarousel(state, open.cans, open.start, readOnly = false, onBack = { browsing = null })
        } else {
            SearchField(query, onChange = { query = it; shown = TOPS })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChoice("All brands", selected = brand == null) { brand = null; shown = TOPS }
                for (b in catalogue.brands) SmallChoice(b, selected = brand == b) { brand = b; shown = TOPS }
            }
            val results = remember(query, brand, shown, catalogue) {
                val b = brand
                when {
                    query.isNotBlank() -> catalogue.search(query, shown + 1, if (b == null) emptySet() else setOf(b))
                    b != null -> catalogue.cans(b).take(shown + 1)
                    else -> emptyList()
                }
            }
            if (query.isBlank() && brand == null) {
                Text("Search by name, code or brand, or pick a brand.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else if (results.isEmpty()) {
                Text("No can matches.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                val list = results.take(shown)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, can) in list.withIndex()) CanTile(can, can in chosen) { browsing = Browsing(list, i) }
                    if (results.size > shown) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant)
                                .clickable(role = Role.Button) { shown += TOPS },
                            contentAlignment = Alignment.Center,
                        ) { Text("Show more", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                    }
                }
                Text("Tap a can to see it.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED (${chosen.size})", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (chosen.isNotEmpty()) Text(
                "Clear all", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(role = Role.Button) { state.clearCans() }.padding(4.dp),
            )
        }
        if (chosen.isEmpty()) {
            Text("Nothing selected.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            // Bounded and scrolling, so thirty cans do not push the rest of the panel away.
            Column(Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (can in chosen) SelectedTop(can) { state.removeCan(can) }
                }
            }
        }
    }
}

/** One can top in the grid: tapping it opens the carousel. A can with no colour value is hatched and labelled. */
@Composable
private fun CanTile(can: Can, selected: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = can.displayName + (if (selected) ", selected" else "") + (if (can.rgb == null) ", no colour value" else "") },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CanTop(can.rgb, selected, can.hexSource == HexSource.SAMPLED, 56.dp)
        if (can.rgb == null) Text("no colour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
 */
@Composable
private fun CanCarousel(state: StencilState, cans: List<Can>, start: Int, readOnly: Boolean, onBack: () -> Unit) {
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
            val isSelected = current in state.selected
            val selectable = current.rgb != null
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) scheme.primary else scheme.surfaceVariant)
                    .clickable(enabled = selectable, role = Role.Checkbox) { state.toggleCan(current) }
                    .semantics { contentDescription = (if (isSelected) "Remove " else "Select ") + current.displayName },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        !selectable -> "No colour value: can't be a layer"
                        isSelected -> "Selected"
                        else -> "Select"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimary else if (selectable) scheme.onSurface else scheme.onSurfaceVariant,
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
        CanSide(
            can.rgb, 44.dp, 120.dp,
            Modifier.align(Alignment.CenterStart).graphicsLayer {
                translationX = (-signed).coerceIn(0f, 1f) * travel.toPx()
            },
        )
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
                can.rgb == null -> Text("no colour value", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
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
