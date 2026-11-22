package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StencilPrintTipsTest {
    @Test
    fun aTrappedPieceOfMaterialNeedsBridges() {
        val width = 11
        val mask = BooleanArray(width * width)
        for (y in 3..7) {
            for (x in 3..7) {
                if (x == 3 || x == 7 || y == 3 || y == 7) mask[y * width + x] = true
            }
        }
        val tips = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 110.0)
        assertEquals(listOf(PrintTip(PrintTipKind.ISLAND, 5, 5)), tips)
    }

    @Test
    fun oneTinyOpeningGetsARealSizeWarning() {
        val width = 12
        val mask = BooleanArray(width * width)
        mask[4 * width + 7] = true
        val tips = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 12.0)
        assertTrue(PrintTip(PrintTipKind.FINE_CUT, 7, 4) in tips)
        assertFalse(tips.any { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun aCutThroughToTheOuterEdgeIsNotAnIsland() {
        val width = 11
        val mask = BooleanArray(width * width)
        for (y in 3..7) for (x in 3..7) {
            if (x == 3 || x == 7 || y == 3 || y == 7) mask[y * width + x] = true
        }
        mask[5 * width + 3] = false
        val tips = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 110.0)
        assertFalse(tips.any { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun physicallyWideCutsAreNotMarkedTinySimplyBecauseTheyUseFewPixels() {
        val width = 12
        val mask = BooleanArray(width * width)
        mask[4 * width + 7] = true
        val tips = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 120.0)
        assertFalse(tips.any { it.kind == PrintTipKind.FINE_CUT })
    }
}
