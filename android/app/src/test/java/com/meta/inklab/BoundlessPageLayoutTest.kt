package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BoundlessPageLayoutTest {
    @Test
    fun onlyContentAtRightOrBottomThresholdGrowsTheCorrespondingEdge() {
        val settings = PageViewSettings()
        val threshold = settings.boundlessDocumentWidth - BoundlessPageLayout.autoGrowMargin
        assertFalse(BoundlessPageLayout.ensureCapacity(settings, sequenceOf(StrokeViewportBounds(10f, 10f, threshold - 1f, 20f))))
        assertTrue(BoundlessPageLayout.ensureCapacity(settings, sequenceOf(StrokeViewportBounds(10f, 10f, threshold, 20f))))
        assertEquals(BoundlessPageLayout.initialWidth + BoundlessPageLayout.growthStep, settings.boundlessDocumentWidth, 0.001f)
        assertEquals(BoundlessPageLayout.initialHeight, settings.boundlessDocumentHeight, 0.001f)
        val bottom = settings.boundlessDocumentHeight - BoundlessPageLayout.autoGrowMargin
        assertTrue(BoundlessPageLayout.ensureCapacity(settings, sequenceOf(StrokeViewportBounds(10f, 10f, 20f, bottom))))
        assertEquals(BoundlessPageLayout.initialHeight + BoundlessPageLayout.growthStep, settings.boundlessDocumentHeight, 0.001f)
    }

    @Test
    fun removalAndViewportNavigationNeverShrinkOrGrowStoredPaper() {
        val settings = PageViewSettings()
        val content = StrokeViewportBounds(100f, 200f, 10000f, 12000f)
        assertTrue(BoundlessPageLayout.ensureCapacity(settings, sequenceOf(content)))
        val enlarged = settings.copy()
        val viewport = ViewportState()
        viewport.applyGesture(0f, 0f, -30000f, -30000f, 2f)
        assertFalse(BoundlessPageLayout.ensureCapacity(settings, emptySequence()))
        assertEquals(enlarged, settings)
        val restored = PageViewSettings.fromJson(settings.toJson())
        assertEquals(enlarged, restored)
        assertTrue(restored.boundlessDocumentWidth > content.right + BoundlessPageLayout.autoGrowMargin)
        assertTrue(restored.boundlessDocumentHeight > content.bottom + BoundlessPageLayout.autoGrowMargin)
    }

    @Test
    fun contentAboveOrLeftOfOriginDoesNotGrowPaperAndPdfOrA4AreUnaffected() {
        val content = sequenceOf(StrokeViewportBounds(-5000f, -10f, -4000f, 9000f), StrokeViewportBounds(0f, -5000f, 9000f, -4000f))
        assertFalse(BoundlessPageLayout.ensureCapacity(PageViewSettings(), content))
        for (paper in listOf(PaperSize.A4, PaperSize.PDF)) {
            val settings = PageViewSettings(paperSize = paper)
            assertFalse(BoundlessPageLayout.ensureCapacity(settings, sequenceOf(StrokeViewportBounds(0f, 0f, 9000f, 9000f))))
        }
        assertEquals(BoundlessPageLayout.initialWidth, PageViewSettings.fromJson(JSONObject()).documentWidth!!, 0.001f)
    }

    @Test
    fun exportUsesCurrentContentWithPaddingRegardlessOfStoredPaperOrViewport() {
        val content = listOf(StrokeViewportBounds(-20f, 10f, 30f, 60f), StrokeViewportBounds(500f, 800f, 900f, 1000f))
        val crop = BoundlessPageLayout.exportBounds(content.asSequence())
        assertEquals(-20f - BoundlessPageLayout.exportPadding, crop.left, 0.001f)
        assertEquals(1000f + BoundlessPageLayout.exportPadding, crop.bottom, 0.001f)
        val reduced = BoundlessPageLayout.exportBounds(content.take(1).asSequence())
        assertTrue(reduced.right < crop.right)
        assertTrue(reduced.bottom < crop.bottom)
        assertEquals(BoundlessPageLayout.initialWidth, BoundlessPageLayout.exportBounds(emptySequence()).right, 0.001f)
    }
}
