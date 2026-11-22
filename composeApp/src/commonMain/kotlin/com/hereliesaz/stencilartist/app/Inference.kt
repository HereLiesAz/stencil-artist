package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable

/**
 * A loaded ONNX model with one input and at least one output. Running it is the only thing the
 * platforms do differently; what goes in and what comes out is prepared in `core`.
 */
interface Inference {
    /** Runs the model on [input], a float tensor of [shape], and returns its first output, flattened. */
    suspend fun run(input: FloatArray, shape: IntArray): FloatArray
}

/** Loads models shipped with the app (the repository's `models/` directory), by file name. */
interface ModelLoader {
    /** Loads [fileName]. Throws if the model or the runtime cannot be loaded. */
    suspend fun load(fileName: String): Inference
}

/** This platform's [ModelLoader]: ONNX Runtime on Android, ONNX Runtime Web in the browser. */
@Composable
expect fun rememberModelLoader(): ModelLoader
