package com.hereliesaz.stencilartist.core

/** Scene geometry in image-relative units: X right, Y up, Z toward the source camera. */
data class ReliefVertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

/** Front triangles sample the source texture; inferred walls and back need separate materials. */
enum class ReliefSurface { FRONT, WALL, BACK }

/** An outward-wound triangle indexing [ReliefMesh.vertices]. */
data class ReliefTriangle(val a: Int, val b: Int, val c: Int, val surface: ReliefSurface)

/** A closed, indexed relief solid, not a reconstruction of invisible subject surfaces. */
class ReliefMesh private constructor(
    val vertices: List<ReliefVertex>,
    val triangles: List<ReliefTriangle>,
) {
    companion object {
        /** Keep the first mesh implementation bounded independently of source/export resolution. */
        const val MAX_EDGE = 256

        /**
         * Extrude occupied mask cells backward to a flat cap at -[thickness]. Front heights are
         * averages of the incident foreground depth samples, multiplied by [relief]. The longer
         * image edge is one scene unit; UVs use the original top-left image convention.
         *
         * Every occupied cell contributes front/back triangles and only exposed edges contribute
         * walls (including hole boundaries). Adjacent cells share indices. Diagonal-only contacts
         * have distinct corner vertices so two solids cannot share a non-manifold vertical edge.
         * [reverseDepth] flips the normalized inverse-depth direction. It never changes the mask.
         * Empty masks produce an empty mesh. The caller owns mesh-resolution sampling; this method
         * never silently downsamples or invents foreground. All dimensions and depths are checked.
         */
        fun build(
            width: Int,
            height: Int,
            foreground: BooleanArray,
            depth: FloatArray,
            thickness: Float = 0.05f,
            relief: Float = 0.2f,
            reverseDepth: Boolean = false,
        ): ReliefMesh {
            require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) { "Invalid relief mesh dimensions" }
            require(foreground.size == width * height && depth.size == foreground.size) { "Mask/depth dimensions differ" }
            require(thickness.isFinite() && thickness in 0.0001f..1f) { "Invalid extrusion thickness" }
            require(relief.isFinite() && relief in 0f..1f) { "Invalid relief strength" }
            require(depth.all { it.isFinite() && it in 0f..1f }) { "Depth must be normalized and finite" }
            val vertices = ArrayList<ReliefVertex>()
            val triangles = ArrayList<ReliefTriangle>()
            val corners = IntArray(foreground.size * 4) { -1 }
            val scale = maxOf(width, height).toFloat()

            fun occupied(x: Int, y: Int): Boolean =
                x in 0 until width && y in 0 until height && foreground[y * width + x]

            // Corner order around a cell: top-left, bottom-left, bottom-right, top-right.
            // Incident cells around a grid vertex: NW, SW, SE, NE. Their matching corner indices
            // are BR, TR, TL, BL. A four-neighbour component has exactly one front/back pair here.
            val dx = intArrayOf(-1, -1, 0, 0)
            val dy = intArrayOf(-1, 0, 0, -1)
            val slot = intArrayOf(2, 3, 0, 1)
            for (y in 0..height) for (x in 0..width) {
                var remaining = 0
                for (i in 0..3) if (occupied(x + dx[i], y + dy[i])) remaining = remaining or (1 shl i)
                while (remaining != 0) {
                    var group = remaining and -remaining
                    // Grow over cyclic edge-neighbours only; diagonal islands stay separate.
                    repeat(3) {
                        val adjacent = ((group shl 1) or (group ushr 1) or (group shl 3) or (group ushr 3)) and 15
                        group = group or (adjacent and remaining)
                    }
                    var sum = 0f
                    var count = 0
                    val front = vertices.size
                    for (i in 0..3) if ((group and (1 shl i)) != 0) {
                        val cell = (y + dy[i]) * width + x + dx[i]
                        val value = depth[cell]
                        sum += if (reverseDepth) 1f - value else value
                        count++
                        corners[cell * 4 + slot[i]] = front
                    }
                    val px = (x - width / 2f) / scale
                    val py = (height / 2f - y) / scale
                    val u = x.toFloat() / width
                    val v = y.toFloat() / height
                    vertices.add(ReliefVertex(px, py, sum / count * relief, u, v))
                    vertices.add(ReliefVertex(px, py, -thickness, u, v))
                    remaining = remaining and group.inv()
                }
            }
            fun quad(a: Int, b: Int, c: Int, d: Int, surface: ReliefSurface) {
                triangles.add(ReliefTriangle(a, b, c, surface))
                triangles.add(ReliefTriangle(a, c, d, surface))
            }
            // Front winding is counterclockwise when viewed from +Z. Each wall reverses the
            // front edge it joins, and the cap reverses the front winding.
            for (y in 0 until height) for (x in 0 until width) {
                if (!occupied(x, y)) continue
                val i = (y * width + x) * 4
                val a = corners[i]
                val b = corners[i + 1]
                val c = corners[i + 2]
                val d = corners[i + 3]
                quad(a, b, c, d, ReliefSurface.FRONT)
                quad(d + 1, c + 1, b + 1, a + 1, ReliefSurface.BACK)
                if (!occupied(x - 1, y)) quad(b, a, a + 1, b + 1, ReliefSurface.WALL)
                if (!occupied(x, y + 1)) quad(c, b, b + 1, c + 1, ReliefSurface.WALL)
                if (!occupied(x + 1, y)) quad(d, c, c + 1, d + 1, ReliefSurface.WALL)
                if (!occupied(x, y - 1)) quad(a, d, d + 1, a + 1, ReliefSurface.WALL)
            }
            return ReliefMesh(vertices, triangles)
        }
    }
}
