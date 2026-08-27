package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasInputRoutingTest {
    @Test
    fun mouseSecondaryUsesMovementOnlyToChooseLasso() {
        assertEquals(false, CanvasInputRouting.mouseSecondaryStartsLasso(5f, 5f, 8f))
        assertEquals(true, CanvasInputRouting.mouseSecondaryStartsLasso(8f, 0f, 8f))
    }

    @Test
    fun stylusShortcutUsesOnlyThePressReleaseTimeWindow() {
        assertEquals(
            SecondaryReleaseIntent.OPEN_CONTEXT_MENU,
            CanvasInputRouting.stylusShortcutReleaseIntent(449, 450),
        )
        assertEquals(
            SecondaryReleaseIntent.COMMIT_LASSO,
            CanvasInputRouting.stylusShortcutReleaseIntent(450, 450),
        )
    }

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
