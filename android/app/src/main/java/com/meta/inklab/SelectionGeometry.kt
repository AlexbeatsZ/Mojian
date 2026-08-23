package com.meta.inklab

import kotlin.math.abs

data class DocumentPoint(val x: Float, val y: Float)

data class DocumentRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isEmpty: Boolean
        get() = left >= right || top >= bottom

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

object SelectionGeometry {
    fun translate(point: DocumentPoint, deltaX: Float, deltaY: Float): DocumentPoint =
        translate(point.x, point.y, deltaX, deltaY)

    fun translate(x: Float, y: Float, deltaX: Float, deltaY: Float): DocumentPoint =
        DocumentPoint(x + deltaX, y + deltaY)

    fun scale(point: DocumentPoint, anchor: DocumentPoint, factor: Float): DocumentPoint =
        scale(point.x, point.y, anchor.x, anchor.y, factor)

    fun scale(x: Float, y: Float, anchorX: Float, anchorY: Float, factor: Float): DocumentPoint =
        DocumentPoint(
            anchorX + (x - anchorX) * factor,
            anchorY + (y - anchorY) * factor,
        )

    fun pointInPolygon(x: Float, y: Float, polygon: List<DocumentPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses =
                ((current.y > y) != (previous.y > y)) &&
                    (x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x)
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }

    fun rectanglePolygon(start: DocumentPoint, end: DocumentPoint): List<DocumentPoint> {
        val left = minOf(start.x, end.x)
        val top = minOf(start.y, end.y)
        val right = maxOf(start.x, end.x)
        val bottom = maxOf(start.y, end.y)
        return listOf(
            DocumentPoint(left, top),
            DocumentPoint(right, top),
            DocumentPoint(right, bottom),
            DocumentPoint(left, bottom),
        )
    }

    /** Select boxes that overlap the lasso, not only boxes whose centre lies inside it. */
    fun polygonIntersectsRect(polygon: List<DocumentPoint>, rect: DocumentRect): Boolean {
        if (polygon.size < 3 || rect.isEmpty) return false
        val corners =
            listOf(
                DocumentPoint(rect.left, rect.top),
                DocumentPoint(rect.right, rect.top),
                DocumentPoint(rect.right, rect.bottom),
                DocumentPoint(rect.left, rect.bottom),
            )
        if (corners.any { pointInPolygon(it.x, it.y, polygon) }) return true
        if (polygon.any { rect.contains(it.x, it.y) }) return true

        val rectangleEdges = corners.indices.map { index -> corners[index] to corners[(index + 1) % corners.size] }
        return polygon.indices.any { index ->
            val start = polygon[index]
            val end = polygon[(index + 1) % polygon.size]
            rectangleEdges.any { (rectStart, rectEnd) -> segmentsIntersect(start, end, rectStart, rectEnd) }
        }
    }

    fun squaredDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x1 - x2
        val deltaY = y1 - y2
        return deltaX * deltaX + deltaY * deltaY
    }

    fun nearestPointIndexWithinRadius(
        x: Float,
        y: Float,
        points: List<DocumentPoint>,
        radius: Float,
    ): Int {
        val radiusSquared = radius * radius
        return points
            .mapIndexed { index, point -> index to squaredDistance(point.x, point.y, x, y) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= radiusSquared }
            ?.first
            ?: -1
    }

    private fun segmentsIntersect(
        firstStart: DocumentPoint,
        firstEnd: DocumentPoint,
        secondStart: DocumentPoint,
        secondEnd: DocumentPoint,
    ): Boolean {
        val firstA = orientation(firstStart, firstEnd, secondStart)
        val firstB = orientation(firstStart, firstEnd, secondEnd)
        val secondA = orientation(secondStart, secondEnd, firstStart)
        val secondB = orientation(secondStart, secondEnd, firstEnd)
        if (firstA * firstB < 0f && secondA * secondB < 0f) return true
        return (abs(firstA) < 0.0001f && onSegment(firstStart, firstEnd, secondStart)) ||
            (abs(firstB) < 0.0001f && onSegment(firstStart, firstEnd, secondEnd)) ||
            (abs(secondA) < 0.0001f && onSegment(secondStart, secondEnd, firstStart)) ||
            (abs(secondB) < 0.0001f && onSegment(secondStart, secondEnd, firstEnd))
    }

    private fun orientation(start: DocumentPoint, end: DocumentPoint, point: DocumentPoint): Float =
        (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

    private fun onSegment(start: DocumentPoint, end: DocumentPoint, point: DocumentPoint): Boolean =
        point.x in minOf(start.x, end.x)..maxOf(start.x, end.x) &&
            point.y in minOf(start.y, end.y)..maxOf(start.y, end.y)
}
