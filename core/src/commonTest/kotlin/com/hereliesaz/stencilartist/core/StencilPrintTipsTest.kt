package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
        assertEquals(1, tips.size)
        assertEquals(PrintTipKind.ISLAND, tips.single().kind)
        assertFalse(mask[tips.single().y * width + tips.single().x],
            "The island anchor must belong to surviving material, not the opening inside it.")
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
    fun aPervasiveProblemIsNotTruncatedAtAnArbitraryTipLimit() {
        val width = 50
        val mask = BooleanArray(width * width) { true }
        for (y in 1 until 48 step 3) {
            for (x in 1 until 48 step 3) mask[y * width + x] = false
        }
        val tips = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 150.0)
        assertEquals(256, tips.count { it.kind == PrintTipKind.ISLAND })
    }

    @Test
    fun concentricUncutIslandsAreDistinctAndBothAnchoredOnRealMaterial() {
        val width = 41
        val mask = BooleanArray(width * width) { true }
        for (y in 5..35) for (x in 5..35) {
            if (x == 5 || x == 35 || y == 5 || y == 35) mask[y * width + x] = false
        }
        for (y in 13..27) for (x in 13..27) {
            if (x == 13 || x == 27 || y == 13 || y == 27) mask[y * width + x] = false
        }
        val islands = StencilPrintTips.detect(mask, width, width, physicalWidthMm = 150.0)
            .filter { it.kind == PrintTipKind.ISLAND }
        assertEquals(2, islands.size)
        assertNotEquals(islands[0], islands[1])
        assertEquals(20, (5 + 35) / 2) // Both bounds have this midpoint; it is cut, not material.
        assertTrue(mask[20 * width + 20])
        for (tip in islands) {
            assertFalse(mask[tip.y * width + tip.x])
        }
    }

    @Test
    fun exceedingThePrintableDetailBudgetFailsWithoutDroppingAnyTip() {
        val width = 60
        val mask = BooleanArray(width * width) { true }
        for (y in 1 until width step 2) for (x in 1 until width step 2) {
            mask[y * width + x] = false
        }
        val error = assertFailsWith<IllegalArgumentException> {
            StencilPrintTips.detect(mask, width, width, physicalWidthMm = 100.0, maxTips = 10)
        }
        assertTrue(error.message.orEmpty().contains("Too many stencil details"))
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
