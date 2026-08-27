package com.meta.inklab

/** Device-independent direct-manipulation policy for finger and primary-mouse input. */
enum class DirectPointerDownIntent {
    IGNORE,
    NATIVE_TEXT_EDIT,
    TRANSFORM_SELECTION,
    MARQUEE_CANDIDATE,
    PAGE_NAVIGATION,
}

enum class DirectInputDevice {
    FINGER,
    MOUSE,
    PEN,
}

object DirectInputPolicy {
    fun canDragInsertSpace(device: DirectInputDevice): Boolean =
        device == DirectInputDevice.FINGER || device == DirectInputDevice.PEN

    fun showsInsertSpaceHover(device: DirectInputDevice): Boolean =
        device == DirectInputDevice.MOUSE || device == DirectInputDevice.PEN

    fun pointerDownIntent(
        pointerCount: Int,
        primaryDirectPointer: Boolean,
        editingTextHit: Boolean,
        selectedContentHit: Boolean,
        selectionHandleHit: Boolean,
    ): DirectPointerDownIntent =
        when {
            pointerCount >= 2 -> DirectPointerDownIntent.PAGE_NAVIGATION
            !primaryDirectPointer -> DirectPointerDownIntent.IGNORE
            editingTextHit -> DirectPointerDownIntent.NATIVE_TEXT_EDIT
            selectedContentHit || selectionHandleHit -> DirectPointerDownIntent.TRANSFORM_SELECTION
            else -> DirectPointerDownIntent.MARQUEE_CANDIDATE
        }
}
