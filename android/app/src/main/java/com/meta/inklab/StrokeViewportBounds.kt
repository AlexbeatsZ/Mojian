package com.meta.inklab

data class StrokeViewportBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun intersects(other: StrokeViewportBounds): Boolean =
        right >= other.left && left <= other.right && bottom >= other.top && top <= other.bottom

    fun translated(deltaY: Float): StrokeViewportBounds =
        copy(top = top + deltaY, bottom = bottom + deltaY)
}
