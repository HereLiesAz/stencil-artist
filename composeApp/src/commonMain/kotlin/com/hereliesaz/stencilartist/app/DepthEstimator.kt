package com.hereliesaz.stencilartist.app

import com.hereliesaz.stencilartist.core.DepthLighting
import com.hereliesaz.stencilartist.core.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One on-demand Depth Anything model session shared by background masking and source relighting.
 * The model is never fetched merely to open the app; the state requests it only when needed.
 */
class DepthEstimator(private val loader: ModelLoader) {
    private val lock = Mutex()
    private var model: Inference? = null

    suspend fun estimate(image: RgbaImage): FloatArray = lock.withLock {
        val net = model ?: loader.load(DepthLighting.MODEL_FILE).also { model = it }
        val input = withContext(Dispatchers.Default) { DepthLighting.input(image) }
        val prediction = net.run(input.values, input.shape)
        withContext(Dispatchers.Default) {
            DepthLighting.depth(prediction, input, image.width, image.height)
        }
    }
}
