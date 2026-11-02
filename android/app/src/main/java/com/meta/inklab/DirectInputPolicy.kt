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

/** Reading view owns the single-pointer stream before content layers can select or edit. */
enum class ReadingViewPointerIntent {
    PAN,
    PASS_THROUGH,
    BLOCK,
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
        device == DirectInputDevice.FINGER || device == DirectInputDevice.PEN || device == DirectInputDevice.MOUSE

    fun showsInsertSpaceHover(device: DirectInputDevice): Boolean =
        device == DirectInputDevice.MOUSE || device == DirectInputDevice.PEN

    /**
     * Finger, pen and primary-mouse drags pan the page; middle-button navigation keeps its
     * own pan/zoom path; right-button menus and lassos are suppressed because they select.
     */
    fun readingViewPointerIntent(
        device: DirectInputDevice,
        primaryButton: Boolean,
        middleButton: Boolean,
        secondaryButton: Boolean,
    ): ReadingViewPointerIntent =
        when {
            device != DirectInputDevice.MOUSE -> ReadingViewPointerIntent.PAN
            primaryButton -> ReadingViewPointerIntent.PAN
            middleButton -> ReadingViewPointerIntent.PASS_THROUGH
            secondaryButton -> ReadingViewPointerIntent.BLOCK
            else -> ReadingViewPointerIntent.PAN
        }

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
