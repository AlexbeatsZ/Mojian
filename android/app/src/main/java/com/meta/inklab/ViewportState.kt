package com.meta.inklab

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * Document-to-view transform shared by dry ink, wet ink authoring, gestures, and hit testing.
 * Keeping this as explicit scale/translation values avoids accumulating Matrix rounding error.
 */
class ViewportState(
    val minScale: Float = 0.5f,
    val maxScale: Float = 8f,
) {
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
