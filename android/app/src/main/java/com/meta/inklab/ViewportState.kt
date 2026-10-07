package com.meta.inklab

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

data class ViewportSnapshot(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Document-to-view transform shared by dry ink, wet ink authoring, gestures, and hit testing.
 * Keeping this as explicit scale/translation values avoids accumulating Matrix rounding error.
 */
class ViewportState(
    val minScale: Float = DEFAULT_MIN_SCALE,
    val maxScale: Float = DEFAULT_MAX_SCALE,
) {
    companion object {
        const val DEFAULT_MIN_SCALE = 0.5f
        const val DEFAULT_MAX_SCALE = 64f
        val ZOOM_PRESETS = listOf(50, 75, 100, 125, 150, 200, 400, 800, 1600, 3200, 6400)
    }

    var scale: Float = 1f
        private set
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    fun applyGesture(
        previousFocusX: Float,
        previousFocusY: Float,
        focusX: Float,
        focusY: Float,
        requestedScaleFactor: Float,
    ) {
        val anchorX = (previousFocusX - translationX) / scale
        val anchorY = (previousFocusY - translationY) / scale
        val newScale = min(max(scale * requestedScaleFactor, minScale), maxScale)
        scale = newScale
        translationX = focusX - anchorX * newScale
        translationY = focusY - anchorY * newScale
    }

    fun viewToDocument(viewX: Float, viewY: Float): PointF =
        PointF(
            (viewX - translationX) / scale,
            (viewY - translationY) / scale,
        )

    fun centerDocumentPoint(documentX: Float, documentY: Float, viewCenterX: Float, viewCenterY: Float) {
        translationX = viewCenterX - documentX * scale
        translationY = viewCenterY - documentY * scale
    }

    fun setScaleAround(
        requestedScale: Float,
        anchorViewX: Float,
        anchorViewY: Float,
    ) {
        val factor = requestedScale / scale
        applyGesture(anchorViewX, anchorViewY, anchorViewX, anchorViewY, factor)
    }

    fun fitDocumentWidth(
        documentLeft: Float,
        documentWidth: Float,
        viewWidth: Float,
        horizontalPadding: Float,
    ) {
        if (documentWidth <= 0f || viewWidth <= 0f) return
        val availableWidth = (viewWidth - horizontalPadding * 2f).coerceAtLeast(1f)
        scale = (availableWidth / documentWidth).coerceIn(minScale, maxScale)
        translationX = horizontalPadding - documentLeft * scale
    }

    fun snapshot(): ViewportSnapshot = ViewportSnapshot(scale, translationX, translationY)

    fun restore(snapshot: ViewportSnapshot) {
        scale = snapshot.scale.coerceIn(minScale, maxScale)
        translationX = snapshot.translationX
        translationY = snapshot.translationY
    }

    fun reset() {
        scale = 1f.coerceIn(minScale, maxScale)
        translationX = 0f
        translationY = 0f
    }

    fun documentToViewMatrix(): Matrix =
        Matrix().apply {
            setValues(
                floatArrayOf(
                    scale,
                    0f,
                    translationX,
                    0f,
                    scale,
                    translationY,
                    0f,
                    0f,
                    1f,
                ),
            )
        }

    fun viewToDocumentMatrix(): Matrix =
        Matrix().also { inverse ->
            check(documentToViewMatrix().invert(inverse)) { "Viewport transform must be invertible" }
        }
}
