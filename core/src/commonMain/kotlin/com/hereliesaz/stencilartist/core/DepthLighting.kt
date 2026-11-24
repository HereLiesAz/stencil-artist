package com.hereliesaz.stencilartist.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lighting changes the source image before tonal separation, never individual cut layers. */
data class LightingParams(
    val enabled: Boolean = false,
    val azimuth: Float = 45f,
    val elevation: Float = 50f,
    val intensity: Float = 0.7f,
    val ambient: Float = 0.55f,
    val depthStrength: Float = 3f,
    val extrusionThickness: Float = .05f,
    val reverseDepth: Boolean = false,
    val castShadows: Boolean = true,
    /** Null preserves the original single directional light; an empty list means ambient only. */
    val lights: List<SceneLight>? = null,
    /** Skin the front with source RGB; otherwise coat the entire solid with [surfaceColor]. */
    val useSourceTexture: Boolean = true,
    /** Opaque material RGB (source alpha is retained independently). */
    val surfaceColor: Int = 0xFFFFFF,
    /** Flat relight only: mix source RGB toward neutral white before applying the new light. */
    val flatMatteMix: Float = 0f,
) {
    init {
        require(azimuth in -180f..180f && elevation in 0f..90f)
        require(intensity in 0f..2f && ambient in 0f..2f && depthStrength in 0f..10f)
        require(extrusionThickness in .0001f..1f)
        require(lights == null || lights.size <= SceneLight.MAX_LIGHTS)
        require(surfaceColor in 0..0xFFFFFF)
        require(flatMatteMix in 0f..1f)
    }

    /** Resolve the legacy key-light controls or the explicitly edited emitter list. */
    fun sceneLights(): List<SceneLight> = lights ?: listOf(SceneLight(azimuth = azimuth, elevation = elevation, intensity = intensity))
}

/**
 * Depth Anything V2 Small (quantised ONNX, Apache-2.0): relative inverse depth.
 * Model: onnx-community/depth-anything-v2-small, onnx/model_quantized.onnx.
 * DPT processing: keep aspect, multiples of 14, ImageNet RGB normalization, NCHW float32.
 */
object DepthLighting {
    const val MODEL_FILE = "depth-anything-v2-small-int8.onnx"
    const val INPUT_EDGE = 518
    private const val PATCH = 14
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    data class TensorInput(val values: FloatArray, val width: Int, val height: Int) {
        val shape: IntArray get() = intArrayOf(1, 3, height, width)
    }

    fun input(image: RgbaImage): TensorInput {
        require(image.width > 0 && image.height > 0)
        val scale = INPUT_EDGE.toDouble() / max(image.width, image.height)
        fun dimension(size: Int): Int =
            ((size * scale / PATCH).roundToInt().coerceAtLeast(1) * PATCH).coerceAtMost(INPUT_EDGE)
        val width = dimension(image.width)
        val height = dimension(image.height)
        val count = image.width * image.height
        val planeSize = width * height
        val tensor = FloatArray(planeSize * 3)
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val pixels = FloatArray(count) { i -> ((image.pixels[i] ushr shift) and 255) / 255f }
            val scaled = Resample.bilinear(pixels, image.width, image.height, width, height)
            for (i in 0 until planeSize) {
                tensor[channel * planeSize + i] = (scaled[i] - MEAN[channel]) / STD[channel]
            }
        }
        return TensorInput(tensor, width, height)
    }

    /** Normalise a model output to [0,1] and resize to the original image dimensions. */
    fun depth(output: FloatArray, tensor: TensorInput, imageWidth: Int, imageHeight: Int): FloatArray {
        require(output.size == tensor.width * tensor.height) {
            "Expected " + tensor.width + "x" + tensor.height + " depth; got " + output.size + " samples"
        }
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (sample in output) {
            require(sample.isFinite()) { "Depth estimator produced an invalid sample" }
            if (sample < lo) lo = sample
            if (sample > hi) hi = sample
        }
        val range = hi - lo
        if (range <= 1e-6f) return FloatArray(imageWidth * imageHeight)
        val normal = FloatArray(output.size) { ((output[it] - lo) / range).coerceIn(0f, 1f) }
        val resized = Resample.bilinear(normal, tensor.width, tensor.height, imageWidth, imageHeight)
        for (i in resized.indices) resized[i] = resized[i].coerceIn(0f, 1f)
        return resized
    }

    /**
     * Conservatively refine ambiguous U²-Net edges using foreground/background depth seeds.
     * Depth has no fixed near direction: seed medians establish the orientation per image.
     * Confident mask pixels never change; uninformative depth leaves the mask untouched.
     */
    fun refineMask(confidence: FloatArray, depth: FloatArray): FloatArray {
        require(confidence.size == depth.size)
        val foreground = ArrayList<Float>()
        val background = ArrayList<Float>()
        // Bound median-estimation work on 1600px working images; exhaustive Float boxing
        // would otherwise retain millions of seed objects on Android and Wasm.
        val stride = (depth.size / 8192).coerceAtLeast(1)
        for (i in depth.indices step stride) {
            val d = depth[i]
            if (!d.isFinite()) return confidence
            when {
                confidence[i] >= 0.9f -> foreground += d
                confidence[i] <= 0.1f -> background += d
            }
        }
        if (foreground.size < 4 || background.size < 4) return confidence
        foreground.sort()
        background.sort()
        val near = foreground[foreground.size / 2]
        val far = background[background.size / 2]
        val difference = near - far
        if (kotlin.math.abs(difference) < 0.15f) return confidence
        val midpoint = (near + far) / 2
        val direction = if (difference > 0) 1f else -1f
        val reach = kotlin.math.abs(difference).coerceAtLeast(0.2f)
        return FloatArray(confidence.size) { i ->
            val c = confidence[i]
            if (c <= 0.25f || c >= 0.75f || !depth[i].isFinite()) c else {
                val gate = (1f - kotlin.math.abs(c - 0.5f) / 0.25f).coerceIn(0f, 1f)
                val depthScore = ((depth[i] - midpoint) * direction / reach + 0.5f)
                    .coerceIn(0f, 1f)
                (c + (depthScore - c) * 0.7f * gate).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Flat depth relighting (no extrusion or cast shadows). Convert normalized relative depth
     * to image-relative height; central or one-sided differences yield a normal without crossing
     * transparent background. Robust foreground contrast prevents distant background or a single
     * depth outlier from flattening the subject, with a floor to avoid amplifying tiny depth noise.
     * Matte mixing deliberately replaces baked RGB contrast; it is not recovered intrinsic albedo.
     * Alpha and dimensions are unchanged. The original image is never mutated.
     */
    fun relight(image: RgbaImage, depth: FloatArray, params: LightingParams): RgbaImage {
        require(depth.size == image.pixels.size) { "Depth doesn't match the image" }
        if (!params.enabled || params.depthStrength == 0f || depth.isEmpty()) return image
        require(depth.all { it.isFinite() && it in 0f..1f }) { "Depth must be normalized and finite" }
        val w = image.width
        val h = image.height
        val histogram = IntArray(256)
        var count = 0
        for (i in depth.indices) if (image.pixels[i] ushr 24 != 0) {
            histogram[(depth[i] * 255f).roundToInt()]++
            count++
        }
        if (count == 0) return image
        fun percentile(rank: Int): Int {
            var n = 0
            for (i in histogram.indices) {
                n += histogram[i]
                if (n > rank) return i
            }
            return 255
        }
        val lo = percentile((count - 1) / 50)
        val hi = percentile((count - 1) - (count - 1) / 50)
        val range = ((hi - lo) / 255f).coerceAtLeast(.15f)
        // At strength 3 a full normalized ramp rises 1.05 image widths, rather than the
        // old ~0.28. Divide by the actual sample distance even at silhouette boundaries.
        val scale = .35f * params.depthStrength * max(w, h) / range *
            (if (params.reverseDepth) -1f else 1f)
        val az = params.azimuth * PI / 180.0
        val el = params.elevation * PI / 180.0
        val lightX = (cos(az) * cos(el)).toFloat()
        val lightY = (sin(az) * cos(el)).toFloat()
        val lightZ = sin(el).toFloat()
        val output = IntArray(depth.size)
        fun visible(i: Int) = image.pixels[i] ushr 24 != 0
        fun gradient(i: Int, before: Int, after: Int): Float {
            val left = before >= 0 && visible(before)
            val right = after >= 0 && visible(after)
            return when {
                left && right -> (depth[after] - depth[before]) * .5f
                left -> depth[i] - depth[before]
                right -> depth[after] - depth[i]
                else -> 0f
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val original = image.pixels[i]
            if (!visible(i)) {
                output[i] = original
                continue
            }
            val dx = gradient(i, if (x > 0) i - 1 else -1, if (x + 1 < w) i + 1 else -1) * scale
            val dy = gradient(i, if (y > 0) i - w else -1, if (y + 1 < h) i + w else -1) * scale
            val diffuse = (-dx * lightX - dy * lightY + lightZ) / sqrt(dx * dx + dy * dy + 1f)
            val illumination = params.ambient + params.intensity * maxOf(0f, diffuse)
            fun channel(shift: Int): Int {
                val rgb = (original ushr shift) and 255
                val material = rgb + (255 - rgb) * params.flatMatteMix
                return (material * illumination).roundToInt().coerceIn(0, 255)
            }
            output[i] = (original and 0xFF000000.toInt()) or
                (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return RgbaImage(w, h, output)
    }
}
