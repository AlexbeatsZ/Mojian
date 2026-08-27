package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureEraserControllerTest {
    @Test
    fun requiresCompletedPenLiftBeforePressureCanTrigger() {
        val controller = PressureEraserController(pressureThreshold = 0.75f, dwellMillis = 140L)

        assertNull(controller.updateSample(0.9f, 0f, 0f, 1_000L))
        assertFalse(controller.activate(2_000L))

        controller.onStylusLift(completedInk = true)
        controller.updateSample(0.9f, 10f, 10f, 3_000L)
        assertFalse(controller.activate(3_139L))
        assertTrue(controller.activate(3_140L))
    }

    @Test
    fun pressureDropRestartsDwellWindow() {
        val controller = PressureEraserController(pressureThreshold = 0.75f, dwellMillis = 140L)
        controller.onStylusLift(completedInk = true)

        controller.updateSample(0.9f, 0f, 0f, 1_000L)
        assertNull(controller.updateSample(0.5f, 0f, 0f, 1_050L))
        controller.updateSample(0.9f, 0f, 0f, 1_100L)

        assertFalse(controller.activate(1_200L))
        assertTrue(controller.activate(1_240L))
    }

    @Test
    fun liftingAfterEraseRestoresPenButKeepsFeatureArmed() {
        val controller = PressureEraserController(pressureThreshold = 0.75f, dwellMillis = 100L)
        controller.onStylusLift(completedInk = true)
        controller.updateSample(0.9f, 0f, 0f, 10L)
        assertTrue(controller.activate(110L))

        controller.onStylusLift(completedInk = false)

        assertFalse(controller.isErasing)
        assertTrue(controller.isArmed)
    }

    @Test
    fun heavyWritingResetsDwellInsteadOfErasing() {
        val controller =
            PressureEraserController(
                pressureThreshold = 0.75f,
                dwellMillis = 140L,
                stationarySlop = 4f,
            )
        controller.onStylusLift(completedInk = true)

        controller.updateSample(0.9f, 0f, 0f, 1_000L)
        controller.updateSample(0.9f, 8f, 0f, 1_080L)
        controller.updateSample(0.9f, 16f, 0f, 1_160L)

        assertFalse(controller.activate(1_240L))
        assertTrue(controller.activate(1_300L))
    }

    @Test
    fun stationaryJitterWithinSlopStillTriggers() {
        val controller =
            PressureEraserController(
                pressureThreshold = 0.75f,
                dwellMillis = 140L,
                stationarySlop = 4f,
            )
        controller.onStylusLift(completedInk = true)

        controller.updateSample(0.9f, 100f, 100f, 1_000L)
        controller.updateSample(0.9f, 102f, 101f, 1_080L)

        assertTrue(controller.activate(1_140L))
    }

    @Test
    fun onlyWritingToolsCanUseTheEraserOwnedPressureSetting() {
        assertTrue(PressureEraserPolicy.canTrigger(DrawingToolKind.PEN, eraserEnabled = true))
        assertTrue(PressureEraserPolicy.canTrigger(DrawingToolKind.HIGHLIGHTER, eraserEnabled = true))
        assertFalse(PressureEraserPolicy.canTrigger(DrawingToolKind.LASSO, eraserEnabled = true))
        assertFalse(PressureEraserPolicy.canTrigger(DrawingToolKind.ERASER, eraserEnabled = true))
        assertFalse(PressureEraserPolicy.canTrigger(DrawingToolKind.PEN, eraserEnabled = false))
    }

    @Test
    fun cancellationRestoresTemporaryEraserState() {
        val controller = PressureEraserController(pressureThreshold = 0.75f, dwellMillis = 100L)
        controller.onStylusLift(completedInk = true)
        controller.updateSample(0.9f, 0f, 0f, 10L)
        assertTrue(controller.activate(110L))

        controller.cancelGesture()

        assertFalse(controller.isErasing)
    }
}
