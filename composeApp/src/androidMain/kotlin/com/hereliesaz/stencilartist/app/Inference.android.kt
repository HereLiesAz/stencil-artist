package com.hereliesaz.stencilartist.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

@Composable
actual fun rememberModelLoader(): ModelLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { AssetModelLoader(context) }
}

/** Loads models from the APK's assets into ONNX Runtime. */
private class AssetModelLoader(private val context: Context) : ModelLoader {
    override suspend fun load(fileName: String): Inference = withContext(Dispatchers.IO) {
        val bytes = context.assets.open(fileName).use { it.readBytes() }
        val environment = OrtEnvironment.getEnvironment()
        OrtInference(environment, environment.createSession(bytes))
    }
}

private class OrtInference(private val environment: OrtEnvironment, private val session: OrtSession) : Inference {
    override suspend fun run(input: FloatArray, shape: IntArray): FloatArray = withContext(Dispatchers.Default) {
        val name = session.inputNames.first()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), LongArray(shape.size) { shape[it].toLong() }).use { tensor ->
            session.run(mapOf(name to tensor)).use { outputs ->
                val buffer = (outputs[0] as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }
}
