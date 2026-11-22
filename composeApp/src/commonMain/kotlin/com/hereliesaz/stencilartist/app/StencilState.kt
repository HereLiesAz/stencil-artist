package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
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

    var params: TonalParams by mutableStateOf(TonalParams())
        private set

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
    private var processed: RgbaImage? = null

    /** [source] with its background removed, once that has been done and found a subject. */
    private var cutOut: RgbaImage? = null

    /** Whether isolation has been tried for [source], whatever came of it: it is tried once. */
    private var isolationTried = false

    /** The stencil made from [source] with [params], or null if there is none (yet, or at all). */
    var separation: Separation? by mutableStateOf(null)
        private set

    /** The sheet on show, as an index into the separation's sheets; null shows the finished piece. */
    var shownSheet: Int? by mutableStateOf(null)
        private set

    /** What is drawn: the stencil as [shownSheet] selects it, or the source while there is no stencil. */
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
        if (next == params) return
        params = next
        // A sheet that no longer exists cannot stay selected.
        shownSheet = shownSheet?.takeIf { it < next.sheets }
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
        if (on) depthAttempted = false // Allow an explicit retry after a failed download.
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
        val wanted = params
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
                        if (generation == request) depthAttempted = true
                        notes += "Depth unavailable; using normal masking and lighting."
                    }
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
                // -> tonal separation. No relighting occurs after any stencil layer is created.
                val result = withContext(Dispatchers.Default) {
                    TonalSeparator.separate(lit, wanted)
                }
                processed = lit
                if (result == null) {
                    separation = null
                    message = "Couldn't make a stencil from this image."
                } else {
                    separation = result.separation
                    message = notes.joinToString(" ").ifEmpty { null }
                    preview = if (viewProcessedImage) argbToImageBitmap(lit.pixels, lit.width, lit.height)
                        else result.separation.toImageBitmap(shownSheet)
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
