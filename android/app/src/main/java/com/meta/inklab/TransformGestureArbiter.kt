package com.meta.inklab

import kotlin.math.abs
import kotlin.math.hypot

enum class TransformGestureMode {
    UNDECIDED,
    PAN,
    SCALE,
}

data class TransformGestureUpdate(
    val previousFocusX: Float,
    val previousFocusY: Float,
    val focusX: Float,
    val focusY: Float,
    val scaleFactor: Float,
    val mode: TransformGestureMode,
)

/**
 * Holds a two-finger gesture in an undecided state until movement clears a
 * platform-scaled slop, then locks that contact sequence to pan or scale.
 */
class TransformGestureArbiter(
    private val panSlop: Float,
    private val scaleSlop: Float,
) {
    init {
        require(panSlop > 0f)
        require(scaleSlop > 0f)
    }

    var mode: TransformGestureMode = TransformGestureMode.UNDECIDED
        private set

    private var active = false
    private var initialFocusX = 0f
    private var initialFocusY = 0f
    private var initialSpan = 1f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var previousSpan = 1f

    fun begin(focusX: Float, focusY: Float, span: Float) {
        active = true
        mode = TransformGestureMode.UNDECIDED
        initialFocusX = focusX
        initialFocusY = focusY
        initialSpan = span.coerceAtLeast(1f)
        previousFocusX = focusX
        previousFocusY = focusY
        previousSpan = initialSpan
    }

    fun update(
        focusX: Float,
        focusY: Float,
        span: Float,
        allowScale: Boolean,
    ): TransformGestureUpdate? {
        if (!active) {
            begin(focusX, focusY, span)
            return null
        }

        val safeSpan = span.coerceAtLeast(1f)
        if (mode == TransformGestureMode.UNDECIDED) {
            val panDistance = hypot(focusX - initialFocusX, focusY - initialFocusY)
            val spanDistance = abs(safeSpan - initialSpan)
            val panReady = panDistance >= panSlop
            val scaleReady = allowScale && spanDistance >= scaleSlop
            if (!panReady && !scaleReady) return null

            mode =
                if (scaleReady && (!panReady || spanDistance / scaleSlop > panDistance / panSlop)) {
                    TransformGestureMode.SCALE
                } else {
                    TransformGestureMode.PAN
                }
            // Reset the incremental baseline at commitment so crossing the
            // threshold never produces a visible jump.
            previousFocusX = focusX
            previousFocusY = focusY
            previousSpan = safeSpan
            return null
        }

        val update =
            TransformGestureUpdate(
                previousFocusX = previousFocusX,
                previousFocusY = previousFocusY,
                focusX = focusX,
                focusY = focusY,
                scaleFactor =
                    if (mode == TransformGestureMode.SCALE && allowScale) {
                        safeSpan / previousSpan
                    } else {
                        1f
                    },
                mode = mode,
            )
        previousFocusX = focusX
        previousFocusY = focusY
        previousSpan = safeSpan
        return update
    }

    fun cancel() {
        active = false
        mode = TransformGestureMode.UNDECIDED
    }
}
