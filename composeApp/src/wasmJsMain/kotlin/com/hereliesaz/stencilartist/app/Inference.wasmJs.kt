@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Loads ONNX Runtime Web (once, with a script tag: see composeApp/build.gradle.kts for why it is not
 * bundled) and opens a session on the model at [url]. Resolves to the session.
 *
 * One thread: more needs cross-origin isolation headers, which a static host such as GitHub Pages
 * cannot send.
 */
private fun openSession(url: String): Promise<JsAny> = js(
    """(async () => {
        if (!globalThis.ort) {
            await new Promise((resolve, reject) => {
                const script = document.createElement('script');
                script.src = 'ort/ort.wasm.min.js';
                script.onload = resolve;
                script.onerror = () => reject(new Error('ONNX Runtime Web failed to load'));
                document.head.appendChild(script);
            });
            globalThis.ort.env.wasm.wasmPaths = new URL('ort/', document.baseURI).href;
            globalThis.ort.env.wasm.numThreads = 1;
        }
        return await globalThis.ort.InferenceSession.create(url, { executionProviders: ['wasm'] });
    })()"""
)

/** Runs [session] on [data] (a Float32Array) shaped [dims]; resolves to the first output's Float32Array. */
private fun runSession(session: JsAny, data: JsAny, dims: JsAny): Promise<JsAny> = js(
    """(async () => {
        const feeds = {};
        feeds[session.inputNames[0]] = new globalThis.ort.Tensor('float32', data, dims);
        const results = await session.run(feeds);
        return results[session.outputNames[0]].data;
    })()"""
)

private fun newFloat32Array(size: Int): JsAny = js("new Float32Array(size)")
private fun setFloat(array: JsAny, index: Int, value: Float): Unit = js("{ array[index] = value; }")
private fun getFloat(array: JsAny, index: Int): Float = js("array[index]")
private fun lengthOf(array: JsAny): Int = js("array.length")
private fun newArray(): JsAny = js("[]")
private fun push(array: JsAny, value: Int): Unit = js("{ array.push(value); }")

@Composable
actual fun rememberModelLoader(): ModelLoader = remember { WebModelLoader }

/** Loads models from the `models/` directory served beside the app. */
private object WebModelLoader : ModelLoader {
    override suspend fun load(fileName: String): Inference = WebInference(openSession("models/$fileName").await())
}

private class WebInference(private val session: JsAny) : Inference {
    override suspend fun run(input: FloatArray, shape: IntArray): FloatArray {
        val data = newFloat32Array(input.size)
        for (i in input.indices) setFloat(data, i, input[i])
        val dims = newArray()
        for (d in shape) push(dims, d)
        val output: JsAny = runSession(session, data, dims).await()
        return FloatArray(lengthOf(output)) { getFloat(output, it) }
    }
}
