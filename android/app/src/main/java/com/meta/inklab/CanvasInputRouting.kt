package com.meta.inklab

enum class CanvasDragIntent {
    NONE,
    PAN,
    ZOOM,
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

object CanvasInputRouting {
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
