package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportStateTest {
    @Test
    fun highZoomPresetsKeepTheAnchorAndCoordinateConversion() {
        listOf(8f, 16f, 32f, 64f).forEach { scale ->
            val viewport = ViewportState()
            viewport.applyGesture(0f, 0f, 45f, 70f, 1f)
            viewport.setScaleAround(scale, 300f, 180f)

            assertEquals(scale, viewport.scale, 0.0001f)
            assertEquals(255f, (300f - viewport.translationX) / viewport.scale, 0.001f)
            assertEquals(110f, (180f - viewport.translationY) / viewport.scale, 0.001f)
            val viewX = 42.5f * viewport.scale + viewport.translationX
            val viewY = 98.2f * viewport.scale + viewport.translationY
            assertEquals(42.5f, (viewX - viewport.translationX) / viewport.scale, 0.001f)
            assertEquals(98.2f, (viewY - viewport.translationY) / viewport.scale, 0.001f)
        }
    }

    @Test
    fun gestureAtTheNewMaximumStillPansAndCanZoomBackOut() {
        val viewport = ViewportState()
        viewport.applyGesture(100f, 120f, 100f, 120f, 100f)
        assertEquals(64f, viewport.scale, 0.0001f)

        viewport.applyGesture(100f, 120f, 135f, 170f, 2f)
        assertEquals(64f, viewport.scale, 0.0001f)
        assertEquals(100f, (135f - viewport.translationX) / viewport.scale, 0.001f)
        assertEquals(120f, (170f - viewport.translationY) / viewport.scale, 0.001f)

        viewport.setScaleAround(32f, 135f, 170f)
        assertEquals(32f, viewport.scale, 0.0001f)
        assertEquals(100f, (135f - viewport.translationX) / viewport.scale, 0.001f)
        assertEquals(120f, (170f - viewport.translationY) / viewport.scale, 0.001f)
        viewport.setScaleAround(0.01f, 135f, 170f)
        assertEquals(0.5f, viewport.scale, 0.0001f)
    }

    @Test
    fun highZoomSnapshotRestoresWithoutFallingBackToTheOldMaximum() {
        val viewport = ViewportState()
        viewport.setScaleAround(64f, 300f, 180f)
        val saved = viewport.snapshot()
        val restored = ViewportState()
        restored.restore(saved)

        assertEquals(saved, restored.snapshot())
        restored.restore(saved.copy(scale = 128f))
        assertEquals(64f, restored.scale, 0.0001f)
    }

    @Test
    fun centerDocumentPointMovesTheRequestedPointToTheViewCenter() {
        val viewport = ViewportState()

        viewport.centerDocumentPoint(120f, 80f, 500f, 300f)

        assertEquals(380f, viewport.translationX, 0.001f)
        assertEquals(220f, viewport.translationY, 0.001f)
    }

    @Test
    fun twoFingerTranslationMovesViewportWithoutScaling() {
        val viewport = ViewportState()

        viewport.applyGesture(100f, 120f, 135f, 170f, 1f)

        assertEquals(1f, viewport.scale, 0.0001f)
        assertEquals(35f, viewport.translationX, 0.0001f)
        assertEquals(50f, viewport.translationY, 0.0001f)
    }

    @Test
    fun pinchKeepsDocumentPointUnderGestureFocus() {
        val viewport = ViewportState()

        viewport.applyGesture(100f, 200f, 100f, 200f, 2f)

        assertEquals(2f, viewport.scale, 0.0001f)
        assertEquals(-100f, viewport.translationX, 0.0001f)
        assertEquals(-200f, viewport.translationY, 0.0001f)
    }

    @Test
    fun zoomIsClampedToDocumentLimits() {
        val viewport = ViewportState(minScale = 0.5f, maxScale = 8f)

        viewport.applyGesture(0f, 0f, 0f, 0f, 100f)
        assertEquals(8f, viewport.scale, 0.0001f)

        viewport.applyGesture(0f, 0f, 0f, 0f, 0.001f)
        assertEquals(0.5f, viewport.scale, 0.0001f)
    }

    @Test
    fun explicitZoomKeepsTheAnchorDocumentPointStationary() {
        val viewport = ViewportState()

        viewport.setScaleAround(2f, 300f, 180f)

        assertEquals(2f, viewport.scale, 0.0001f)
        assertEquals(-300f, viewport.translationX, 0.0001f)
        assertEquals(-180f, viewport.translationY, 0.0001f)
    }

    @Test
    fun fitDocumentWidthCentersThePageInsideTheRequestedPadding() {
        val viewport = ViewportState(minScale = 0.1f)

        viewport.fitDocumentWidth(documentLeft = 10f, documentWidth = 1000f, viewWidth = 800f, horizontalPadding = 40f)

        assertEquals(0.72f, viewport.scale, 0.0001f)
        assertEquals(32.8f, viewport.translationX, 0.0001f)
    }

    @Test
    fun snapshotRestoresAnIndependentPaneViewport() {
        val viewport = ViewportState()
        viewport.applyGesture(0f, 0f, 45f, 70f, 2f)
        val snapshot = viewport.snapshot()
        viewport.reset()

        viewport.restore(snapshot)

        assertEquals(2f, viewport.scale, 0.0001f)
        assertEquals(45f, viewport.translationX, 0.0001f)
        assertEquals(70f, viewport.translationY, 0.0001f)
    }
}
