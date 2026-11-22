package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.Separation
import com.hereliesaz.stencilartist.core.TonalParams
import com.hereliesaz.stencilartist.core.TonalSeparator
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
class StencilState(private val scope: CoroutineScope, private val isolator: SubjectIsolator) {

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

    fun open(image: RgbaImage) {
        source = image
        cutOut = null
        isolationTried = false
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

    fun show(sheet: Int?) {
        shownSheet = sheet
        separation?.let { preview = it.toImageBitmap(sheet) }
    }

    /**
     * Makes the stencil again. With [settle], waits a moment first, so dragging a slider makes one
     * stencil when the drag pauses and not one per pixel of travel.
     */
    private fun remake(settle: Boolean) {
        val image = source ?: return
        val wanted = params
        run?.cancel()
        run = scope.launch {
            if (settle) delay(SETTLE_MILLIS)
            busy = true
            try {
                // On the web there is one thread, so let the "working" state reach the screen first.
                delay(1)
                var note: String? = null
                if (removeBackground && !isolationTried && !image.hasTransparency()) {
                    when (val isolation = isolator.isolate(image)) {
                        is Isolation.Cut -> cutOut = isolation.image
                        Isolation.NoSubject -> note = "No subject found. Using the whole image."
                        is Isolation.Unavailable -> note = "Background removal isn't available. Using the whole image."
                    }
                    isolationTried = true
                }
                val subject = cutOut?.takeIf { removeBackground } ?: image
                val result = withContext(Dispatchers.Default) { TonalSeparator.separate(subject, wanted) }
                if (result == null) {
                    separation = null
                    message = "Couldn't make a stencil from this image."
                } else {
                    separation = result.separation
                    message = note
                    preview = result.separation.toImageBitmap(shownSheet)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun RgbaImage.hasTransparency(): Boolean = pixels.any { it ushr 24 != 0xFF }

    private fun Separation.toImageBitmap(sheet: Int?): ImageBitmap = argbToImageBitmap(render(sheet), width, height)

    private companion object {
        const val SETTLE_MILLIS = 120L
    }
}
