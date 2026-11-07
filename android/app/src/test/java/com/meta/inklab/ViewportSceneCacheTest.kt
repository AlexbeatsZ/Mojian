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

    @Test
    fun restTilesMustMatchZoomAndSubPixelAnchor() {
        val fraction = ViewportSceneCache.anchorFraction(120.25f)
        assertEquals(0.25f, fraction, 1e-6f)
        assertEquals(0.75f, ViewportSceneCache.anchorFraction(-3.25f), 1e-6f)
        assertTrue(ViewportSceneCache.exactAtRest(2f, 2f, fraction, 0.5f, 7.25f, -1.5f))
        // A whole-pixel pan keeps the anchor; a sub-pixel pan or any zoom change does not.
        assertTrue(ViewportSceneCache.exactAtRest(2f, 2f, fraction, 0.5f, 300.25f, 40.5f))
        assertFalse(ViewportSceneCache.exactAtRest(2f, 2f, fraction, 0.5f, 7.5f, -1.5f))
        assertFalse(ViewportSceneCache.exactAtRest(2f, 2.01f, fraction, 0.5f, 7.25f, -1.5f))
        assertFalse(ViewportSceneCache.exactAtRest(0f, 2f, fraction, 0.5f, 7.25f, -1.5f))
    }

    @Test
    fun recordBoundsCoverTheAnchoredTileWithSeamMargin() {
        val bounds = ViewportSceneCache.recordBounds(2f, 1, 0, 0.5f, 0f)
        val margin = ViewportSceneCache.RECORD_MARGIN_PX
        assertEquals((size - 0.5f - margin) / 2f, bounds.left, 1e-4f)
        assertEquals((2 * size - 0.5f + margin) / 2f, bounds.right, 1e-4f)
        assertEquals(-margin / 2f, bounds.top, 1e-4f)
        // Neighbouring tiles overlap, so anti-aliased ink on the boundary is recorded by both.
        val left = ViewportSceneCache.recordBounds(2f, 0, 0, 0.5f, 0f)
        assertTrue(left.right > bounds.left)
    }

    @Test
    fun consecutiveLocalEditsRecordOnlyTheirRegion() {
        val tracker = SceneTileContentTracker()
        assertEquals(SceneTileContentTracker.Sync.Full, tracker.sync(10))
        assertEquals(SceneTileContentTracker.Sync.Unchanged, tracker.sync(10))
        tracker.markLocal(10, 11, StrokeViewportBounds(0f, 0f, 5f, 5f))
        tracker.markLocal(11, 13, StrokeViewportBounds(20f, 1f, 30f, 2f))
        assertEquals(SceneTileContentTracker.Sync.Partial(StrokeViewportBounds(0f, 0f, 30f, 5f)), tracker.sync(13))
        assertEquals(SceneTileContentTracker.Sync.Unchanged, tracker.sync(13))
    }

    @Test
    fun anyUntrackedMutationRecordsEverything() {
        val tracker = SceneTileContentTracker()
        tracker.sync(10)
        // Undo/transform/page replacement bump the revision without a local region.
        assertEquals(SceneTileContentTracker.Sync.Full, tracker.sync(12))
        // A local edit after an unsynchronized change cannot be partial either.
        tracker.markLocal(14, 15, StrokeViewportBounds(0f, 0f, 1f, 1f))
        assertEquals(SceneTileContentTracker.Sync.Full, tracker.sync(15))
        // A broken chain (another change between two local edits) falls back to full.
        tracker.markLocal(15, 16, StrokeViewportBounds(0f, 0f, 1f, 1f))
        tracker.markLocal(17, 18, StrokeViewportBounds(0f, 0f, 1f, 1f))
        assertEquals(SceneTileContentTracker.Sync.Full, tracker.sync(18))
        // Dropped tiles start over.
        tracker.markLocal(18, 19, StrokeViewportBounds(0f, 0f, 1f, 1f))
        tracker.reset()
        assertEquals(SceneTileContentTracker.Sync.Full, tracker.sync(19))
    }
}
