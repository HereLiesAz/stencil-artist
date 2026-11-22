package com.hereliesaz.stencilartist.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.stencilartist.core.DepthLighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
actual fun rememberModelLoader(): ModelLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { AssetModelLoader(context) }
}

/**
 * U²-Net remains bundled. The 27.3 MB Depth Anything V2 Small quantised ONNX model is downloaded
 * only on first use, SHA-256 verified, and cached in private persistent app storage.
 */
private class AssetModelLoader(private val context: Context) : ModelLoader {
    override suspend fun load(fileName: String): Inference = withContext(Dispatchers.IO) {
        val environment = OrtEnvironment.getEnvironment()
        val session = if (fileName == DepthLighting.MODEL_FILE) {
            environment.createSession(depthModelFile().absolutePath)
        } else {
            // Bundled subject isolation is unaffected by network availability.
            val bytes = context.assets.open(fileName).use { it.readBytes() }
            environment.createSession(bytes)
        }
        OrtInference(environment, session)
    }

    private fun depthModelFile(): File {
        val folder = File(context.filesDir, "model-cache").apply { mkdirs() }
        val target = File(folder, DepthLighting.MODEL_FILE)
        if (target.isFile && validSha256(target)) return target
        target.delete()

        val temp = File.createTempFile("depth-download-", ".tmp", folder)
        try {
            val connection = URL(DEPTH_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.instanceFollowRedirects = true
                require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Depth model download returned HTTP " + connection.responseCode
                }
                require(connection.contentLengthLong in -1L..MAX_MODEL_BYTES) {
                    "Depth model exceeds the maximum supported download size"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= MAX_MODEL_BYTES) { "Depth model download is too large" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                require(hex(digest.digest()) == DEPTH_SHA256) {
                    "Depth model failed its SHA-256 integrity check"
                }
            } finally {
                connection.disconnect()
            }
            check(temp.renameTo(target)) { "Cannot save depth model in app storage" }
            return target
        } finally {
            temp.delete()
        }
    }

    private fun validSha256(file: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest()) == DEPTH_SHA256
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val value = b.toInt() and 255
                append(digits[value ushr 4])
                append(digits[value and 15])
            }
        }
    }

    private companion object {
        const val DEPTH_URL =
            "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/f7421df0cc30f121782ab050d42f0a423291dcde/onnx/model_quantized.onnx"
        const val DEPTH_SHA256 = "fcf51f1b230362b28690bb9d1809bf0431f29cad20534e3f589bd7285547f20d"
        const val MAX_MODEL_BYTES = 40_000_000L
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
