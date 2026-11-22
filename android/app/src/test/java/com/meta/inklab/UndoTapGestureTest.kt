package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoTapGestureTest {
    @Test
    fun doubleTapRedoesAndSingleTapUndoesAfterIntervalOrFlush() {
        var undo = 0
        var redo = 0
        var enabled = true
        val scheduled = mutableListOf<Runnable>()
        val gesture = UndoTapGesture(
            schedule = { delay, action -> assertEquals(UndoTapGesture.DOUBLE_TAP_MILLIS, delay); scheduled += action },
            cancel = { scheduled -= it },
            undo = { undo++ },
            redo = { redo++ },
            enabled = { enabled },
        )
        gesture.tap()
        assertTrue(gesture.isPending)
        assertEquals(0 to 0, undo to redo)
        gesture.tap()
        assertFalse(gesture.isPending)
        assertTrue(scheduled.isEmpty())
        assertEquals(0 to 1, undo to redo)

        gesture.tap()
        scheduled.single().run()
        assertFalse(gesture.isPending)
        assertEquals(1 to 1, undo to redo)

        gesture.tap()
        gesture.flush()
        gesture.flush()
        assertTrue(scheduled.isEmpty())
        assertEquals(2 to 1, undo to redo)

        enabled = false
        gesture.tap()
        gesture.tap()
        assertFalse(gesture.isPending)
        assertEquals(4 to 1, undo to redo)
    }
}
