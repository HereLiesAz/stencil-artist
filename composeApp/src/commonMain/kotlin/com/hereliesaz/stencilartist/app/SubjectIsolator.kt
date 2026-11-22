package com.hereliesaz.stencilartist.app

import com.hereliesaz.stencilartist.core.DepthLighting
import com.hereliesaz.stencilartist.core.RgbaImage
import com.hereliesaz.stencilartist.core.Saliency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What came of trying to cut the subject out of an image. */
sealed interface Isolation {
    /** The subject alone: everything else transparent. */
    class Cut(val image: RgbaImage) : Isolation

    /** The image has no subject the model can find. */
    data object NoSubject : Isolation

    /** The model or its runtime could not be loaded or run. Nothing is wrong with the image. */
    class Unavailable(val cause: Throwable) : Isolation
}

/**
 * Cuts the subject out of an image with U²-Net (`u2netp.onnx`), on the device. The model is loaded
 * on first use, because a session that never isolates should not pay for it, and runs one image at
 * a time.
 */
class SubjectIsolator(private val loader: ModelLoader) {

    private val lock = Mutex()
    private var model: Inference? = null

    suspend fun isolate(image: RgbaImage, depth: FloatArray? = null): Isolation {
        val map = try {
            lock.withLock {
                val net = model ?: loader.load(MODEL).also { model = it }
                val input = withContext(Dispatchers.Default) { Saliency.input(image) }
                net.run(input, Saliency.INPUT_SHAPE)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Isolation.Unavailable(e)
        }
        return withContext(Dispatchers.Default) {
            val confidence = Saliency.confidence(map, image.width, image.height)
            val refined = if (depth != null) DepthLighting.refineMask(confidence, depth) else confidence
            Saliency.cutOut(image, refined)?.let { Isolation.Cut(it) } ?: Isolation.NoSubject
        }
    }

    private companion object {
        const val MODEL = "u2netp.onnx"
    }
}
