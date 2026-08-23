package com.meta.inklab

data class DocumentPoint(val x: Float, val y: Float)

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
}
