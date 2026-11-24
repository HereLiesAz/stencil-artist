package com.hereliesaz.stencilartist.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.stencilartist.core.DepthLighting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.nio.FloatBuffer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Import a user-selected ONNX model after checking its size and actual input/output signatures.
 * Checkpoint bytes never leave this device; a failed import cannot replace a working model.
 */
@Composable
actual fun rememberNeuralModelImporter(
    onImported: (NeuralModelPart) -> Unit,
    onError: (String) -> Unit,
): (NeuralModelPart) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val imported = rememberUpdatedState(onImported)
    val failed = rememberUpdatedState(onError)
    var pending by remember { mutableStateOf<NeuralModelPart?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val part = pending
        pending = null
        if (uri == null || part == null) return@rememberLauncherForActivityResult
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = File(context.filesDir, "neural-models").apply { mkdirs() }
                    val temporary = File.createTempFile("neural-import-", ".onnx", folder)
                    try {
                        var bytes = 0L
                        context.contentResolver.openInputStream(uri).let { stream ->
                            requireNotNull(stream) { "Could not read the selected ONNX file." }
                            stream.use { input ->
                                temporary.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        bytes += n
                                        require(bytes <= 150_000_000L) { "ONNX file exceeds 150 MB." }
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                        require(bytes > 0) { "The selected ONNX file is empty." }
                        OrtEnvironment.getEnvironment().createSession(temporary.absolutePath).use { session ->
                            val expectedInputs = when (part) {
                                NeuralModelPart.PAINT_TRANSFORMER -> setOf("image", "canvas")
                                NeuralModelPart.COMPOSITIONAL_REGION,
                                NeuralModelPart.COMPOSITIONAL_PAINTER -> setOf("canvas", "target")
                            }
                            val expectedOutputs = when (part) {
                                NeuralModelPart.PAINT_TRANSFORMER -> setOf("params", "decisions")
                                NeuralModelPart.COMPOSITIONAL_REGION -> setOf("region")
                                NeuralModelPart.COMPOSITIONAL_PAINTER -> setOf("strokes")
                            }
                            require(session.inputNames == expectedInputs &&
                                session.outputNames == expectedOutputs) {
                                "This ONNX file does not match the ${part.name} port names. " +
                                    "Follow the model conversion instructions."
                            }
                        }
                        val target = File(folder, part.fileName)
                        // Copy rather than rename over an existing valid checkpoint: interrupted
                        // writes must never leave a partially installed model.
                        val backup = File(folder, "${part.fileName}.previous")
                        backup.delete()
                        if (target.exists()) check(target.renameTo(backup))
                        if (!temporary.renameTo(target)) {
                            if (backup.exists()) backup.renameTo(target)
                            error("Could not install model.")
                        }
                        backup.delete()
                    } finally {
                        temporary.delete()
                    }
                }.exceptionOrNull()?.message
            }
            if (error == null) imported.value(part) else failed.value(error)
        }
    }
    return { part -> pending = part; launcher.launch("*/*") }
}

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
        val sourcePath = when {
            fileName == DepthLighting.MODEL_FILE -> depthModelFile().absolutePath
            NeuralModelPart.entries.any { it.fileName == fileName } -> {
                val model = File(File(context.filesDir, "neural-models"), fileName)
                require(model.isFile) { "Import ${fileName} from the Stroke construction panel first." }
                model.absolutePath
            }
            else -> null
        }
        currentCoroutineContext().ensureActive()
        val session = if (sourcePath != null) {
            environment.createSession(sourcePath)
        } else {
            // Bundled subject isolation is unaffected by network availability.
            val bytes = context.assets.open(fileName).use { it.readBytes() }
            currentCoroutineContext().ensureActive()
            environment.createSession(bytes)
        }
        try {
            // If cancellation arrived during native session construction, release its resources.
            currentCoroutineContext().ensureActive()
            OrtInference(environment, session)
        } catch (cancelled: Throwable) {
            session.close()
            throw cancelled
        }
    }

    private suspend fun depthModelFile(): File {
        val folder = File(context.filesDir, "model-cache").apply { mkdirs() }
        val target = File(folder, DepthLighting.MODEL_FILE)
        if (target.isFile && validSha256(target)) return target
        target.delete()

        // This block runs on the IO dispatcher. Cancellation invokes the handler even while a
        // blocking HttpURLConnection read is in progress, interrupting the worker and disconnecting
        // the socket. An obsolete download cannot hold the estimator's mutex for the full timeout.
        return suspendCancellableCoroutine { continuation ->
            val worker = Thread.currentThread()
            val temp = File.createTempFile("depth-download-", ".tmp", folder)
            val connection = URL(DEPTH_URL).openConnection() as HttpURLConnection
            continuation.invokeOnCancellation {
                connection.disconnect()
                worker.interrupt()
            }
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
                            if (!continuation.isActive) throw CancellationException("Depth download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= MAX_MODEL_BYTES) { "Depth model download is too large" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (!continuation.isActive) throw CancellationException("Depth download cancelled")
                require(hex(digest.digest()) == DEPTH_SHA256) {
                    "Depth model failed its SHA-256 integrity check"
                }
                if (!continuation.isActive) throw CancellationException("Depth download cancelled")
                check(temp.renameTo(target)) { "Cannot save depth model in app storage" }
                continuation.resume(target)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
                temp.delete()
                // A cancellation may interrupt the pooled IO worker. Do not poison its next task.
                Thread.interrupted()
            }
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

private class OrtInference(private val environment: OrtEnvironment, private val session: OrtSession) : NeuralInference {
    override suspend fun run(input: FloatArray, shape: IntArray): FloatArray = withContext(Dispatchers.Default) {
        val name = session.inputNames.first()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), LongArray(shape.size) { shape[it].toLong() }).use { tensor ->
            session.run(mapOf(name to tensor)).use { outputs ->
                val buffer = (outputs[0] as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }
    override suspend fun runNamed(inputs: Map<String, ModelTensor>): Map<String, FloatArray> =
        withContext(Dispatchers.Default) {
            require(inputs.keys == session.inputNames) { "Unexpected ONNX input ports." }
            val tensors = inputs.mapValues { (_, input) ->
                OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(input.values),
                    LongArray(input.shape.size) { input.shape[it].toLong() },
                )
            }
            try {
                session.run(tensors).use { results ->
                    session.outputNames.associateWith { name ->
                        val value = results.get(name).orElseThrow {
                            IllegalStateException("Missing ONNX output: $name")
                        } as OnnxTensor
                        val buffer = value.floatBuffer
                        FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
            } finally {
                tensors.values.forEach { it.close() }
            }
        }

}
