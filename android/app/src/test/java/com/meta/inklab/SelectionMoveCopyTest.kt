package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class SelectionMoveCopyTest {
    @Test fun modifierMayJoinLeaveAndRejoinWithoutCommittingUntilRelease() {
        val gesture = SelectionMoveCopy()
        gesture.begin(false)
        assertFalse(gesture.preview)
        gesture.update(true)
        assertTrue(gesture.preview)
        gesture.update(false)
        assertFalse(gesture.preview)
        gesture.update(true)
        assertTrue(gesture.finish(commit = true))
        assertFalse(gesture.active)
        assertFalse(gesture.preview)
        assertFalse(gesture.finish(commit = true))
    }

    @Test fun releasingControlBeforePointerUpLeavesOnlyTheMovedContent() {
        val gesture = SelectionMoveCopy()
        gesture.begin(true)
        gesture.update(false)
        assertFalse(gesture.finish(commit = true))
    }

    @Test fun cancelOrModifierOutsideAMoveNeverMakesACopy() {
        val gesture = SelectionMoveCopy()
        gesture.update(true)
        assertFalse(gesture.preview)
        assertFalse(gesture.finish(commit = true))
        gesture.begin(true)
        assertFalse(gesture.finish(commit = false))
        gesture.begin(false)
        assertFalse(gesture.preview)
    }
}
