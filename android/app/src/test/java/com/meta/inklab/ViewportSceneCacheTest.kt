package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportSceneCacheTest {
    private val size = ViewportSceneCache.TILE_SIZE

    @Test
    fun visibleTilesCoverExactlyThePhoneView() {
        val range = requireNotNull(ViewportSceneCache.visibleTiles(1f, 1f, 0f, 0f, 1440, 3200))
        assertEquals(SceneTileRange(0, 2, 0, 6), range)
        // A view edge that lands exactly on a tile boundary does not pull in the next tile.
        assertEquals(SceneTileRange(0, 1, 0, 1), ViewportSceneCache.visibleTiles(1f, 1f, 0f, 0f, size * 2, size * 2))
    }

    @Test
    fun panningShiftsTilesInBaseScaleSpace() {
        // Content moved up by 1.5 tiles: rows start at the tile containing base pixel 768.
        val range = requireNotNull(ViewportSceneCache.visibleTiles(2f, 2f, -10f, -size * 1.5f, 1000, 1000))
        assertEquals(0, range.firstColumn)
        assertEquals(1, range.firstRow)
        assertEquals(((size * 1.5f + 1000) / size).toInt(), range.lastRow)
        // Negative document coordinates (imported legacy paper) use negative tile indices.
        assertEquals(-1, requireNotNull(ViewportSceneCache.visibleTiles(1f, 1f, 100f, 100f, 400, 400)).firstColumn)
    }

    @Test
    fun zoomDuringGestureMapsViewIntoBaseTiles() {
        // Zoomed in 1.25x around the origin: the same view shows fewer base pixels.
        val range = requireNotNull(ViewportSceneCache.visibleTiles(1f, 1.25f, 0f, 0f, 1280, 1280))
        assertEquals(SceneTileRange(0, 1, 0, 1), range)
        assertNull(ViewportSceneCache.visibleTiles(1f, 1.6f, 0f, 0f, 1280, 1280))
    }

    @Test
    fun scaleDriftForcesReRecording() {
        assertTrue(ViewportSceneCache.scaleUsable(1f, 1.5f))
        assertTrue(ViewportSceneCache.scaleUsable(1f, 1f / 1.5f))
        assertFalse(ViewportSceneCache.scaleUsable(1f, 1.51f))
        assertFalse(ViewportSceneCache.scaleUsable(1f, 0.6f))
        assertFalse(ViewportSceneCache.scaleUsable(0f, 1f))
    }

    @Test
    fun tileDocumentBoundsMatchTheRecordingTransform() {
        val bounds = ViewportSceneCache.documentBounds(2f, -1, 3)
        assertEquals(StrokeViewportBounds(-size / 2f, size * 1.5f, 0f, size * 2f), bounds)
    }

    @Test
    fun prefetchVisitsNearestTilesFirstAndKeysRoundTrip() {
        val visible = SceneTileRange(0, 0, 0, 0)
        val order = mutableListOf<Pair<Int, Int>>()
        visible.expanded(2).forEachNearest(visible) { column, row -> order += column to row }
        assertEquals(25, order.size)
        assertEquals(0 to 0, order.first())
        assertTrue(order.take(9).all { (column, row) -> column in -1..1 && row in -1..1 })
        for ((column, row) in listOf(0 to 0, -3 to 7, 5 to -9, Int.MIN_VALUE to Int.MAX_VALUE)) {
            val key = ViewportSceneCache.key(column, row)
            assertEquals(column, ViewportSceneCache.column(key))
            assertEquals(row, ViewportSceneCache.row(key))
        }
    }
}
