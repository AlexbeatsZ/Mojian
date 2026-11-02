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
 * Geometry of the gesture-only tiled scene cache. While a pan/zoom gesture is active the
 * composed scene is recorded once per tile at a base zoom; samples only move those layers.
 * Release restores direct vector drawing, so nothing cached survives a static frame.
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

    fun key(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xffffffffL)
    fun column(key: Long): Int = (key shr 32).toInt()
    fun row(key: Long): Int = key.toInt()
}
