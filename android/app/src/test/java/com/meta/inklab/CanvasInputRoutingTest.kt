package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasInputRoutingTest {
    @Test
    fun middleButtonReusesPanoramaDragAndCtrlPromotesItToZoom() {
        assertEquals(
            CanvasDragIntent.PAN,
            CanvasInputRouting.mouseDragIntent(true, false),
        )
        assertEquals(
            CanvasDragIntent.ZOOM,
            CanvasInputRouting.mouseDragIntent(true, true),
        )
        assertEquals(CanvasDragIntent.NONE, CanvasInputRouting.mouseDragIntent(false, false))
    }

    @Test
    fun primaryMouseIsAlwaysReservedForDirectContentManipulation() {
        assertEquals(
            CanvasDragIntent.NONE,
            CanvasInputRouting.mouseDragIntent(false, false),
        )
        assertEquals(
            CanvasDragIntent.NONE,
            CanvasInputRouting.mouseDragIntent(false, false),
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

    @Test
    fun panoramaSensitivityScalesOnlyTheSinglePointerPanDelta() {
        assertEquals(PanFocus(130f, 70f), CanvasInputRouting.scaledPanFocus(100f, 100f, 120f, 80f, 1.5f))
        assertEquals(PanFocus(105f, 95f), CanvasInputRouting.scaledPanFocus(100f, 100f, 120f, 80f, 0.25f))
        assertEquals(PanFocus(160f, 40f), CanvasInputRouting.scaledPanFocus(100f, 100f, 120f, 80f, 9f))
    }
}
