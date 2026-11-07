package com.meta.inklab

import kotlin.math.floor

/** Inclusive range of scene tiles; tile (c,r) covers base-scale pixels [c*size,(c+1)*size). */
internal data class SceneTileRange(val firstColumn: Int, val lastColumn: Int, val firstRow: Int, val lastRow: Int) {
    fun contains(column: Int, row: Int): Boolean = column in firstColumn..lastColumn && row in firstRow..lastRow

    fun expanded(margin: Int): SceneTileRange =
        SceneTileRange(firstColumn - margin, lastColumn + margin, firstRow - margin, lastRow + margin)

    /** Rows nearest the view first, so prefetch records the tiles a pan reaches soonest. */
    fun forEachNearest(center: SceneTileRange, action: (Int, Int) -> Unit) {
        val tiles = ArrayList<Long>()
        for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) tiles += ViewportSceneCache.key(column, row)
        tiles.sortBy { key ->
            val column = ViewportSceneCache.column(key)
            val row = ViewportSceneCache.row(key)
            val dx = maxOf(center.firstColumn - column, column - center.lastColumn, 0)
            val dy = maxOf(center.firstRow - row, row - center.lastRow, 0)
            maxOf(dx, dy)
        }
        tiles.forEach { action(ViewportSceneCache.column(it), ViewportSceneCache.row(it)) }
    }
}

/**
 * Geometry of the tiled scene cache. The composed scene is recorded once per tile at a base zoom and
 * a sub-pixel anchor. Pan/zoom samples only move those layers; at rest the tiles must sit at exactly
 * the current zoom and fractional translation, so they are drawn 1:1 on whole pixels and match direct
 * vector drawing. Appended or erased ink re-records only the tiles its bounds touch.
 */
internal object ViewportSceneCache {
    const val TILE_SIZE = 512
    const val PREFETCH_TILES = 1
    const val MAX_SCALE_DRIFT = 1.5f
    const val PREFETCH_BUDGET_NANOS = 6_000_000L

    fun scaleUsable(baseScale: Float, scale: Float): Boolean {
        if (!(baseScale > 0f) || !(scale > 0f)) return false
        val ratio = scale / baseScale
        return ratio <= MAX_SCALE_DRIFT && ratio >= 1f / MAX_SCALE_DRIFT
    }

    /** Tiles of the base-scale grid that intersect a view of the given size. */
    fun visibleTiles(baseScale: Float, scale: Float, translationX: Float, translationY: Float, viewWidth: Int, viewHeight: Int): SceneTileRange? {
        if (viewWidth <= 0 || viewHeight <= 0 || !scaleUsable(baseScale, scale)) return null
        val toBase = baseScale / scale
        val left = -translationX * toBase
        val top = -translationY * toBase
        val right = (viewWidth - translationX) * toBase
        val bottom = (viewHeight - translationY) * toBase
        val size = TILE_SIZE.toFloat()
        return SceneTileRange(
            floor(left / size).toInt(),
            floor(Math.nextDown(right) / size).toInt(),
            floor(top / size).toInt(),
            floor(Math.nextDown(bottom) / size).toInt(),
        )
    }

    /** Document rectangle (left, top, right, bottom) drawn by one tile. */
    fun documentBounds(baseScale: Float, column: Int, row: Int): StrokeViewportBounds {
        val size = TILE_SIZE / baseScale
        return StrokeViewportBounds(column * size, row * size, (column + 1) * size, (row + 1) * size)
    }

    /** Sub-pixel part of a translation, recorded into tiles so they can be placed on whole pixels. */
    fun anchorFraction(translation: Float): Float = translation - floor(translation)

    /** At rest a tile set is usable only when it reproduces the vector scene pixel for pixel. */
    fun exactAtRest(baseScale: Float, scale: Float, fractionX: Float, fractionY: Float, translationX: Float, translationY: Float): Boolean =
        baseScale > 0f && kotlin.math.abs(scale - baseScale) <= baseScale * 1e-6f &&
            kotlin.math.abs(anchorFraction(translationX) - fractionX) <= 1e-3f &&
            kotlin.math.abs(anchorFraction(translationY) - fractionY) <= 1e-3f

    /**
     * Document rectangle whose content can reach one tile recorded with the given anchor fraction,
     * widened by [RECORD_MARGIN_PX] so anti-aliased edges never leave a seam between tiles.
     */
    fun recordBounds(baseScale: Float, column: Int, row: Int, fractionX: Float, fractionY: Float): StrokeViewportBounds {
        val size = TILE_SIZE.toFloat()
        val margin = RECORD_MARGIN_PX
        return StrokeViewportBounds(
            (column * size - fractionX - margin) / baseScale,
            (row * size - fractionY - margin) / baseScale,
            ((column + 1) * size - fractionX + margin) / baseScale,
            ((row + 1) * size - fractionY + margin) / baseScale,
        )
    }

    const val RECORD_MARGIN_PX = 2f

    fun union(a: StrokeViewportBounds, b: StrokeViewportBounds): StrokeViewportBounds =
        StrokeViewportBounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    fun key(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xffffffffL)
    fun column(key: Long): Int = (key shr 32).toInt()
    fun row(key: Long): Int = key.toInt()
}

/**
 * Decides how recorded tiles follow ink revisions. Consecutive local edits (appended strokes, erased
 * strokes) that start from the revision the tiles were synchronized at re-record only their region;
 * any other change in between, or an unknown mutation, re-records everything.
 */
internal class SceneTileContentTracker {
    sealed interface Sync {
        data object Unchanged : Sync
        data object Full : Sync
        data class Partial(val bounds: StrokeViewportBounds) : Sync
    }

    private var syncedRevision = Long.MIN_VALUE
    private var dirty: StrokeViewportBounds? = null
    private var dirtyBase = Long.MIN_VALUE
    private var dirtyRevision = Long.MIN_VALUE

    /** A local edit moved the content from [before] to [after], touching only [bounds]. */
    fun markLocal(before: Long, after: Long, bounds: StrokeViewportBounds) {
        val current = dirty
        dirty = when {
            current == null -> { dirtyBase = before; bounds }
            dirtyRevision == before -> ViewportSceneCache.union(current, bounds)
            else -> { dirtyBase = Long.MIN_VALUE; null }
        }
        dirtyRevision = after
    }

    fun sync(revision: Long): Sync {
        if (revision == syncedRevision) {
            dirty = null
            return Sync.Unchanged
        }
        val region = dirty
        val result = if (region != null && dirtyBase == syncedRevision && dirtyRevision == revision) Sync.Partial(region) else Sync.Full
        syncedRevision = revision
        dirty = null
        return result
    }

    /** Tiles were dropped; the next synchronization starts from scratch. */
    fun reset() {
        syncedRevision = Long.MIN_VALUE
        dirty = null
    }
}
