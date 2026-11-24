package com.hereliesaz.stencilartist.core

import kotlin.math.*

/** A textured closed solid with an independent source camera and orbit camera. All distances
 * are image-relative, never physical measurements. Lighting uses an orthographic directional
 * shadow map, not image-space depth gradients. Front texture retains the photograph's baked light. */
class ReliefScene private constructor(private val source: RgbaImage, private val mesh: ReliefMesh) {
    private data class V(val x: Float, val y: Float, val z: Float) {
        operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
        fun dot(b: V) = x * b.x + y * b.y + z * b.z
        fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
        fun unit(): V { val n = sqrt(dot(this)); return if (n > 0f) V(x / n, y / n, z / n) else this }
    }
    private data class P(val x: Float, val y: Float, val z: Float)
    private val scale = max(source.width, source.height).toFloat()
    // Use exact source aspect rather than the rounded mesh-grid aspect.
    private val world = mesh.vertices.map { V((it.u - .5f) * source.width / scale, (.5f - it.v) * source.height / scale, it.z) }

    /** Render at original working-image dimensions. The frontal camera and source alpha are fixed. */
    fun renderSource(light: LightingParams): RgbaImage = render(light, source.width, source.height, 0f, 0f, true)

    /** Orbit the same solid and light without changing the image used by the separator. */
    fun renderOrbit(light: LightingParams, yaw: Float, pitch: Float): RgbaImage {
        require(yaw.isFinite() && pitch in -89f..89f)
        return render(light, 256, 256, yaw, pitch, false)
    }

    private fun radians(degrees: Float) = degrees * (PI / 180).toFloat()

    private fun render(light: LightingParams, width: Int, height: Int, yaw: Float, pitch: Float, frontal: Boolean): RgbaImage {
        if (world.isEmpty()) return if (frontal) source else RgbaImage(width, height, IntArray(width * height))
        val az = radians(light.azimuth); val el = radians(light.elevation)
        // Preserve the original azimuth convention: 90 degrees points down the image.
        val direction = V(cos(az) * cos(el), -sin(az) * cos(el), sin(el)).unit()
        val shadowRight = (if (abs(direction.y) > .95f) V(1f, 0f, 0f) else V(0f, 1f, 0f)).cross(direction).unit()
        val shadowUp = direction.cross(shadowRight)
        val shadow = if (light.castShadows && light.intensity > 0f) fitProjection(shadowRight, shadowUp, direction, SHADOW_SIZE) else null
        val shadowDepth = shadow?.let { points ->
            FloatArray(SHADOW_SIZE * SHADOW_SIZE) { Float.NEGATIVE_INFINITY }.also { buffer ->
                for (t in mesh.triangles) raster(points[t.a], points[t.b], points[t.c], SHADOW_SIZE, SHADOW_SIZE) { i, a, b, c ->
                    val z = a * points[t.a].z + b * points[t.b].z + c * points[t.c].z
                    if (z > buffer[i]) buffer[i] = z
                }
            }
        }
        val y = radians(yaw); val p = radians(pitch)
        val cameraRight = V(cos(y), 0f, -sin(y))
        val cameraUp = V(-sin(y) * sin(p), cos(p), -cos(y) * sin(p))
        val cameraForward = cameraRight.cross(cameraUp)
        val view = if (frontal) mesh.vertices.map { P(it.u * width, it.v * height, it.z) }
            else fitProjection(cameraRight, cameraUp, cameraForward, width)
        val pixels = if (frontal) IntArray(source.pixels.size) { i -> if (source.pixels[i] ushr 24 == 0) source.pixels[i] else 0 }
            else IntArray(width * height)
        val zBuffer = FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }
        for (t in mesh.triangles) {
            // Walls and cap project to zero area or are hidden from the fixed frontal camera.
            if (frontal && t.surface != ReliefSurface.FRONT) continue
            val a = view[t.a]; val b = view[t.b]; val c = view[t.c]
            val va = mesh.vertices[t.a]; val vb = mesh.vertices[t.b]; val vc = mesh.vertices[t.c]
            val normal = (world[t.b] - world[t.a]).cross(world[t.c] - world[t.a]).unit()
            val direct = max(0f, normal.dot(direction)) * light.intensity
            val sa = shadow?.get(t.a); val sb = shadow?.get(t.b); val sc = shadow?.get(t.c)
            raster(a, b, c, width, height) pixel@{ i, wa, wb, wc ->
                val z = wa * a.z + wb * b.z + wc * c.z
                if (z <= zBuffer[i]) return@pixel
                val color = if (frontal) source.pixels[i] else if (t.surface == ReliefSurface.FRONT) {
                    val u = wa * va.u + wb * vb.u + wc * vc.u
                    val v = wa * va.v + wb * vb.v + wc * vc.v
                    source.pixels[(v * source.height).toInt().coerceIn(0, source.height - 1) * source.width +
                        (u * source.width).toInt().coerceIn(0, source.width - 1)]
                } else 0xFF909090.toInt()
                if (color ushr 24 == 0) return@pixel
                var occluded = false
                if (direct > 0f && shadowDepth != null && sa != null && sb != null && sc != null) {
                    val sx = (wa * sa.x + wb * sb.x + wc * sc.x).toInt().coerceIn(0, SHADOW_SIZE - 1)
                    val sy = (wa * sa.y + wb * sb.y + wc * sc.y).toInt().coerceIn(0, SHADOW_SIZE - 1)
                    val sz = wa * sa.z + wb * sb.z + wc * sc.z
                    occluded = shadowDepth[sy * SHADOW_SIZE + sx] > sz + SHADOW_BIAS
                }
                val gain = light.ambient + if (occluded) 0f else direct
                pixels[i] = shade(color, gain)
                zBuffer[i] = z
            }
        }
        // Conservative cell sampling must cover every original nontransparent pixel. Do not
        // silently erase thin details if a projection regression breaks this contract.
        if (frontal) check(source.pixels.indices.all { source.pixels[it] ushr 24 == 0 || zBuffer[it].isFinite() }) {
            "Relief render did not cover the original silhouette"
        }
        return RgbaImage(width, height, pixels)
    }

    /** Fit a square orthographic viewport to actual geometry with a border. */
    private fun fitProjection(right: V, up: V, forward: V, size: Int): List<P> {
        val raw = world.map { P(it.dot(right), -it.dot(up), it.dot(forward)) }
        val minX = raw.minOf { it.x }; val maxX = raw.maxOf { it.x }
        val minY = raw.minOf { it.y }; val maxY = raw.maxOf { it.y }
        val s = size * .9f / max(maxX - minX, maxY - minY).coerceAtLeast(.0001f)
        return raw.map { P((it.x - (minX + maxX) / 2) * s + size / 2f,
            (it.y - (minY + maxY) / 2) * s + size / 2f, it.z) }
    }

    /** Pixel-centre triangle rasterization with a two-sided depth test in the caller. */
    private inline fun raster(a: P, b: P, c: P, w: Int, h: Int, visit: (Int, Float, Float, Float) -> Unit) {
        val dx = b.x - a.x; val dy = b.y - a.y; val ex = c.x - a.x; val ey = c.y - a.y
        val det = dx * ey - dy * ex
        if (abs(det) < 1e-8f) return
        val x0 = floor(minOf(a.x, b.x, c.x)).toInt().coerceAtLeast(0)
        val x1 = ceil(maxOf(a.x, b.x, c.x)).toInt().coerceAtMost(w)
        val y0 = floor(minOf(a.y, b.y, c.y)).toInt().coerceAtLeast(0)
        val y1 = ceil(maxOf(a.y, b.y, c.y)).toInt().coerceAtMost(h)
        for (y in y0 until y1) for (x in x0 until x1) {
            val rx = x + .5f - a.x; val ry = y + .5f - a.y
            val wb = (rx * ey - ry * ex) / det; val wc = (dx * ry - dy * rx) / det
            val wa = 1f - wb - wc
            if (wa >= -1e-5f && wb >= -1e-5f && wc >= -1e-5f) visit(y * w + x, wa, wb, wc)
        }
    }

    private fun shade(color: Int, gain: Float): Int {
        fun channel(shift: Int) = (((color ushr shift) and 255) * gain).roundToInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    companion object {
        private const val GRID_EDGE = 128
        private const val SHADOW_SIZE = 512
        private const val SHADOW_BIAS = .004f

        /** Occupy every grid cell containing any visible source pixel, averaging only its visible
         * depth samples. This preserves the final pixel silhouette even at reduced mesh resolution;
         * the coarse solid itself is approximate. A foreground pixel never samples background depth. */
        fun prepare(source: RgbaImage, depth: FloatArray, light: LightingParams): ReliefScene {
            require(source.width > 0 && source.height > 0 && depth.size == source.pixels.size)
            require(depth.all { it.isFinite() && it in 0f..1f })
            val longest = max(source.width, source.height)
            val ratio = min(1f, GRID_EDGE.toFloat() / longest)
            val w = (source.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (source.height * ratio).roundToInt().coerceAtLeast(1)
            val count = IntArray(w * h); val sum = FloatArray(w * h)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val i = y * source.width + x
                if (source.pixels[i] ushr 24 == 0) continue
                val gx = ((x + .5) * w / source.width).toInt().coerceAtMost(w - 1)
                val gy = ((y + .5) * h / source.height).toInt().coerceAtMost(h - 1)
                val cell = gy * w + gx
                count[cell]++; sum[cell] += depth[i]
            }
            val mask = BooleanArray(count.size) { count[it] > 0 }
            val samples = FloatArray(count.size) { if (mask[it]) (sum[it] / count[it]).coerceIn(0f, 1f) else 0f }
            return ReliefScene(source, ReliefMesh.build(w, h, mask, samples,
                thickness = light.extrusionThickness, relief = light.depthStrength / 10f, reverseDepth = light.reverseDepth))
        }
    }
}
