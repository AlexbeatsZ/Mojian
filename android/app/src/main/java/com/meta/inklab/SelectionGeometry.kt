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
        scale(x, y, anchorX, anchorY, factor, factor)

    fun scale(x: Float, y: Float, anchorX: Float, anchorY: Float, factorX: Float, factorY: Float): DocumentPoint =
        DocumentPoint(
            anchorX + (x - anchorX) * factorX,
            anchorY + (y - anchorY) * factorY,
        )

    fun selectionHandles(bounds: DocumentRect): List<DocumentPoint> = listOf(
        DocumentPoint(bounds.left, bounds.top),
        DocumentPoint((bounds.left + bounds.right) / 2, bounds.top),
        DocumentPoint(bounds.right, bounds.top),
        DocumentPoint(bounds.right, (bounds.top + bounds.bottom) / 2),
        DocumentPoint(bounds.right, bounds.bottom),
        DocumentPoint((bounds.left + bounds.right) / 2, bounds.bottom),
        DocumentPoint(bounds.left, bounds.bottom),
        DocumentPoint(bounds.left, (bounds.top + bounds.bottom) / 2),
    )

    fun hitSelectionHandle(point: DocumentPoint, contentBounds: DocumentRect, displayBounds: DocumentRect, radius: Float): Int {
        if (contentBounds.isEmpty) return -1
        val insetX = minOf(radius, (contentBounds.right - contentBounds.left) / 4)
        val insetY = minOf(radius, (contentBounds.bottom - contentBounds.top) / 4)
        val body = DocumentRect(contentBounds.left + insetX, contentBounds.top + insetY, contentBounds.right - insetX, contentBounds.bottom - insetY)
        if (body.contains(point.x, point.y)) return -1
        return nearestPointIndexWithinRadius(point.x, point.y, selectionHandles(displayBounds), radius)
    }

    fun resizeBounds(start: DocumentRect, handle: Int, point: DocumentPoint, minimumSize: Float): DocumentRect {
        if (start.isEmpty || handle !in 0..7) return start
        val width = start.right - start.left
        val height = start.bottom - start.top
        val minimum = minOf(minimumSize, width, height)
        if (handle % 2 == 0) {
            val anchor = selectionHandles(start)[(handle + 4) % 8]
            val corner = selectionHandles(start)[handle]
            val dx = corner.x - anchor.x
            val dy = corner.y - anchor.y
            val factor = maxOf(((point.x - anchor.x) * dx + (point.y - anchor.y) * dy) / (dx * dx + dy * dy), minimum / width, minimum / height)
            val x = anchor.x + dx * factor
            val y = anchor.y + dy * factor
            return DocumentRect(minOf(anchor.x, x), minOf(anchor.y, y), maxOf(anchor.x, x), maxOf(anchor.y, y))
        }
        return when (handle) {
            1 -> start.copy(top = minOf(point.y, start.bottom - minimum))
            3 -> start.copy(right = maxOf(point.x, start.left + minimum))
            5 -> start.copy(bottom = maxOf(point.y, start.top + minimum))
            else -> start.copy(left = minOf(point.x, start.right - minimum))
        }
    }

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

    /** True only when the complete rectangle is inside the selection polygon. */
    fun polygonContainsRect(polygon: List<DocumentPoint>, rect: DocumentRect): Boolean {
        if (polygon.size < 3 || rect.isEmpty) return false
        return listOf(
            DocumentPoint(rect.left, rect.top),
            DocumentPoint(rect.right, rect.top),
            DocumentPoint(rect.right, rect.bottom),
            DocumentPoint(rect.left, rect.bottom),
        ).all { pointInPolygonOrBoundary(it.x, it.y, polygon) }
    }

    fun rectContainsRect(container: DocumentRect, candidate: DocumentRect): Boolean =
        !container.isEmpty &&
            !candidate.isEmpty &&
            candidate.left >= container.left &&
            candidate.top >= container.top &&
            candidate.right <= container.right &&
            candidate.bottom <= container.bottom

    /** Legacy overlap helper retained for non-selection collision checks. */
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

    fun pointInPolygonOrBoundary(x: Float, y: Float, polygon: List<DocumentPoint>): Boolean {
        if (pointInPolygon(x, y, polygon)) return true
        val point = DocumentPoint(x, y)
        return polygon.indices.any { index ->
            abs(orientation(polygon[index], polygon[(index + 1) % polygon.size], point)) < 0.0001f &&
                onSegment(polygon[index], polygon[(index + 1) % polygon.size], point)
        }
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
