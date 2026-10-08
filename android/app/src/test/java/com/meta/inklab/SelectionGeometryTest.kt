package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {
    @Test
    fun tinyAndNarrowSelectionsRetainBodyAndAllHandlesAtEveryZoom() {
        for (scale in listOf(.5f, 1f, 8f, 64f))
        for ((width, height) in listOf(8f to 6f, 4f to 100f, 100f to 4f))
        for (radius in listOf(12f, 24f)) {
            val bounds = DocumentRect(100f, 200f, 100f + width * scale, 200f + height * scale)
            val display = DocumentRect(bounds.left - 8f, bounds.top - 8f, bounds.right + 8f, bounds.bottom + 8f)
            for (fraction in listOf(.3f, .5f, .7f)) {
                val point = DocumentPoint(bounds.left + width * scale * fraction, bounds.top + height * scale * fraction)
                assertEquals(-1, SelectionGeometry.hitSelectionHandle(point, bounds, display, radius))
            }
            SelectionGeometry.selectionHandles(display).forEachIndexed { index, point ->
                assertEquals(index, SelectionGeometry.hitSelectionHandle(point, bounds, display, radius))
            }
            assertEquals(-1, SelectionGeometry.hitSelectionHandle(DocumentPoint(display.right + radius + 1, display.bottom + radius + 1), bounds, display, radius))
        }
    }

    @Test
    fun allCornersPreserveRatioAndOppositeAnchorIncludingMinimumClamp() {
        val start = DocumentRect(10f, 20f, 90f, 60f)
        for (handle in listOf(0, 2, 4, 6)) {
            val points = SelectionGeometry.selectionHandles(start)
            val corner = points[handle]
            val anchor = points[(handle + 4) % 8]
            for (target in listOf(DocumentPoint(corner.x + 91, corner.y - 27), anchor, DocumentPoint(2 * anchor.x - corner.x, 2 * anchor.y - corner.y))) {
                val resized = SelectionGeometry.resizeBounds(start, handle, target, 24f)
                assertEquals(2f, (resized.right - resized.left) / (resized.bottom - resized.top), .0001f)
                assertEquals(anchor, SelectionGeometry.selectionHandles(resized)[(handle + 4) % 8])
                assertTrue(resized.bottom - resized.top >= 24f)
            }
        }
        val tiny = DocumentRect(10f, 20f, 18f, 24f)
        assertEquals(tiny, SelectionGeometry.resizeBounds(tiny, 4, DocumentPoint(18f, 24f), 24f))
    }

    @Test
    fun edgeHandlesChangeOnlyTheirOwnAxis() {
        val start = DocumentRect(10f, 20f, 90f, 60f)
        for (handle in listOf(1, 3, 5, 7)) {
            val resized = SelectionGeometry.resizeBounds(start, handle, DocumentPoint(200f, 200f), 24f)
            if (handle == 1 || handle == 5) {
                assertEquals(start.left, resized.left, .0001f); assertEquals(start.right, resized.right, .0001f)
            } else {
                assertEquals(start.top, resized.top, .0001f); assertEquals(start.bottom, resized.bottom, .0001f)
            }
        }
    }

    @Test
    fun polygonHitTestDistinguishesInsideAndOutsidePoints() {
        val polygon =
            listOf(
                DocumentPoint(0f, 0f),
                DocumentPoint(100f, 0f),
                DocumentPoint(100f, 80f),
                DocumentPoint(0f, 80f),
            )

        assertTrue(SelectionGeometry.pointInPolygon(40f, 30f, polygon))
        assertFalse(SelectionGeometry.pointInPolygon(120f, 30f, polygon))
    }

    @Test
    fun translationMovesDocumentPointByDragDelta() {
        val moved = SelectionGeometry.translate(12f, 18f, -2f, 7f)

        assertEquals(10f, moved.x, 0.001f)
        assertEquals(25f, moved.y, 0.001f)
    }

    @Test
    fun scalingKeepsOppositeHandleAnchorFixed() {
        val scaled = SelectionGeometry.scale(20f, 30f, 10f, 10f, 1.5f)

        assertEquals(25f, scaled.x, 0.001f)
        assertEquals(40f, scaled.y, 0.001f)
    }

    @Test
    fun overlappingHandleTargetsChooseTheNearestVisibleHandle() {
        val handles = listOf(DocumentPoint(0f, 0f), DocumentPoint(18f, 0f), DocumentPoint(36f, 0f))

        assertEquals(1, SelectionGeometry.nearestPointIndexWithinRadius(15f, 2f, handles, radius = 24f))
        assertEquals(-1, SelectionGeometry.nearestPointIndexWithinRadius(70f, 0f, handles, radius = 24f))
    }

    @Test
    fun rectangleLassoNormalizesDragDirection() {
        val polygon = SelectionGeometry.rectanglePolygon(DocumentPoint(90f, 70f), DocumentPoint(20f, 10f))

        assertEquals(DocumentPoint(20f, 10f), polygon[0])
        assertEquals(DocumentPoint(90f, 70f), polygon[2])
    }

    @Test
    fun lassoSelectsOverlappingBoxesAndRejectsOutsideBoxes() {
        val polygon =
            listOf(
                DocumentPoint(0f, 0f),
                DocumentPoint(100f, 0f),
                DocumentPoint(100f, 100f),
                DocumentPoint(0f, 100f),
            )

        assertTrue(SelectionGeometry.polygonIntersectsRect(polygon, DocumentRect(80f, 80f, 130f, 130f)))
        assertTrue(SelectionGeometry.polygonIntersectsRect(polygon, DocumentRect(25f, 25f, 75f, 75f)))
        assertTrue(
            SelectionGeometry.polygonIntersectsRect(
                listOf(DocumentPoint(-20f, 45f), DocumentPoint(120f, 45f), DocumentPoint(120f, 55f), DocumentPoint(-20f, 55f)),
                DocumentRect(40f, 20f, 60f, 80f),
            ),
        )
        assertFalse(SelectionGeometry.polygonIntersectsRect(polygon, DocumentRect(120f, 120f, 150f, 150f)))
    }

    @Test
    fun containmentSelectionRejectsPartiallyCoveredLargeObject() {
        val polygon =
            listOf(
                DocumentPoint(0f, 0f),
                DocumentPoint(100f, 0f),
                DocumentPoint(100f, 100f),
                DocumentPoint(0f, 100f),
            )

        assertTrue(SelectionGeometry.polygonContainsRect(polygon, DocumentRect(20f, 20f, 80f, 80f)))
        assertTrue(SelectionGeometry.polygonContainsRect(polygon, DocumentRect(0f, 0f, 100f, 100f)))
        assertFalse(SelectionGeometry.polygonContainsRect(polygon, DocumentRect(80f, 80f, 130f, 130f)))
    }
}
