package com.meta.inklab

object InputModality {
    fun acceptsTextInput(
        pointerToolTypes: List<Int>,
        fingerToolType: Int,
        mouseToolType: Int,
    ): Boolean =
        pointerToolTypes.isNotEmpty() &&
            pointerToolTypes.all { it == fingerToolType || it == mouseToolType }

    fun isFingerOnly(pointerToolTypes: List<Int>, fingerToolType: Int): Boolean =
        pointerToolTypes.isNotEmpty() && pointerToolTypes.all { it == fingerToolType }

    fun isMouseOnly(pointerToolTypes: List<Int>, mouseToolType: Int): Boolean =
        pointerToolTypes.isNotEmpty() && pointerToolTypes.all { it == mouseToolType }
}
