package com.meta.inklab

import android.view.KeyEvent

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
    fun isSelectionDeleteKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        hasModifiers: Boolean,
    ): Boolean =
        action == KeyEvent.ACTION_DOWN &&
            repeatCount == 0 &&
            !hasModifiers &&
            (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)

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
        selectionFrameHit: Boolean,
    ): DirectPointerDownIntent =
        when {
            pointerCount >= 2 -> DirectPointerDownIntent.PAGE_NAVIGATION
            !primaryDirectPointer -> DirectPointerDownIntent.IGNORE
            editingTextHit -> DirectPointerDownIntent.NATIVE_TEXT_EDIT
            selectedContentHit || selectionHandleHit || selectionFrameHit -> DirectPointerDownIntent.TRANSFORM_SELECTION
            else -> DirectPointerDownIntent.MARQUEE_CANDIDATE
        }
}
