package com.hereliesaz.stencilartist.core

import kotlin.math.*

/** A textured closed solid with an independent source camera and orbit camera. All distances
 * are image-relative, never physical measurements. Lighting uses directional orthographic and positional perspective shadow maps,
 * not image-space depth gradients. Source texture retains baked photographic light; the solid material does not. */
class ReliefScene private constructor(private val source: RgbaImage, private val mesh: ReliefMesh) {
    private data class V(val x: Float, val y: Float, val z: Float) {
        fun length() = sqrt(dot(this))
        operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
        fun dot(b: V) = x * b.x + y * b.y + z * b.z
        fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
        fun unit(): V { val n = sqrt(dot(this)); return if (n > 0f) V(x / n, y / n, z / n) else this }
    }
    private data class P(val x: Float, val y: Float, val z: Float)
    private val scale = max(source.width, source.height).toFloat()
    // Use exact source aspect rather than the rounded mesh-grid aspect.
    private val world = mesh.vertices.map { V((it.u - .5f) * source.width / scale, (.5f - it.v) * source.height / scale, it.z) }

    /** Fixed source camera; source alpha is retained unless the receiver is explicitly enabled. */
    fun renderSource(light: LightingParams): RgbaImage = render(light, source.width, source.height, 0f, 0f, true)

    /** Orbit the same solid and light without changing the image used by the separator. */
    fun renderOrbit(light: LightingParams, yaw: Float, pitch: Float, shadowOnly: Boolean = false): RgbaImage {
        require(yaw.isFinite() && pitch in -89f..89f)
        return render(light, 256, 256, yaw, pitch, false, shadowOnly)
    }

    private fun radians(degrees: Float) = degrees * (PI / 180).toFloat()

    private fun render(light: LightingParams, width: Int, height: Int, yaw: Float, pitch: Float, frontal: Boolean, shadowOnly: Boolean = false): RgbaImage {
        if (world.isEmpty() && !light.receiverEnabled) return if (frontal) source else RgbaImage(width, height, IntArray(width * height))
        val receiverZ = (world.minOfOrNull { it.z } ?: -light.extrusionThickness) - light.receiverGap
        val receiver = if (light.receiverEnabled) listOf(
            V(-source.width / (2f * scale), source.height / (2f * scale), receiverZ),
            V(-source.width / (2f * scale), -source.height / (2f * scale), receiverZ),
            V(source.width / (2f * scale), -source.height / (2f * scale), receiverZ),
            V(source.width / (2f * scale), source.height / (2f * scale), receiverZ),
        ) else emptyList()
        val emitters = light.sceneLights().filter { it.intensity > 0f }.map { Emitter(it, light.castShadows) }
        val y = radians(yaw); val p = radians(pitch)
        val cameraRight = V(cos(y), 0f, -sin(y))
        val cameraUp = V(-sin(y) * sin(p), cos(p), -cos(y) * sin(p))
        val cameraForward = cameraRight.cross(cameraUp)
        val projected = if (frontal) emptyList() else fitProjection(cameraRight, cameraUp, cameraForward, width, world + receiver)
        val view = if (frontal) mesh.vertices.map { P(it.u * width, it.v * height, it.z) } else projected
        val pixels = if (frontal) IntArray(source.pixels.size) { i -> if (source.pixels[i] ushr 24 == 0) source.pixels[i] else 0 }
            else IntArray(width * height)
        val zBuffer = FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }
        for (t in mesh.triangles) {
            // Walls and cap project to zero area or are hidden from the fixed frontal camera.
            if (frontal && t.surface != ReliefSurface.FRONT) continue
            val a = view[t.a]; val b = view[t.b]; val c = view[t.c]
            val va = mesh.vertices[t.a]; val vb = mesh.vertices[t.b]; val vc = mesh.vertices[t.c]
            val normal = (world[t.b] - world[t.a]).cross(world[t.c] - world[t.a]).unit()
            raster(a, b, c, width, height) pixel@{ i, wa, wb, wc ->
                val z = wa * a.z + wb * b.z + wc * c.z
                if (z <= zBuffer[i]) return@pixel
                val sampled = if (frontal) source.pixels[i] else if (t.surface == ReliefSurface.FRONT) {
                    val u = wa * va.u + wb * vb.u + wc * vc.u
                    val v = wa * va.v + wb * vb.v + wc * vc.v
                    source.pixels[(v * source.height).toInt().coerceIn(0, source.height - 1) * source.width +
                        (u * source.width).toInt().coerceIn(0, source.width - 1)]
                } else 0xFF909090.toInt()
                if (sampled ushr 24 == 0) return@pixel
                // Material replaces RGB only, never the source silhouette or partial alpha.
                val color = if (light.useSourceTexture) sampled else
                    (sampled and 0xFF000000.toInt()) or light.surfaceColor
                val pa = world[t.a]; val pb = world[t.b]; val pc = world[t.c]
                val point = V(wa * pa.x + wb * pb.x + wc * pc.x,
                    wa * pa.y + wb * pb.y + wc * pc.y, wa * pa.z + wb * pb.z + wc * pc.z)
                pixels[i] = illuminate(color, point, normal, emitters, light, shadowOnly)
                zBuffer[i] = z
            }
        }
        // Conservative cell sampling must cover every original nontransparent pixel. Do not
        // silently erase thin details if a projection regression breaks this contract.
        if (frontal) check(source.pixels.indices.all { source.pixels[it] ushr 24 == 0 || zBuffer[it].isFinite() }) {
            "Relief render did not cover the original silhouette"
        }
        if (receiver.isNotEmpty()) {
            val plane = if (frontal) listOf(P(0f, 0f, receiverZ), P(0f, height.toFloat(), receiverZ),
                P(width.toFloat(), height.toFloat(), receiverZ), P(width.toFloat(), 0f, receiverZ))
                else projected.drop(world.size)
            val normal = V(0f, 0f, 1f)
            val color = light.receiverColor or 0xFF000000.toInt()
            // The backdrop is a real rectangular receiver in scene coordinates, but does not
            // cast its own shadows. Composite only after testing the subject coverage invariant.
            val drawn = BooleanArray(pixels.size)
            for ((a, b, c) in listOf(Triple(0, 1, 2), Triple(0, 2, 3))) {
                raster(plane[a], plane[b], plane[c], width, height) pixel@{ i, wa, wb, wc ->
                    if (drawn[i]) return@pixel
                    drawn[i] = true
                    val z = wa * plane[a].z + wb * plane[b].z + wc * plane[c].z
                    val pa = receiver[a]; val pb = receiver[b]; val pc = receiver[c]
                    val point = V(wa * pa.x + wb * pb.x + wc * pc.x,
                        wa * pa.y + wb * pb.y + wc * pc.y, receiverZ)
                    val background = illuminate(color, point, normal, emitters, light, shadowOnly)
                    pixels[i] = if (z > zBuffer[i]) background else overOpaque(pixels[i], background)
                }
            }
        }
        return RgbaImage(width, height, pixels)
    }

    /** Straight-alpha subject over an opaque opted-in receiver. */
    private fun overOpaque(front: Int, back: Int): Int {
        val a = front ushr 24
        fun mix(shift: Int) = (((front ushr shift and 255) * a +
            (back ushr shift and 255) * (255 - a) + 127) / 255)
        return 0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** One lighting calculation shared by the solid and the receive-only backdrop. */
    private fun illuminate(color: Int, point: V, normal: V, emitters: List<Emitter>,
        light: LightingParams, shadowOnly: Boolean): Int {
        var red = light.ambient; var green = light.ambient; var blue = light.ambient
        var total = 0f; var visible = 0f
        for (emitter in emitters) {
            val direct = emitter.direct(point, normal)
            if (direct <= 0f) continue
            val lit = direct * emitter.visibility(point)
            total += direct; visible += lit
            red += lit * emitter.red; green += lit * emitter.green; blue += lit * emitter.blue
        }
        return if (shadowOnly) {
            val value = if (total > 0f) visible / total else 1f
            shade((color and 0xFF000000.toInt()) or 0xFFFFFF, value, value, value)
        } else shade(color, red, green, blue)
    }

    /** Fit a square orthographic viewport to actual geometry with a border. */
    private fun fitProjection(right: V, up: V, forward: V, size: Int, geometry: List<V>): List<P> {
        val raw = geometry.map { P(it.dot(right), -it.dot(up), it.dot(forward)) }
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

    /** Per-emitter projection: perspective for finite positions, orthographic for directions.
     * Perspective raster depth is reciprocal camera distance, which is affine in screen space. */
    private inner class Emitter(private val light: SceneLight, shadows: Boolean) {
        private val az = radians(light.azimuth)
        private val el = radians(light.elevation)
        private val direction = V(cos(az) * cos(el), -sin(az) * cos(el), sin(el)).unit()
        private val positional = light.kind != SceneLightKind.DIRECTIONAL
        private val position = V(direction.x * light.distance, direction.y * light.distance, direction.z * light.distance)
        private val aim = (V(light.targetX, light.targetY, light.targetZ) - position).unit()
        private val coneOuter = cos(radians(light.cone))
        private val coneInner = cos(radians(light.cone * .8f))
        val red = ((light.color ushr 16) and 255) / 255f
        val green = ((light.color ushr 8) and 255) / 255f
        val blue = (light.color and 255) / 255f
        private val right = (if (abs(direction.y) > .95f) V(1f, 0f, 0f) else V(0f, 1f, 0f)).cross(direction).unit()
        private val up = direction.cross(right)
        private var centerX = 0f
        private var centerY = 0f
        private var scale = 1f
        private val buffer: FloatArray? = if (shadows && light.castsShadows && world.isNotEmpty()) {
            val raw = world.map(::project)
            val minX = raw.minOf { it.x }; val maxX = raw.maxOf { it.x }
            val minY = raw.minOf { it.y }; val maxY = raw.maxOf { it.y }
            centerX = (minX + maxX) / 2; centerY = (minY + maxY) / 2
            scale = SHADOW_SIZE * .9f / max(maxX - minX, maxY - minY).coerceAtLeast(.0001f)
            val projected = raw.map(::fit)
            FloatArray(SHADOW_SIZE * SHADOW_SIZE) { Float.NEGATIVE_INFINITY }.also { depth ->
                for (t in mesh.triangles) {
                    val a = projected[t.a]; val b = projected[t.b]; val c = projected[t.c]
                    raster(a, b, c, SHADOW_SIZE, SHADOW_SIZE) { i, wa, wb, wc ->
                        val z = wa * a.z + wb * b.z + wc * c.z
                        if (z > depth[i]) depth[i] = z
                    }
                }
            }
        } else null

        private fun project(p: V): P {
            val z = p.dot(direction)
            if (!positional) return P(p.dot(right), -p.dot(up), z)
            val d = light.distance - z
            check(d > 0f) { "Geometry must be in front of the positional shadow camera" }
            return P(p.dot(right) / d, -p.dot(up) / d, 1f / d)
        }
        private fun fit(p: P) = P((p.x - centerX) * scale + SHADOW_SIZE / 2f,
            (p.y - centerY) * scale + SHADOW_SIZE / 2f, p.z)

        /** Direct diffuse light before occlusion; finite emitters use inverse-square falloff. */
        fun direct(p: V, normal: V): Float {
            if (!positional) return max(0f, normal.dot(direction)) * light.intensity
            val delta = position - p
            val distance = delta.length()
            if (distance < .0001f) return 0f
            val toward = V(delta.x / distance, delta.y / distance, delta.z / distance)
            val coneGain = if (light.kind == SceneLightKind.SPOT) {
                ((-toward.dot(aim) - coneOuter) / (coneInner - coneOuter)).coerceIn(0f, 1f)
            } else 1f
            return max(0f, normal.dot(toward)) * light.intensity * coneGain / (distance * distance)
        }

        /** Percentage-closer filtering: a bounded nine-tap approximation, not area-light transport. */
        fun visibility(p: V): Float {
            val depth = buffer ?: return 1f
            if (positional && light.distance - p.dot(direction) <= 0f) return 1f
            val q = fit(project(p))
            val threshold = if (positional) 1f / (1f / q.z - SHADOW_BIAS).coerceAtLeast(.0001f)
                else q.z + SHADOW_BIAS
            val x = q.x.toInt(); val y = q.y.toInt()
            fun sample(sx: Int, sy: Int): Int = if (sx !in 0 until SHADOW_SIZE || sy !in 0 until SHADOW_SIZE ||
                depth[sy * SHADOW_SIZE + sx] <= threshold) 1 else 0
            val radius = light.softness
            if (radius == 0) return sample(x, y).toFloat()
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += sample(x + dx * radius, y + dy * radius)
            return sum / 9f
        }
    }

    private fun shade(color: Int, red: Float, green: Float, blue: Float): Int {
        fun channel(shift: Int, gain: Float) = (((color ushr shift) and 255) * gain).roundToInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (channel(16, red) shl 16) or (channel(8, green) shl 8) or channel(0, blue)
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
