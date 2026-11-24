package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReliefMeshTest {
    /** Every undirected edge has exactly two incident triangles with opposing directions. */
    private fun assertClosed(mesh: ReliefMesh) {
        val edges = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
        for (triangle in mesh.triangles) {
            val indices = listOf(triangle.a, triangle.b, triangle.c)
            assertEquals(3, indices.toSet().size)
            assertTrue(indices.all { it in mesh.vertices.indices })
            for ((a, b) in listOf(indices[0] to indices[1], indices[1] to indices[2], indices[2] to indices[0])) {
                edges.getOrPut(minOf(a, b) to maxOf(a, b)) { mutableListOf() }.add(if (a < b) 1 else -1)
            }
        }
        assertTrue(edges.values.all { it.size == 2 && it.sum() == 0 })
    }

    @Test
    fun singleCellIsAClosedBoxWithSourceUvAndDepth() {
        val mesh = ReliefMesh.build(1, 1, booleanArrayOf(true), floatArrayOf(.5f), .1f, .4f)
        assertEquals(8, mesh.vertices.size)
        assertEquals(12, mesh.triangles.size)
        assertEquals(8, mesh.triangles.count { it.surface == ReliefSurface.WALL })
        assertEquals(ReliefVertex(-.5f, .5f, .2f, 0f, 0f), mesh.vertices.first())
        assertEquals(ReliefVertex(-.5f, .5f, -.1f, 0f, 0f), mesh.vertices[1])
        assertClosed(mesh)
        // Signed volume of the independently specified 1 x 1 x .3 box is positive.
        var volume = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]; val b = mesh.vertices[t.b]; val c = mesh.vertices[t.c]
            volume += (a.x * (b.y * c.z - b.z * c.y) + a.y * (b.z * c.x - b.x * c.z) + a.z * (b.x * c.y - b.y * c.x)) / 6.0
        }
        assertEquals(.3, volume, 0.000001)
    }

    @Test
    fun neighbouringCellsShareEdgesAndRespectAspectRatio() {
        val mesh = ReliefMesh.build(2, 1, booleanArrayOf(true, true), floatArrayOf(0f, 1f), relief = .4f)
        assertEquals(12, mesh.vertices.size)
        assertEquals(20, mesh.triangles.size)
        assertEquals(12, mesh.triangles.count { it.surface == ReliefSurface.WALL })
        assertEquals(setOf(-.25f, .25f), mesh.vertices.map { it.y }.toSet())
        assertEquals(setOf(0f, .2f, .4f), mesh.vertices.filter { it.z >= 0f }.map { it.z }.toSet())
        assertClosed(mesh)
    }

    @Test
    fun holeGetsWallsAndDiagonalContactDoesNotJoinSolids() {
        val ring = ReliefMesh.build(3, 3, BooleanArray(9) { it != 4 }, FloatArray(9))
        assertEquals(32, ring.triangles.count { it.surface == ReliefSurface.WALL })
        assertEquals(64, ring.triangles.size)
        assertClosed(ring)
        val diagonal = ReliefMesh.build(2, 2, booleanArrayOf(true, false, false, true), FloatArray(4))
        assertEquals(16, diagonal.vertices.size)
        assertEquals(24, diagonal.triangles.size)
        assertEquals(4, diagonal.vertices.count { it.x == 0f && it.y == 0f })
        assertClosed(diagonal)
    }

    @Test
    fun reverseAndFlattenChangeOnlyFrontHeight() {
        val mask = booleanArrayOf(true, true)
        val depth = floatArrayOf(.25f, .75f)
        val forward = ReliefMesh.build(2, 1, mask, depth, relief = .4f)
        val reversed = ReliefMesh.build(2, 1, mask, depth, relief = .4f, reverseDepth = true)
        val flat = ReliefMesh.build(2, 1, mask, depth, relief = 0f)
        assertEquals(forward.triangles, reversed.triangles)
        assertEquals(forward.triangles, flat.triangles)
        assertEquals(.1f, forward.vertices.first().z, .000001f)
        assertEquals(.3f, reversed.vertices.first().z, .000001f)
        assertTrue(flat.vertices.all { it.z == 0f || it.z == -.05f })
        assertEquals(forward.vertices, ReliefMesh.build(2, 1, mask, depth, relief = .4f).vertices)
    }

    @Test
    fun allThreeByThreeSilhouettesAreClosed() {
        // Exhaust all 512 masks, including pinched corners, one-pixel channels and holes.
        for (bits in 0 until 512) {
            val mesh = ReliefMesh.build(3, 3, BooleanArray(9) { bits and (1 shl it) != 0 },
                FloatArray(9) { it / 8f })
            assertClosed(mesh)
        }
    }

    @Test
    fun emptyAndInvalidInputsAreExplicit() {
        val mesh = ReliefMesh.build(1, 1, booleanArrayOf(false), floatArrayOf(0f))
        assertTrue(mesh.vertices.isEmpty() && mesh.triangles.isEmpty())
        assertFailsWith<IllegalArgumentException> { ReliefMesh.build(257, 1, BooleanArray(257), FloatArray(257)) }
        assertFailsWith<IllegalArgumentException> { ReliefMesh.build(1, 1, booleanArrayOf(true), floatArrayOf(Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { ReliefMesh.build(1, 1, booleanArrayOf(true), floatArrayOf(0f), thickness = 0f) }
        assertFailsWith<IllegalArgumentException> { ReliefMesh.build(1, 1, booleanArrayOf(), floatArrayOf(0f)) }
    }
}
