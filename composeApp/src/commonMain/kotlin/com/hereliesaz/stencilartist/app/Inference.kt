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

/** Tensor input for a named ONNX port; dimensions and values must agree. */
data class ModelTensor(val values: FloatArray, val shape: IntArray) {
    init {
        require(shape.isNotEmpty() && shape.all { it > 0 })
        require(shape.fold(1L) { product, dimension -> product * dimension } == values.size.toLong())
    }
}

/** Optional multi-tensor ONNX interface for stroke predictors; legacy inference remains unchanged. */
interface NeuralInference : Inference {
    /** Inputs and outputs are keyed by the exported ONNX graph's exact port names. */
    suspend fun runNamed(inputs: Map<String, ModelTensor>): Map<String, FloatArray>
}

/** Upstream predictors with explicitly different formats and checkpoint requirements. */
enum class NeuralModelPart(val fileName: String) {
    PAINT_TRANSFORMER("paint-transformer.onnx"),
    COMPOSITIONAL_REGION("compositional-region.onnx"),
    COMPOSITIONAL_PAINTER("compositional-painter.onnx"),
}

/**
 * Let the user provide locally converted ONNX checkpoints. Models are copied into app-private
 * storage; no pretrained weights are distributed without a separate licence verification.
 */
@Composable
expect fun rememberNeuralModelImporter(
    onImported: (NeuralModelPart) -> Unit,
    onError: (String) -> Unit,
): (NeuralModelPart) -> Unit

/** Loads bundled U²-Net or the independently downloaded Depth Anything model by logical file name. */
interface ModelLoader {
    /** Loads [fileName]. Throws if the model or the runtime cannot be loaded. */
    suspend fun load(fileName: String): Inference
}

/** This platform's [ModelLoader]: ONNX Runtime on Android, ONNX Runtime Web in the browser. */
@Composable
expect fun rememberModelLoader(): ModelLoader
