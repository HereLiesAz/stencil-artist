package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StencilPackManifestTest {
    @Test
    fun preservesCanvasPaintOrderAndStraightAlphaColours() {
        val separation = Separation(
            width = 2, height = 1,
            sheets = listOf(Sheet(0xFF000000.toInt()), Sheet(0x80FF0000.toInt())),
            index = intArrayOf(0, 1),
        )
        val expected = """{"format":"com.hereliesaz.stencilpack","version":1,"width":2,"height":1,"order":"bottom-to-top","coverage":"overpaint","composite":"stencil-composite.png","layers":[{"name":"Sheet 1","file":"stencil-sheet-001.png","color":"#FF000000","visible":true},{"name":"Sheet 2","file":"stencil-sheet-002.png","color":"#80FF0000","visible":true}]}"""
        assertEquals(expected, StencilPackManifest.encode(separation))
    }

    @Test
    fun tenSheetsRetainStableThreeDigitNames() {
        val separation = Separation(
            width = 1, height = 1,
            sheets = List(10) { Sheet(0xFFFFFFFF.toInt()) },
            index = intArrayOf(0),
        )
        val encoded = StencilPackManifest.encode(separation)
        kotlin.test.assertTrue(encoded.contains("stencil-sheet-010.png"))
        kotlin.test.assertTrue(encoded.indexOf("stencil-sheet-001.png") < encoded.indexOf("stencil-sheet-010.png"))
    }
}
