package com.meta.inklab

import kotlin.math.floor

/** Paper stays anchored at (0, 0); document content alone grows its right/bottom edges. */
object BoundlessPageLayout {
    val initialWidth = 210f * DOCUMENT_UNITS_PER_MM
    val initialHeight = 297f * DOCUMENT_UNITS_PER_MM
    val autoGrowMargin = 24f * DOCUMENT_UNITS_PER_MM
    val growthStep = 100f * DOCUMENT_UNITS_PER_MM
    val exportPadding = 6f * DOCUMENT_UNITS_PER_MM

    fun requiredExtent(currentExtent: Float, contentEdge: Float, initialExtent: Float): Float {
        val extent = if (currentExtent.isFinite()) maxOf(currentExtent, initialExtent) else initialExtent
        if (!contentEdge.isFinite() || contentEdge < extent - autoGrowMargin) return extent
        return extent + (floor((contentEdge + autoGrowMargin - extent) / growthStep) + 1f) * growthStep
    }

    fun ensureCapacity(settings: PageViewSettings, content: Sequence<StrokeViewportBounds>): Boolean {
        if (settings.paperSize != PaperSize.BOUNDLESS) return false
        var right = 0f
        var bottom = 0f
        content.filter { it.right >= 0f && it.bottom >= 0f }.forEach {
            right = maxOf(right, it.right)
            bottom = maxOf(bottom, it.bottom)
        }
        val width = requiredExtent(settings.boundlessDocumentWidth, right, initialWidth)
        val height = requiredExtent(settings.boundlessDocumentHeight, bottom, initialHeight)
        if (width == settings.boundlessDocumentWidth && height == settings.boundlessDocumentHeight) return false
        settings.boundlessDocumentWidth = width
        settings.boundlessDocumentHeight = height
        return true
    }

    fun exportBounds(content: Sequence<StrokeViewportBounds>): StrokeViewportBounds {
        val bounds = content.reduceOrNull { a, b ->
            StrokeViewportBounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        } ?: return StrokeViewportBounds(0f, 0f, initialWidth, initialHeight)
        return StrokeViewportBounds(bounds.left - exportPadding, bounds.top - exportPadding, bounds.right + exportPadding, bounds.bottom + exportPadding)
    }
}
