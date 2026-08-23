package com.meta.inklab

import kotlin.math.hypot

internal enum class InsertObjectGestureMode {
    NONE,
    MOVE,
    RESIZE,
}

/** Keeps object manipulation pending until motion is unambiguously a drag. */
internal class InsertObjectGestureArbiter(
    private val movementSlop: Float,
) {
    var mode: InsertObjectGestureMode = InsertObjectGestureMode.NONE
        private set
    var movementCommitted: Boolean = false
        private set
    var multiTouchSeen: Boolean = false
        private set

    private var downX = 0f
    private var downY = 0f

    fun begin(
        x: Float,
        y: Float,
        requestedMode: InsertObjectGestureMode,
        manipulationEnabled: Boolean,
    ) {
        downX = x
        downY = y
        mode = if (manipulationEnabled) requestedMode else InsertObjectGestureMode.NONE
        movementCommitted = false
        multiTouchSeen = false
    }

    fun move(x: Float, y: Float): Boolean {
        if (!movementCommitted && hypot(x - downX, y - downY) > movementSlop) {
            movementCommitted = true
        }
        return movementCommitted && mode != InsertObjectGestureMode.NONE
    }

    fun addPointer() {
        multiTouchSeen = true
        movementCommitted = false
        mode = InsertObjectGestureMode.NONE
    }

    fun canOpenContextMenu(): Boolean = !movementCommitted && !multiTouchSeen

    fun end() {
        mode = InsertObjectGestureMode.NONE
        movementCommitted = false
        multiTouchSeen = false
    }
}
