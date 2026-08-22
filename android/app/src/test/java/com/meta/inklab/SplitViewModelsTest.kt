package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitViewModelsTest {
    private val pageA = WorkspaceLocation("book-a", "section-a", "section-a", "page-a")
    private val pageB = WorkspaceLocation("book-b", "section-b", "section-b", "page-b")

    @Test
    fun panesKeepIndependentNavigationContexts() {
        val state = SplitViewState()

        assertTrue(state.enter(pageA))
        state.setLocation(SplitPaneSide.RIGHT, pageB)
        assertTrue(state.focus(SplitPaneSide.RIGHT))

        assertEquals(pageA, state.location(SplitPaneSide.LEFT))
        assertEquals(pageB, state.focusedLocation())
    }

    @Test
    fun exitingAlwaysKeepsTheRightPaneEvenWhenLeftWasFocused() {
        val state = SplitViewState()
        state.enter(pageA)
        state.setLocation(SplitPaneSide.RIGHT, pageB)

        val kept = state.exitKeepingRight()

        assertEquals(pageB, kept)
        assertFalse(state.enabled)
        assertEquals(SplitPaneSide.LEFT, state.focusedSide)
    }
}
