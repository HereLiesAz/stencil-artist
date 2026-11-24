package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.stencilartist.core.RgbaImage

/**
 * The longest edge an opened image is brought down to before the stencil is made from it. The
 * pipeline's smoothing and closing are measured in pixels, so the working size is also what those
 * controls are relative to.
 */
const val MAX_WORKING_EDGE = 1600

/**
 * Something that lets the user choose an image from wherever this platform keeps them. Returns the
 * function that opens the chooser. The chosen image arrives at [onPicked], upright and no larger
 * than [MAX_WORKING_EDGE]; a file that cannot be read arrives at [onError] as a message to show.
 * Cancelling calls neither.
 */
@Composable
expect fun rememberImagePicker(onPicked: (RgbaImage) -> Unit, onError: (String) -> Unit): () -> Unit

/** Straight-alpha ARGB [pixels], row-major, as something Compose can draw. */
expect fun argbToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** Whether local, opt-in timing diagnostics are enabled for this process/page. */
internal expect fun stencilPerformanceEnabled(): Boolean

/** Report one timing record locally; never include image data or send network telemetry. */
internal expect fun reportStencilPerformance(record: String)
