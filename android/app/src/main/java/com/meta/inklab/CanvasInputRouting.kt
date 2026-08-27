package com.meta.inklab

enum class CanvasDragIntent {
    NONE,
    PAN,
    ZOOM,
}

enum class SecondaryReleaseIntent {
    OPEN_CONTEXT_MENU,
    COMMIT_LASSO,
}

enum class WheelIntent {
    NONE,
    PAN_HORIZONTAL,
    PAN_VERTICAL,
    ZOOM,
}

data class WheelDecision(
    val intent: WheelIntent,
    val amount: Float,
)

data class PanFocus(
    val x: Float,
    val y: Float,
)

object CanvasInputRouting {
    fun mouseSecondaryStartsLasso(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
    ): Boolean = deltaX * deltaX + deltaY * deltaY >= touchSlop * touchSlop

    fun stylusShortcutReleaseIntent(
        heldMillis: Long,
        holdMillis: Long,
    ): SecondaryReleaseIntent =
        if (heldMillis >= holdMillis) SecondaryReleaseIntent.COMMIT_LASSO
        else SecondaryReleaseIntent.OPEN_CONTEXT_MENU

    fun scaledPanFocus(
        previousX: Float,
        previousY: Float,
        focusX: Float,
        focusY: Float,
        sensitivity: Float,
    ): PanFocus {
        val factor = sensitivity.coerceIn(0.25f, 3f)
        return PanFocus(
            previousX + (focusX - previousX) * factor,
            previousY + (focusY - previousY) * factor,
        )
    }

    fun mouseDragIntent(
        middlePressed: Boolean,
        ctrlPressed: Boolean,
    ): CanvasDragIntent =
        when {
            middlePressed ->
                if (ctrlPressed) CanvasDragIntent.ZOOM else CanvasDragIntent.PAN
            else -> CanvasDragIntent.NONE
        }

    fun wheelDecision(
        vertical: Float,
        horizontal: Float,
        shiftPressed: Boolean,
        ctrlPressed: Boolean,
    ): WheelDecision =
        when {
            ctrlPressed && vertical != 0f -> WheelDecision(WheelIntent.ZOOM, vertical)
            shiftPressed && (vertical != 0f || horizontal != 0f) ->
                WheelDecision(WheelIntent.PAN_HORIZONTAL, if (horizontal != 0f) horizontal else vertical)
            horizontal != 0f -> WheelDecision(WheelIntent.PAN_HORIZONTAL, horizontal)
            vertical != 0f -> WheelDecision(WheelIntent.PAN_VERTICAL, vertical)
            else -> WheelDecision(WheelIntent.NONE, 0f)
        }
}
