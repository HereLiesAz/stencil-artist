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
        val remove = removeBackground
        val assist = depthAssistedBackground
        val request = ++generation
        run?.cancel()
        separation = null
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
                val result = withContext(Dispatchers.Default) {
                    when (wantedMode) {
                        SeparationMode.TONE -> TonalSeparator.separate(lit, wantedTone)?.separation
                        SeparationMode.COLOUR -> ColorSeparator.separate(lit, wantedColor)?.separation
                    }
                }
                processed = lit
                if (result == null) {
                    separation = null
                    message = "Couldn't make a stencil from this image."
                } else {
                    val asked = if (wantedMode == SeparationMode.TONE) wantedTone.sheets else wantedColor.sheets
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
    }
}
