package com.hereliesaz.stencilartist.core

/**
 * Everything about cutting the subject out of an image except running the network: what goes into
 * U²-Net and what is done with what comes out. GraffitiXR's `SubjectIsolator`, with the inference
 * itself left to the platform (see the app's `Inference`), so both targets prepare and read the
 * model identically.
 *
 * The model is `u2netp` (see `models/u2netp.LICENSE.txt`): input `[1, 3, 320, 320]`, RGB,
 * ImageNet-normalised; its first output is the fused saliency map, `320 * 320` values.
 */
object Saliency {

    /** The network's square input side. */
    const val INPUT = 320

    /** The shape of [input]'s tensor. */
    val INPUT_SHAPE: IntArray get() = intArrayOf(1, 3, INPUT, INPUT)

    /** Confidence at or above this is subject. */
    const val THRESHOLD = 0.5f

    /** Confidence this far below [THRESHOLD] fades to nothing, so the cut edge is not a staircase. */
    const val FEATHER = 0.1f

    /** Less than this share of the image above [THRESHOLD] is no subject at all. */
    const val MIN_SUBJECT_FRACTION = 0.005f

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * [image] as the network takes it: shrunk to [INPUT] x [INPUT] (aspect ratio not kept, as the
     * network was trained), colour only, scaled to 0..1 and ImageNet-normalised, one plane per
     * channel in R, G, B order.
     */
    fun input(image: RgbaImage): FloatArray {
        val size = image.width * image.height
        val tensor = FloatArray(3 * INPUT * INPUT)
        for (channel in 0 until 3) {
            val shift = 16 - channel * 8
            val plane = FloatArray(size) { ((image.pixels[it] shr shift) and 0xFF) / 255f }
            val small = Resample.area(plane, image.width, image.height, INPUT, INPUT)
            for (i in small.indices) tensor[channel * INPUT * INPUT + i] = (small[i] - MEAN[channel]) / STD[channel]
        }
        return tensor
    }

    /**
     * The network's saliency [map] ([INPUT] x [INPUT]) as a per-pixel subject confidence in 0..1 at
     * [width] x [height]: stretched to its own full range, as U²-Net's reference inference does,
     * then enlarged. A map with no range (a blank answer) is all zeros.
     */
    fun confidence(map: FloatArray, width: Int, height: Int): FloatArray {
        require(map.size >= INPUT * INPUT) { "a saliency map has ${INPUT * INPUT} values, got ${map.size}" }
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in 0 until INPUT * INPUT) {
            if (map[i] < lo) lo = map[i]
            if (map[i] > hi) hi = map[i]
        }
        val range = hi - lo
        if (!(range > 1e-6f)) return FloatArray(width * height)
        val stretched = FloatArray(INPUT * INPUT) { (map[it] - lo) / range }
        return Resample.bilinear(stretched, INPUT, INPUT, width, height)
    }

    /**
     * [image] with everything that is not subject made transparent, or null when [confidence] finds
     * no subject worth the name: cutting then would erase the whole image, which is never what was
     * asked for. The colour of every pixel is kept; only its alpha is rewritten.
     */
    fun cutOut(image: RgbaImage, confidence: FloatArray): RgbaImage? {
        require(confidence.size == image.pixels.size) { "confidence does not match the image" }
        if (confidence.count { it >= THRESHOLD } < confidence.size * MIN_SUBJECT_FRACTION) return null
        val floor = THRESHOLD - FEATHER
        return RgbaImage(image.width, image.height, IntArray(image.pixels.size) { i ->
            val c = confidence[i]
            val alpha = when {
                c >= THRESHOLD -> 255
                c <= floor -> 0
                else -> ((c - floor) / FEATHER * 255f).toInt().coerceIn(0, 255)
            }
            (image.pixels[i] and 0x00FFFFFF) or (alpha shl 24)
        })
    }
}
