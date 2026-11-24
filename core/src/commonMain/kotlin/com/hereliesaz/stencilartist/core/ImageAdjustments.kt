package com.hereliesaz.stencilartist.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Non-destructive RGB preparation, independent of lighting, segmentation and sheet count.
 * All spatial radii are working-image pixels; transparency and dimensions never change. */
data class ImageAdjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val black: Float = 0f,
    val white: Float = 1f,
    val autoLevels: Boolean = false,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val grayscale: Boolean = false,
    val invert: Boolean = false,
    val despeckle: Boolean = false,
    val edgeSmooth: Int = 0,
    val blur: Int = 0,
    val sharpen: Float = 0f,
    val clarity: Float = 0f,
    /** Zero disables value quantization; otherwise number of equally spaced value bands. */
    val posterize: Int = 0,
    /** Master value curve, applied after colour adjustments and before spatial effects. */
    val curve: ToneCurve = ToneCurve(),
) {
    init {
        require(exposure in -2f..2f && brightness in -1f..1f && contrast in -1f..1f && gamma in .25f..4f)
        require(black in 0f.. .49f && white in .51f..1f)
        require(shadows in -1f..1f && highlights in -1f..1f && saturation in 0f..2f)
        require(warmth in -1f..1f && tint in -1f..1f)
        require(edgeSmooth in 0..64 && blur in 0..8 && sharpen in 0f..2f && clarity in -1f..2f)
        require(posterize == 0 || posterize in 2..16)
    }

    /** Apply levels, tone/color, master value curve, cleanup, detail enhancement and value bands in that order. */
    fun apply(image: RgbaImage): RgbaImage {
        if (this == ImageAdjustments() || image.pixels.isEmpty()) return image
        val bounds = if (autoLevels) valueBounds(image.pixels) else 0 to 255
        val span = bounds.second - bounds.first
        val low = bounds.first / 255f + black * span / 255f
        val high = bounds.first / 255f + white * span / 255f
        val gain = 2f.pow(exposure)
        val lut = IntArray(256) { i ->
            var v = ((i / 255f - low) / (high - low)).coerceIn(0f, 1f)
            v = ((v * gain - .5f) * (1f + contrast) + .5f + brightness * .5f).coerceIn(0f, 1f)
            v = v.pow(1f / gamma)
            v += .35f * (shadows * (1f - v) * (1f - v) + highlights * v * v)
            (255f * v).roundToInt().coerceIn(0, 255)
        }
        var pixels = IntArray(image.pixels.size) { i ->
            val p = image.pixels[i]
            if (p ushr 24 == 0) p else {
                val r = lut[channel(p, 16)]; val g = lut[channel(p, 8)]; val b = lut[channel(p, 0)]
                val luma = .2126f * r + .7152f * g + .0722f * b
                val sat = if (grayscale) 0f else saturation
                val warm = if (grayscale) 0f else warmth * 48f
                val green = if (grayscale) 0f else tint * 48f
                fun adjusted(v: Float): Int {
                    val q = v.roundToInt().coerceIn(0, 255)
                    return if (invert) 255 - q else q
                }
                pack(p, adjusted(luma + (r - luma) * sat + warm),
                    adjusted(luma + (g - luma) * sat + green), adjusted(luma + (b - luma) * sat - warm))
            }
        }
        if (curve != ToneCurve()) {
            val transfer = curve.lookup()
            for (i in pixels.indices) {
                val p = pixels[i]
                if (p ushr 24 == 0) continue
                val v = value(p)
                val target = transfer[v]
                if (v == 0) pixels[i] = pack(p, target, target, target)
                else {
                    fun remap(shift: Int) = (channel(p, shift) * target / v.toFloat()).roundToInt().coerceIn(0, 255)
                    pixels[i] = pack(p, remap(16), remap(8), remap(0))
                }
            }
        }
        val w = image.width; val h = image.height
        if (despeckle) pixels = median(pixels, w, h)
        if (edgeSmooth > 0) pixels = smoothSimilar(pixels, w, h, edgeSmooth)
        if (blur > 0) pixels = boxBlur(pixels, w, h, blur)
        if (sharpen > 0f) pixels = detail(pixels, boxBlur(pixels, w, h, 1), sharpen)
        if (clarity != 0f) pixels = detail(pixels, boxBlur(pixels, w, h, 6), clarity)
        if (posterize > 0) {
            val bands = posterize - 1
            for (i in pixels.indices) {
                val p = pixels[i]
                if (p ushr 24 == 0) continue
                val value = value(p)
                if (value == 0) continue
                val target = (value * bands / 255f).roundToInt() * 255f / bands
                fun quantize(shift: Int) = (channel(p, shift) * target / value).roundToInt().coerceIn(0, 255)
                pixels[i] = pack(p, quantize(16), quantize(8), quantize(0))
            }
        }
        return RgbaImage(w, h, pixels)
    }

    companion object {
        private fun channel(p: Int, shift: Int) = (p ushr shift) and 255
        private fun value(p: Int) = maxOf(channel(p, 16), channel(p, 8), channel(p, 0))
        private fun pack(original: Int, r: Int, g: Int, b: Int) =
            (original and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b

        /** Robust value percentiles match the tone separator's HSV-value interpretation.
         * Never stretch a constant or almost-constant subject into artificial contrast. */
        private fun valueBounds(src: IntArray): Pair<Int, Int> {
            val histogram = IntArray(256)
            var count = 0
            for (p in src) if (p ushr 24 != 0) { histogram[value(p)]++; count++ }
            if (count == 0) return 0 to 255
            fun at(rank: Int): Int {
                var total = 0
                for (i in histogram.indices) { total += histogram[i]; if (total > rank) return i }
                return 255
            }
            val lo = at((count - 1) / 50); val hi = at((count - 1) - (count - 1) / 50)
            return if (hi - lo < 8) 0 to 255 else lo to hi
        }

        /** Whole-pixel median by value: remove impulse noise without inventing independent channel
         * combinations or importing hidden transparent RGB. Preserve the centre pixel's alpha. */
        private fun median(src: IntArray, w: Int, h: Int): IntArray {
            val samples = IntArray(9)
            return IntArray(src.size) { i ->
                val p = src[i]
                if (p ushr 24 == 0) p else {
                    val x = i % w; val y = i / w
                    var count = 0
                    for (ny in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (nx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                        val q = src[ny * w + nx]
                        if (q ushr 24 == 0) continue
                        var pos = count
                        while (pos > 0 && value(samples[pos - 1]) > value(q)) { samples[pos] = samples[pos - 1]; pos-- }
                        samples[pos] = q; count++
                    }
                    (p and 0xFF000000.toInt()) or (samples[count / 2] and 0xFFFFFF)
                }
            }
        }

        /** Range-gated 3x3 smoothing: exclude neighbouring colours beyond the selected RGB distance. */
        private fun smoothSimilar(src: IntArray, w: Int, h: Int, threshold: Int): IntArray = IntArray(src.size) { i ->
            val p = src[i]
            if (p ushr 24 == 0) p else {
                val x = i % w; val y = i / w
                val pr = channel(p, 16); val pg = channel(p, 8); val pb = channel(p, 0)
                var weight = 0; var r = 0; var g = 0; var b = 0
                for (ny in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (nx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                    val q = src[ny * w + nx]; val a = q ushr 24
                    val qr = channel(q, 16); val qg = channel(q, 8); val qb = channel(q, 0)
                    if (a == 0 || maxOf(abs(qr - pr), abs(qg - pg), abs(qb - pb)) > threshold) continue
                    weight += a; r += a * qr; g += a * qg; b += a * qb
                }
                pack(p, (r + weight / 2) / weight, (g + weight / 2) / weight, (b + weight / 2) / weight)
            }
        }

        /** Separable alpha-weighted box blur with clipped windows, preserving centre alpha. */
        private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
            fun pass(input: IntArray, horizontal: Boolean): IntArray {
                val out = IntArray(input.size)
                val length = if (horizontal) w else h
                val lines = if (horizontal) h else w
                for (line in 0 until lines) {
                    fun index(p: Int) = if (horizontal) line * w + p else p * w + line
                    var r = 0; var g = 0; var b = 0; var weight = 0
                    fun add(p: Int, sign: Int) {
                        if (p !in 0 until length) return
                        val q = input[index(p)]; val a = (q ushr 24) * sign
                        weight += a; r += channel(q, 16) * a; g += channel(q, 8) * a; b += channel(q, 0) * a
                    }
                    for (p in 0..radius) add(p, 1)
                    for (p in 0 until length) {
                        val i = index(p); val q = input[i]
                        out[i] = if (q ushr 24 == 0 || weight == 0) q else
                            pack(q, (r + weight / 2) / weight, (g + weight / 2) / weight, (b + weight / 2) / weight)
                        add(p - radius, -1); add(p + radius + 1, 1)
                    }
                }
                return out
            }
            return pass(pass(src, true), false)
        }

        /** Unsharp/detail gain: negative amounts suppress local contrast. */
        private fun detail(src: IntArray, blurred: IntArray, amount: Float): IntArray = IntArray(src.size) { i ->
            val p = src[i]; val q = blurred[i]
            if (p ushr 24 == 0) p else {
                fun enhance(shift: Int): Int {
                    val v = channel(p, shift)
                    return (v + amount * (v - channel(q, shift))).roundToInt().coerceIn(0, 255)
                }
                pack(p, enhance(16), enhance(8), enhance(0))
            }
        }
    }
}
