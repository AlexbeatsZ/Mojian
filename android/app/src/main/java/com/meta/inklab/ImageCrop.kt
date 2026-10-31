package com.meta.inklab

/** Normalized source-pixel bounds, before clockwise rotation and display-axis flips. */
data class ImageCrop(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 1.0, val bottom: Double = 1.0) {
    val width get() = right - left
    val height get() = bottom - top

    fun normalized(): ImageCrop {
        if (!listOf(left, top, right, bottom).all { it.isFinite() }) return ImageCrop()
        val l = left.coerceIn(0.0, 1.0); val t = top.coerceIn(0.0, 1.0)
        val r = right.coerceIn(0.0, 1.0); val b = bottom.coerceIn(0.0, 1.0)
        return if (r - l < 0.000001 || b - t < 0.000001) ImageCrop() else ImageCrop(l, t, r, b)
    }

    fun transformed(image: InsertObject.Image, inverse: Boolean = false): ImageCrop {
        fun map(px: Double, py: Double): Pair<Double, Double> {
            var x = px; var y = py
            var turns = image.rotationQuarterTurns.mod(4)
            if (inverse) {
                if (image.flipHorizontal) x = 1 - x
                if (image.flipVertical) y = 1 - y
                turns = (4 - turns).mod(4)
            }
            repeat(turns) { val oldX = x; x = 1 - y; y = oldX }
            if (!inverse) {
                if (image.flipHorizontal) x = 1 - x
                if (image.flipVertical) y = 1 - y
            }
            return x to y
        }
        val points = listOf(map(left, top), map(right, top), map(left, bottom), map(right, bottom))
        return ImageCrop(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second }).normalized()
    }

    companion object {
        fun hitHandle(point: DocumentPoint, bounds: DocumentRect, radius: Float): Int {
            val nearest = SelectionGeometry.selectionHandles(bounds).withIndex().minBy { kotlin.math.hypot(it.value.x - point.x, it.value.y - point.y) }
            if (kotlin.math.hypot(nearest.value.x - point.x, nearest.value.y - point.y) <= radius) return nearest.index
            if (point.x < bounds.left - radius || point.x > bounds.right + radius || point.y < bounds.top - radius || point.y > bounds.bottom + radius) return -1
            val edge = listOf(kotlin.math.abs(point.y - bounds.top) to 1, kotlin.math.abs(point.x - bounds.right) to 3,
                kotlin.math.abs(point.y - bounds.bottom) to 5, kotlin.math.abs(point.x - bounds.left) to 7).minBy { it.first }
            return if (edge.first <= radius) edge.second else -1
        }

        fun resizeBounds(start: DocumentRect, handle: Int, delta: DocumentPoint, minimum: Float): DocumentRect {
            var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
            val minX = minOf(start.right - start.left, minimum); val minY = minOf(start.bottom - start.top, minimum)
            if (handle in listOf(0, 6, 7)) l = (l + delta.x).coerceIn(start.left, r - minX)
            if (handle in listOf(2, 3, 4)) r = (r + delta.x).coerceIn(l + minX, start.right)
            if (handle in listOf(0, 1, 2)) t = (t + delta.y).coerceIn(start.top, b - minY)
            if (handle in listOf(4, 5, 6)) b = (b + delta.y).coerceIn(t + minY, start.bottom)
            return DocumentRect(l, t, r, b)
        }

        fun fromDocumentBounds(image: InsertObject.Image, bounds: DocumentRect): ImageCrop {
            val current = display(image)
            return ImageCrop(current.left + (bounds.left - image.documentX) / image.width * current.width,
                current.top + (bounds.top - image.documentY) / image.height * current.height,
                current.left + (bounds.right - image.documentX) / image.width * current.width,
                current.top + (bounds.bottom - image.documentY) / image.height * current.height)
        }

        fun source(image: InsertObject.Image) = ImageCrop(image.cropLeft, image.cropTop, image.cropRight, image.cropBottom).normalized()
        fun display(image: InsertObject.Image) = source(image).transformed(image)

        fun apply(image: InsertObject.Image, selected: ImageCrop): Boolean {
            val next = selected.normalized()
            val previous = display(image)
            if (kotlin.math.abs(previous.left - next.left) + kotlin.math.abs(previous.top - next.top) +
                kotlin.math.abs(previous.width - next.width) + kotlin.math.abs(previous.height - next.height) < 0.000001) return false
            val fullWidth = image.width / previous.width; val fullHeight = image.height / previous.height
            image.documentX += ((next.left - previous.left) * fullWidth).toFloat()
            image.documentY += ((next.top - previous.top) * fullHeight).toFloat()
            image.width = (fullWidth * next.width).toFloat(); image.height = (fullHeight * next.height).toFloat()
            val source = next.transformed(image, inverse = true)
            image.cropLeft = source.left; image.cropTop = source.top
            image.cropRight = source.right; image.cropBottom = source.bottom
            return true
        }
    }
}
