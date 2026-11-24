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
    /** Release the native model session after prediction. */
    fun close()
    /** Inputs and outputs are keyed by the exported ONNX graph's exact port names. */
    suspend fun runNamed(inputs: Map<String, ModelTensor>): Map<String, FloatArray>
}

/** Upstream predictors with explicitly different formats and checkpoint requirements. */
enum class NeuralModelPart(val fileName: String, val sha256: String) {
    PAINT_TRANSFORMER("paint-transformer.onnx", "619ab734a2ce2729d5e7d3df30c76d3095c1916cdc8115b65a414eff6ccfcfb9"),
    COMPOSITIONAL_REGION("compositional-region.onnx", "f84138ffbdd3c426f7e4d3b61b22bbe6659c45434c18eef1ade2d38afcaad1f9"),
    COMPOSITIONAL_PAINTER("compositional-painter.onnx", "9bbbd0af82364363996bb9646ac7007f2500abec43a96f4059e8410e79f7e4ea"),
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
    /** Whether this platform can import and run the stroke graphs. */
    val supportsNeuralStrokes: Boolean get() = false
    /** Loads [fileName]. Throws if the model or the runtime cannot be loaded. */
    suspend fun load(fileName: String): Inference
}

/** This platform's [ModelLoader]: ONNX Runtime on Android, ONNX Runtime Web in the browser. */
@Composable
expect fun rememberModelLoader(): ModelLoader

/** Stroke geometry sources available in Android's construction panel. */
enum class StrokeEngine(val label: String) {
    PROCEDURAL("Procedural"), TRANSFORMER("Paint Transformer"), COMPOSITIONAL("Compositional painter")
}

/** Predict normalized footprints from a black initial canvas. The compositor selects the painter crop. */
internal suspend fun ModelLoader.predictMarks(image: com.hereliesaz.stencilartist.core.RgbaImage, engine: StrokeEngine):
    List<com.hereliesaz.stencilartist.core.NeuralStroke> {
    val transformer = engine == StrokeEngine.TRANSFORMER
    val size = if (transformer) 32 else 128
    val shape = intArrayOf(1, 3, size, size)
    val canvas = ModelTensor(FloatArray(3 * size * size), shape)
    var left = 0f
    var top = 0f
    var width = 1f
    var height = 1f
    suspend fun predict(part: NeuralModelPart): Map<String, FloatArray> {
        val values = FloatArray(canvas.values.size)
        for (y in 0 until size) for (x in 0 until size) {
            val sx = ((left + (x + 0.5f) * width / size) * image.width).toInt().coerceIn(0, image.width - 1)
            val sy = ((top + (y + 0.5f) * height / size) * image.height).toInt().coerceIn(0, image.height - 1)
            val pixel = image.pixels[sy * image.width + sx]
            for (channel in 0..2) {
                val shift = (if (transformer) 2 - channel else channel) * 8
                values[channel * size * size + y * size + x] = ((pixel ushr shift) and 255) / 255f
            }
        }
        val session = load(part.fileName) as NeuralInference
        return try {
            val target = if (transformer) "image" else "target"
            session.runNamed(mapOf(target to ModelTensor(values, shape), "canvas" to canvas))
        } finally { session.close() }
    }
    if (!transformer) {
        val box = predict(NeuralModelPart.COMPOSITIONAL_REGION).getValue("region")
        require(box.size == 4 && box.all { it.isFinite() && it in 0f..1f })
        // Upstream indexes rows first and includes both endpoints of its 128-pixel crop.
        val rows = listOf(box[0], box[2]).map { kotlin.math.round(it * 127).toInt() }
        val cols = listOf(box[1], box[3]).map { kotlin.math.round(it * 127).toInt() }
        top = rows.min() / 128f
        left = cols.min() / 128f
        height = (rows.max() - rows.min() + 1) / 128f
        width = (cols.max() - cols.min() + 1) / 128f
    }
    val output = predict(if (transformer) NeuralModelPart.PAINT_TRANSFORMER else NeuralModelPart.COMPOSITIONAL_PAINTER)
    val params = output.getValue(if (transformer) "params" else "strokes")
    val decisions = if (transformer) output.getValue("decisions") else null
    val stride = if (transformer) 5 else 8
    require(params.size == 40 && params.all { it.isFinite() })
    require(decisions == null || decisions.size == 8 && decisions.all { it.isFinite() })
    return (0 until params.size / stride).filter { decisions == null || decisions[it] > 0 }.map {
        val i = it * stride
        com.hereliesaz.stencilartist.core.NeuralStroke(left + params[i] * width, top + params[i + 1] * height,
            params[i + 2] * width, params[i + 3] * height, params[i + 4] * kotlin.math.PI.toFloat())
    }
}
