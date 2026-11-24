package com.hereliesaz.stencilartist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadingNoticeTest {
    @Test
    fun firstShowKeepsOneTipThroughCompletionAndDismissal() {
        val initial = LoadingNotice()
        assertFalse(initial.visible)
        val loading = initial.begin(8)
        assertEquals(0, loading.tipIndex)
        assertTrue(loading.visible && loading.processing)
        val ready = loading.finish()
        assertTrue(ready.visible)
        assertFalse(ready.processing)
        assertEquals(loading.tipIndex, ready.tipIndex)
        val hidden = ready.dismiss()
        assertFalse(hidden.visible)
        assertEquals(0, hidden.tipIndex)
    }

    @Test
    fun tapsCannotDismissActiveWork() {
        val loading = LoadingNotice().begin(8)
        assertEquals(loading, loading.dismiss())
        assertEquals(LoadingNotice(), LoadingNotice().dismiss())
    }

    @Test
    fun replacementWorkDoesNotRotateAnAlreadyVisibleTip() {
        val loading = LoadingNotice().begin(8)
        assertEquals(loading, loading.begin(8))
        assertEquals(loading, loading.finish().begin(8))
        assertEquals(loading.finish(), loading.finish().finish())
    }

    @Test
    fun eachNewAppearanceAdvancesAndWraps() {
        var notice = LoadingNotice()
        for (expected in listOf(0, 1, 2, 0, 1)) {
            notice = notice.begin(3)
            assertEquals(expected, notice.tipIndex)
            notice = notice.finish().dismiss()
            assertFalse(notice.visible)
        }
    }
}
