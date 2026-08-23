package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertObjectGestureArbiterTest {
    @Test
    fun selectedBodyWaitsForDragSlopBeforeMoving() {
        val arbiter = InsertObjectGestureArbiter(movementSlop = 8f)
        arbiter.begin(100f, 100f, InsertObjectGestureMode.MOVE, manipulationEnabled = true)

        assertFalse(arbiter.move(106f, 103f))
        assertTrue(arbiter.move(110f, 100f))
        assertEquals(InsertObjectGestureMode.MOVE, arbiter.mode)
    }

    @Test
    fun resizeHandleUsesTheSameClearMovementCommit() {
        val arbiter = InsertObjectGestureArbiter(movementSlop = 8f)
        arbiter.begin(100f, 100f, InsertObjectGestureMode.RESIZE, manipulationEnabled = true)

        assertFalse(arbiter.move(104f, 104f))
        assertTrue(arbiter.move(110f, 110f))
        assertEquals(InsertObjectGestureMode.RESIZE, arbiter.mode)
    }

    @Test
    fun panoramaObservationNeverMovesTheImage() {
        val arbiter = InsertObjectGestureArbiter(movementSlop = 8f)
        arbiter.begin(100f, 100f, InsertObjectGestureMode.MOVE, manipulationEnabled = false)

        assertFalse(arbiter.move(140f, 100f))
        assertFalse(arbiter.canOpenContextMenu())
        assertEquals(InsertObjectGestureMode.NONE, arbiter.mode)
    }

    @Test
    fun stationaryPressCanOpenContextMenu() {
        val arbiter = InsertObjectGestureArbiter(movementSlop = 8f)
        arbiter.begin(100f, 100f, InsertObjectGestureMode.MOVE, manipulationEnabled = false)

        assertTrue(arbiter.canOpenContextMenu())
    }

    @Test
    fun secondPointerCancelsObjectManipulationAndContextMenu() {
        val arbiter = InsertObjectGestureArbiter(movementSlop = 8f)
        arbiter.begin(100f, 100f, InsertObjectGestureMode.RESIZE, manipulationEnabled = true)

        arbiter.addPointer()

        assertEquals(InsertObjectGestureMode.NONE, arbiter.mode)
        assertFalse(arbiter.canOpenContextMenu())
        assertFalse(arbiter.move(150f, 150f))
    }
}
