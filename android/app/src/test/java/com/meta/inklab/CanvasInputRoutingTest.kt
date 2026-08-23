package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasInputRoutingTest {
    @Test
    fun middleButtonReusesPanoramaDragAndCtrlPromotesItToZoom() {
        assertEquals(
            CanvasDragIntent.PAN,
            CanvasInputRouting.mouseDragIntent(false, true, false, false, false),
        )
        assertEquals(
            CanvasDragIntent.ZOOM,
            CanvasInputRouting.mouseDragIntent(false, true, true, false, false),
        )
        assertEquals(
            CanvasDragIntent.PAN,
            CanvasInputRouting.mouseDragIntent(true, false, false, true, false),
        )
    }

    @Test
    fun mouseCanLassoButNeverFallsThroughToInk() {
        assertEquals(
            CanvasDragIntent.LASSO,
            CanvasInputRouting.mouseDragIntent(true, false, false, false, true),
        )
        assertEquals(
            CanvasDragIntent.NONE,
            CanvasInputRouting.mouseDragIntent(true, false, false, false, false),
        )
    }

    @Test
    fun shiftWheelIsHorizontalAndCtrlTakesPriority() {
        assertEquals(
            WheelDecision(WheelIntent.PAN_HORIZONTAL, 1f),
            CanvasInputRouting.wheelDecision(1f, 0f, shiftPressed = true, ctrlPressed = false),
        )
        assertEquals(
            WheelDecision(WheelIntent.ZOOM, 1f),
            CanvasInputRouting.wheelDecision(1f, 0f, shiftPressed = true, ctrlPressed = true),
        )
    }
}
