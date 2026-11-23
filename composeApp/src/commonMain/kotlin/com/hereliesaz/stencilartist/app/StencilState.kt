package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.ColorSeparator
import com.hereliesaz.stencilartist.core.DepthLighting
import com.hereliesaz.stencilartist.core.LightingParams
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.TonalParams
import com.hereliesaz.stencilartist.core.TonalSeparator
import com.hereliesaz.stencilartist.core.paint.Can
import com.hereliesaz.stencilartist.core.paint.CanFilter
import com.hereliesaz.stencilartist.core.paint.PaintCatalogue
import com.hereliesaz.stencilartist.core.paint.PaintMode
import com.hereliesaz.stencilartist.core.paint.PaintPersistence
import com.hereliesaz.stencilartist.core.paint.PaintPlan
import com.hereliesaz.stencilartist.core.paint.ShoppingItem
import com.hereliesaz.stencilartist.core.paint.SnappedLayer
import com.hereliesaz.stencilartist.core.paint.UserBarcodes
import com.hereliesaz.stencilartist.core.paint.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the subject is split into sheets: by brightness into greys, or by colour into paints. */
enum class SeparationMode { TONE, COLOUR }

/**
 * Everything the screen shows, and the one place it changes. The stencil is re-made whenever the
 * image or a parameter changes, off the main thread where the platform has one to spare, and a
 * run that has been overtaken is cancelled, not finished.
 */
class StencilState(
    private val scope: CoroutineScope,
    private val isolator: SubjectIsolator,
    private val depthEstimator: DepthEstimator,
    private val catalogueLoader: CatalogueLoader,
    private val store: KeyValueStore = MemoryStore(),
) {

    /** The opened image, or null before one is opened. */
    var source: RgbaImage? by mutableStateOf(null)
        private set

    /** Which separator makes the sheets. Each mode keeps its own settings, so switching back loses nothing. */
    var mode: SeparationMode by mutableStateOf(SeparationMode.TONE)
        private set

    /** The settings of the tonal separator, used in [SeparationMode.TONE]. */
    var toneParams: TonalParams by mutableStateOf(TonalParams())
        private set

    /** The settings of the colour separator, used in [SeparationMode.COLOUR]. */
    var colorParams: ColorParams by mutableStateOf(ColorParams())
        private set

    /** How many sheets the active mode is asked for; a colour image with fewer colours makes fewer. */
    val sheetCount: Int get() = if (mode == SeparationMode.TONE) toneParams.sheets else colorParams.sheets

    /** The most sheets the active mode can be asked for. */
    val maxSheets: Int get() = if (mode == SeparationMode.TONE) TonalParams.MAX_SHEETS else ColorParams.MAX_SHEETS

    /** Median-filter side of the active mode. */
    val smoothing: Int get() = if (mode == SeparationMode.TONE) toneParams.smoothing else colorParams.smoothing

    /** Edge-closing side of the active mode. */
    val closing: Int get() = if (mode == SeparationMode.TONE) toneParams.closing else colorParams.closing

    // --- Paint: making the layers real cans -------------------------------------------------------

    /**
     * Where the colour sheets' colours come from. Only [SeparationMode.COLOUR] uses it; like the
     * tone and colour settings it is kept when the mode is switched away and back.
     */
    var paintMode: PaintMode by mutableStateOf(PaintMode.FREE)
        private set

    /** How many layers [PaintMode.PICK_FOR_ME] asks for. */
    var pickCount: Int by mutableStateOf(4)
        private set

    /** The brands and lines [PaintMode.PICK_FOR_ME] may use, as [CanFilter] entries; empty allows all. */
    var allowed: Set<String> by mutableStateOf(emptySet())
        private set

    /** The cans the user chose for [PaintMode.MY_CANS], in the order chosen. Every one has a colour value. */
    var selected: List<Can> by mutableStateOf(emptyList())
        private set

    /** Barcodes the user taught the app; consulted before the catalogue by [addCanByBarcode]. */
    var userBarcodes: UserBarcodes by mutableStateOf(UserBarcodes.EMPTY)
        private set

    /** The most recent unrecognized barcode; picker can associate it with a catalogue can. */
    var unknownBarcode: String? by mutableStateOf(null)
        private set

    /** The last scan result, independent of stencil-generation messages. */
    var barcodeNote: String? by mutableStateOf(null)
        private set

    /** The paint catalogue, null until it is first needed and has loaded. */
    var catalogue: PaintCatalogue? by mutableStateOf(null)
        private set

    /** True while the catalogue is loading. */
    var catalogueLoading: Boolean by mutableStateOf(false)
        private set

    /** Why the catalogue could not be loaded, worded for the user, or null. */
    var catalogueError: String? by mutableStateOf(null)
        private set

    /** The most layers [PaintMode.PICK_FOR_ME] can ask for: the distinct colours among the allowed cans. */
    var pickMax: Int by mutableStateOf(1)
        private set

    /** The sheets of the current stencil as cans, in paint order; empty unless the stencil was made from cans. */
    var paintLayers: List<SnappedLayer> by mutableStateOf(emptyList())
        private set

    /** Colours (24-bit RGB) of unused layers the user chose to keep on the lists. */
    var keptUnused: Set<Int> by mutableStateOf(emptySet())
        private set

    /** Whether the current stencil is made from cans: Colour mode with a paint mode other than free. */
    val snapped: Boolean get() = mode == SeparationMode.COLOUR && paintMode.snapped

    /** The layer count the screen shows: the layers actually used (plus those kept), not the count asked for. */
    val layersUsed: Int get() = PaintPlan.layerCount(paintLayers, keptUnused)

    private var catalogueJob: Job? = null
    private var savedCans = ""
    private var savedAllowed = ""

    init {
        // Brands and cans are text keys until the catalogue is there to resolve them (see ensureCatalogue).
        paintMode = store.get(KEY_MODE)?.let { name -> PaintMode.entries.firstOrNull { it.name == name } } ?: PaintMode.FREE
        pickCount = store.get(KEY_PICK)?.toIntOrNull()?.coerceIn(1, ColorParams.MAX_SHEETS) ?: 4
        savedCans = store.get(KEY_CANS).orEmpty()
        savedAllowed = store.get(KEY_ALLOWED).orEmpty()
        userBarcodes = store.get(KEY_BARCODES)?.let(UserBarcodes::decode) ?: UserBarcodes.EMPTY
    }

    /**
     * Loads the catalogue if it is not loaded or loading. It is read on first need, not at start, so
     * a session that never uses cans never fetches it. When it arrives the saved cans and brands are
     * resolved through it (what it no longer has is dropped) and, if the stencil is made from cans,
     * the stencil is made again.
     */
    fun ensureCatalogue() {
        if (catalogue != null || catalogueJob?.isActive == true) return
        catalogueLoading = true
        catalogueError = null
        catalogueJob = scope.launch {
            try {
                val loaded = catalogueLoader.loadPaintCatalogue()
                selected = PaintPersistence.decodeCans(savedCans, loaded).filter { it.rgb != null }
                allowed = PaintPersistence.decodeAllowed(savedAllowed, loaded)
                catalogue = loaded
                pickMax = pickLimit(loaded, allowed)
                pickCount = pickCount.coerceAtMost(pickMax)
                // A failed load can leave free-colour fallback artwork; retry must replace it.
                if (snapped && separation != null) remake(settle = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                catalogueError = "The paint catalogue couldn't be loaded."
            } finally {
                catalogueLoading = false
            }
        }
    }

    private fun pickLimit(of: PaintCatalogue, filter: Set<String>): Int =
        PaintPlan.availableColours(of, filter).coerceIn(1, ColorParams.MAX_SHEETS)

    /** Chooses where the colour sheets' colours come from and makes the stencil again. */
    fun selectPaintMode(next: PaintMode) {
        if (next == paintMode) return
        paintMode = next
        store.put(KEY_MODE, next.name)
        if (next.snapped) ensureCatalogue()
        if (mode == SeparationMode.COLOUR) changedParams(sheetCount)
    }

    /** Sets how many layers [PaintMode.PICK_FOR_ME] asks for, within what the allowed cans can supply. */
    fun choosePickCount(count: Int) {
        val next = count.coerceIn(1, pickMax)
        if (next == pickCount) return
        pickCount = next
        store.put(KEY_PICK, next.toString())
        if (snapped && paintMode == PaintMode.PICK_FOR_ME) changedParams(sheetCount)
    }

    private fun changeAllowed(next: Set<String>) {
        val cat = catalogue ?: return
        allowed = next
        savedAllowed = PaintPersistence.encodeAllowed(next)
        store.put(KEY_ALLOWED, savedAllowed)
        pickMax = pickLimit(cat, next)
        pickCount = pickCount.coerceAtMost(pickMax)
        if (snapped && paintMode == PaintMode.PICK_FOR_ME) changedParams(sheetCount)
    }

    /** Taps a brand chip of the Pick for me filter. */
    fun toggleBrand(brand: String) = changeAllowed(CanFilter.toggleBrand(allowed, brand))

    /** Taps a line chip of the Pick for me filter. */
    fun toggleLine(brand: String, line: String) {
        val cat = catalogue ?: return
        changeAllowed(CanFilter.toggleLine(allowed, cat, brand, line))
    }

    /** "All": every brand and line is allowed again. */
    fun allowAll() = changeAllowed(emptySet())

    private fun changeSelected(next: List<Can>) {
        selected = next
        savedCans = PaintPersistence.encodeCans(next)
        store.put(KEY_CANS, savedCans)
        if (snapped && paintMode == PaintMode.MY_CANS) changedParams(sheetCount)
    }

    /**
     * Adds [can] to the chosen cans, or removes it if it is chosen. A can with no colour value
     * cannot be a layer and is refused with a message.
     */
    fun toggleCan(can: Can) {
        if (can in selected) changeSelected(selected - can)
        else if (can.rgb == null) message = "${can.name} has no colour value, so it can't be a layer."
        else changeSelected(selected + can)
    }

    /** Removes [can] from the chosen cans. */
    fun removeCan(can: Can) {
        if (can in selected) changeSelected(selected - can)
    }

    /** Clears the chosen cans. */
    fun clearCans() {
        if (selected.isNotEmpty()) changeSelected(emptyList())
    }

    /** Keeps (or stops keeping) an unused layer of colour [rgb] on the paint list and in the print PDF's shopping list. */
    fun keepUnused(rgb: Int, keep: Boolean) {
        val colour = rgb and 0xFFFFFF
        keptUnused = if (keep) keptUnused + colour else keptUnused - colour
    }

    /**
     * The shopping list for an export of [sheet] (a sheet index; null for every sheet), for the
     * print PDF: empty unless the stencil is made from cans, and without unused layers the user
     * has not kept.
     */
    fun shoppingFor(sheet: Int?): List<ShoppingItem> =
        if (!snapped) emptyList() else PaintPlan.shopping(paintLayers, sheet?.let(::listOf), keptUnused)

    // --- Barcode scanner -------------------------------------------------------------------------

    /**
     * Resolves a valid scanned or typed [gtin] through learned barcodes and the catalogue.
     * Recognized cans are added, never toggled off by repeated scans. Unknown codes remain in
     * [unknownBarcode] until identified in the can picker. Colourless cans cannot form layers.
     */
    fun addCanByBarcode(gtin: String) {
        unknownBarcode = null
        barcodeNote = "Looking up $gtin..."
        ensureCatalogue()
        scope.launch {
            catalogueJob?.join()
            val cat = catalogue ?: run {
                barcodeNote = catalogueError ?: "The paint catalogue isn't available."
                message = barcodeNote
                return@launch
            }
            val can = cat.resolve(gtin, userBarcodes)
            when {
                can == null -> {
                    unknownBarcode = gtin
                    barcodeNote = "Barcode $gtin isn't in the catalogue. Find its can below to remember it."
                }
                can in selected -> barcodeNote = "${can.displayName} is already chosen."
                can.rgb == null -> barcodeNote = "${can.displayName} has no colour value, so it can't be a layer."
                else -> {
                    changeSelected(selected + can)
                    barcodeNote = "Added ${can.displayName}."
                }
            }
            message = barcodeNote
        }
    }

    /**
     * Stores [gtin] as belonging to [can], overriding a catalogue match on future reads.
     * A successful association is immediately resolved again to select the can when possible.
     */
    fun learnBarcode(gtin: String, can: Can) {
        try {
            userBarcodes = userBarcodes.learn(gtin, can.canKey)
            store.put(KEY_BARCODES, userBarcodes.encode())
            if (unknownBarcode == gtin) unknownBarcode = null
            addCanByBarcode(gtin)
        } catch (refused: IllegalArgumentException) {
            barcodeNote = "That isn't a valid barcode."
            message = barcodeNote
        }
    }

    /**
     * Whether the background is removed before the stencil is made, so the base sheet is the
     * subject's silhouette and not the whole frame. On by default, as in GraffitiXR. An image that
     * arrives with transparency is taken as already cut out and left alone.
     */
    var removeBackground: Boolean by mutableStateOf(true)
        private set

    /** Depth helps uncertain U²-Net boundaries, using a model downloaded only when needed. */
    var depthAssistedBackground: Boolean by mutableStateOf(true)
        private set

    /** Changes to light and depth modify the base image before stencil separation. */
    var lighting: LightingParams by mutableStateOf(LightingParams())
        private set

    var viewProcessedImage: Boolean by mutableStateOf(false)
        private set

    private var depthMap: FloatArray? = null
    private var depthAttempted = false
    private var depthError: String? = null
    private var processed: RgbaImage? = null

    /** [source] with its background removed, once that has been done and found a subject. */
    private var cutOut: RgbaImage? = null

    /** Whether isolation has been tried for [source], whatever came of it: it is tried once. */
    private var isolationTried = false

    /** The stencil made from the masked, optionally relit source with the active mode's settings, or null while pending. */
    var separation: Separation? by mutableStateOf(null)
        private set

    /** The sheet on show, as an index into the separation's sheets; null shows the finished piece. */
    var shownSheet: Int? by mutableStateOf(null)
        private set

    /** What is drawn: processed base image, selected stencil view, or original source while loading. */
    var preview: ImageBitmap? by mutableStateOf(null)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    /** Something that went wrong, worded for the user; null when nothing has. */
    var message: String? by mutableStateOf(null)
        private set

    private var run: Job? = null
    private var generation = 0L

    fun open(image: RgbaImage) {
        source = image
        cutOut = null
        isolationTried = false
        depthMap = null
        depthAttempted = false
        depthError = null
        processed = null
        viewProcessedImage = false
        shownSheet = null
        message = null
        preview = argbToImageBitmap(image.pixels, image.width, image.height)
        remake(settle = false)
    }

    fun fail(text: String) {
        message = text
    }

    fun update(next: TonalParams) {
        if (next == toneParams) return
        toneParams = next
        changedParams(next.sheets)
    }

    fun update(next: ColorParams) {
        if (next == colorParams) return
        colorParams = next
        changedParams(next.sheets)
    }

    /** Switches the separator. The pipeline is the same up to it; only the separation is made again. */
    fun selectMode(next: SeparationMode) {
        if (next == mode) return
        mode = next
        if (snapped) ensureCatalogue()
        changedParams(sheetCount)
    }

    /** Asks the active mode for [count] sheets, with colours of its own choosing again. */
    fun setSheetCount(count: Int) {
        when (mode) {
            SeparationMode.TONE -> update(toneParams.copy(sheets = count, colors = null))
            SeparationMode.COLOUR -> update(colorParams.copy(sheets = count, colors = null))
        }
    }

    /** Sets the median-filter side (odd) of the active mode. */
    fun setSmoothing(side: Int) {
        when (mode) {
            SeparationMode.TONE -> update(toneParams.copy(smoothing = side))
            SeparationMode.COLOUR -> update(colorParams.copy(smoothing = side))
        }
    }

    /** Sets the edge-closing side of the active mode. */
    fun setClosing(side: Int) {
        when (mode) {
            SeparationMode.TONE -> update(toneParams.copy(closing = side))
            SeparationMode.COLOUR -> update(colorParams.copy(closing = side))
        }
    }

    /**
     * Makes sheet [sheet] (a position in paint order) the colour [argb], keeping every other sheet
     * as it is now. In colour mode the sheets' colours then become the given palette, so the other
     * pixels are re-sorted to those colours and the order option may move the sheet. There is no
     * screen for this yet; it is the seam a colour picker will use. Asking for a colour another
     * sheet already has is refused with a message.
     */
    fun setSheetColor(sheet: Int, argb: Int) {
        if (snapped) {
            message = "The colours come from the cans. Change the cans instead."
            return
        }
        val current = separation?.sheets?.map { it.color } ?: return
        if (sheet !in current.indices) return
        val colors = current.toMutableList().also { it[sheet] = argb }
        if (colors.map { it and 0xFFFFFF }.toSet().size != colors.size) {
            message = "Another sheet already has that colour."
            return
        }
        when (mode) {
            SeparationMode.TONE -> update(toneParams.copy(sheets = colors.size, colors = colors))
            SeparationMode.COLOUR -> update(colorParams.copy(sheets = colors.size, colors = colors))
        }
    }

    /** Settings changed: a sheet that no longer exists cannot stay selected, and the stencil is made again. */
    private fun changedParams(sheets: Int) {
        shownSheet = shownSheet?.takeIf { it < sheets }
        remake(settle = true)
    }

    fun removeBackground(on: Boolean) {
        if (on == removeBackground) return
        removeBackground = on
        remake(settle = false)
    }

    fun depthAssistedBackground(on: Boolean) {
        if (on == depthAssistedBackground) return
        depthAssistedBackground = on
        cutOut = null
        isolationTried = false
        if (on && depthMap == null) {
            depthAttempted = false // An explicit toggle retries a failed depth download.
            depthError = null
        }
        remake(settle = false)
    }

    fun updateLighting(next: LightingParams) {
        if (next == lighting) return
        lighting = next
        remake(settle = true)
    }

    fun showProcessed(on: Boolean) {
        viewProcessedImage = on
        if (on) processed?.let { preview = argbToImageBitmap(it.pixels, it.width, it.height) }
        else separation?.let { preview = it.toImageBitmap(shownSheet) }
    }

    fun show(sheet: Int?) {
        shownSheet = sheet
        viewProcessedImage = false
        separation?.let { preview = it.toImageBitmap(sheet) }
    }

    /**
     * Makes the stencil again. With [settle], waits a moment first, so dragging a slider makes one
     * stencil when the drag pauses and not one per pixel of travel.
     */
    private fun remake(settle: Boolean) {
        val image = source ?: return
        val wantedMode = mode
        val wantedTone = toneParams
        val wantedColor = colorParams
        val wantedLighting = lighting
        val wantedPaint = if (wantedMode == SeparationMode.COLOUR) paintMode else PaintMode.FREE
        val remove = removeBackground
        val assist = depthAssistedBackground
        val request = ++generation
        run?.cancel()
        separation = null
        paintLayers = emptyList()
        busy = true
        run = scope.launch {
            try {
                if (settle) delay(SETTLE_MILLIS)
                delay(1) // Allow the busy indicator to paint before inference in the browser.
                val notes = ArrayList<String>()
                val needsDepth = wantedLighting.enabled ||
                    (remove && assist && !image.hasTransparency())
                if (needsDepth && depthMap == null && !depthAttempted) {
                    try {
                        val calculated = depthEstimator.estimate(image)
                        if (generation == request) {
                            depthMap = calculated
                            depthAttempted = true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (generation == request) {
                            depthAttempted = true
                            depthError = "Depth unavailable; using standard masking and original lighting."
                        }
                    }
                }
                if (needsDepth && depthMap == null) {
                    notes += depthError ?: "Depth unavailable; using standard masking and original lighting."
                }
                if (remove && !isolationTried && !image.hasTransparency()) {
                    when (val isolation = isolator.isolate(image, if (assist) depthMap else null)) {
                        is Isolation.Cut -> cutOut = isolation.image
                        Isolation.NoSubject -> notes += "No subject found. Using the whole image."
                        is Isolation.Unavailable ->
                            notes += "Background removal unavailable. Using the whole image."
                    }
                    isolationTried = true
                }
                val isolated = cutOut?.takeIf { remove } ?: image
                val lit = if (wantedLighting.enabled && depthMap != null) {
                    val map = checkNotNull(depthMap)
                    withContext(Dispatchers.Default) {
                        DepthLighting.relight(isolated, map, wantedLighting)
                    }
                } else isolated
                // This is the processing order: source -> depth-assisted mask -> relit base image
                // -> separation (by tone or by colour). No relighting occurs after any stencil layer is created.
                var asked = if (wantedMode == SeparationMode.TONE) wantedTone.sheets else wantedColor.sheets
                var layers: List<SnappedLayer> = emptyList()
                var failure = "Couldn't make a stencil from this image."
                var result: Separation? = null
                var free = !wantedPaint.snapped
                if (wantedPaint.snapped) {
                    // Cans need the catalogue; the stencil waits for it rather than being made in free colours first.
                    ensureCatalogue()
                    catalogueJob?.join()
                    val cat = catalogue
                    if (cat == null) {
                        notes += (catalogueError ?: "The paint catalogue isn't available.") + " Using free colours."
                        free = true
                    } else {
                        // Catalogue loading restores restrictions and clamps pickCount.
                        // Snapshot only after it completes, never before the await.
                        val wantedPick = pickCount
                        val wantedAllowed = allowed
                        val chosen = selected
                        val picked = withContext(Dispatchers.Default) {
                            PaintPlan.layersFor(wantedPaint, cat, lit, wantedPick, wantedAllowed, chosen)
                        }
                        if (picked.isEmpty()) {
                            failure = if (wantedPaint == PaintMode.MY_CANS) "Choose the cans to use under Paint."
                            else "None of the allowed cans has a colour value to match."
                        } else {
                            asked = picked.size
                            val made = withContext(Dispatchers.Default) { PaintPlan.separate(lit, wantedColor, picked) }
                            result = made?.separation
                            layers = made?.layers.orEmpty()
                        }
                    }
                }
                if (free) {
                    result = withContext(Dispatchers.Default) {
                        when (wantedMode) {
                            SeparationMode.TONE -> TonalSeparator.separate(lit, wantedTone)?.separation
                            SeparationMode.COLOUR -> ColorSeparator.separate(lit, wantedColor)?.separation
                        }
                    }
                }
                processed = lit
                if (result == null) {
                    separation = null
                    message = failure
                    // Show the base image, not the previous stencil, which no longer matches the settings.
                    preview = argbToImageBitmap(lit.pixels, lit.width, lit.height)
                } else {
                    paintLayers = layers
                    if (result.sheets.size < asked) {
                        notes += "The image has only ${result.sheets.size} distinct colour" +
                            (if (result.sheets.size == 1) "" else "s") + ", so that is how many sheets it makes."
                    }
                    separation = result
                    // A colour image can make fewer sheets than asked: keep the shown one in range.
                    shownSheet = shownSheet?.takeIf { it < result.sheets.size }
                    message = notes.joinToString(" ").ifEmpty { null }
                    preview = if (viewProcessedImage) argbToImageBitmap(lit.pixels, lit.width, lit.height)
                        else result.toImageBitmap(shownSheet)
                }
            } finally {
                if (generation == request) busy = false
            }
        }
    }

    private fun RgbaImage.hasTransparency(): Boolean = pixels.any { it ushr 24 != 0xFF }

    private fun Separation.toImageBitmap(sheet: Int?): ImageBitmap = argbToImageBitmap(render(sheet), width, height)

    private companion object {
        const val SETTLE_MILLIS = 120L
        const val KEY_MODE = "paint.mode"
        const val KEY_PICK = "paint.pick"
        const val KEY_CANS = "paint.cans"
        const val KEY_ALLOWED = "paint.allowed"
        const val KEY_BARCODES = "paint.barcodes"
    }
}
