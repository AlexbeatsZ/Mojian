package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureEraserControllerTest {
    @Test
    fun lowPressureTravelDisablesLaterDwellEvenAfterReturningToStart() {
        val controller = PressureEraserController(travelLimitMm = 1f)
        controller.onStylusLift(completedInk = true)
        controller.beginContact(0f, 0f)
        controller.observeContactTravel(.6f, 0f)
        controller.updateSample(.2f, 6f, 0f, 100L)
        controller.observeContactTravel(0f, 0f) // 1.2 mm path, zero displacement.
        assertTrue(controller.isTravelLimitExceeded)
        controller.cancelCandidate()
        assertNull(controller.updateSample(.95f, 0f, 0f, 200L))
        assertFalse(controller.activate(1000L))
        controller.onStylusLift(completedInk = true)
        controller.beginContact(0f, 0f)
        controller.updateSample(.95f, 0f, 0f, 1100L)
        assertTrue(controller.activate(1240L))
    }

    @Test
    fun crossingLimitCancelsPendingTimerButActivatedEraserCanMove() {
        val controller = PressureEraserController(travelLimitMm = 1f)
        controller.onStylusLift(completedInk = true)
        controller.beginContact(0f, 0f)
        controller.updateSample(.95f, 0f, 0f, 100L)
        controller.observeContactTravel(1.1f, 0f)
        assertFalse(controller.activate(1000L))
        controller.cancelGesture()
        controller.beginContact(0f, 0f)
        controller.updateSample(.95f, 0f, 0f, 1100L)
        assertTrue(controller.activate(1240L))
        controller.observeContactTravel(100f, 100f)
        assertTrue(controller.isErasing)
        assertFalse(controller.isTravelLimitExceeded)
    }

    @Test
    fun travelLimitIncludesBoundaryAndIsCapturedAtContactStart() {
        val controller = PressureEraserController(travelLimitMm = 1f)
        controller.onStylusLift(completedInk = true)
        controller.beginContact(0f, 0f)
        controller.observeContactTravel(1f, 0f)
        assertFalse(controller.isTravelLimitExceeded)
        controller.travelLimitMm = 5f
        controller.observeContactTravel(1.01f, 0f)
        assertTrue(controller.isTravelLimitExceeded)
        controller.onStylusLift(completedInk = true)
        controller.beginContact(0f, 0f)
        controller.observeContactTravel(4f, 0f)
        assertFalse(controller.isTravelLimitExceeded)
    }

    @Test
    fun travelSettingDefaultsMigratesClampsAndPersists() {
        val json = org.json.JSONObject("""{"id":"eraser-stroke","kind":"ERASER"}""")
        assertTrue(ToolPreset.fromJson(json)!!.pressureEraseTravelLimitMm == 1f)
        json.put("pressureEraseTravelLimitMm", .001)
        assertTrue(ToolPreset.fromJson(json)!!.pressureEraseTravelLimitMm == .1f)
        json.put("pressureEraseTravelLimitMm", 50)
        assertTrue(ToolPreset.fromJson(json)!!.pressureEraseTravelLimitMm == 10f)
        val shelf = ToolShelf()
        shelf.pressureEraser.pressureEraseTravelLimitMm = 2.3f
        val restored = ToolShelf.fromJson(shelf.toJson())!!
        assertTrue(restored.pressureEraser.pressureEraseTravelLimitMm == 2.3f)
    }

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
