package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {
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
}
