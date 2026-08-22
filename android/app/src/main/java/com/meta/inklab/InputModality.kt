package com.meta.inklab

object InputModality {
    fun acceptsTextInput(pointerToolTypes: List<Int>, fingerToolType: Int): Boolean =
        pointerToolTypes.isNotEmpty() && pointerToolTypes.all { it == fingerToolType }
}
