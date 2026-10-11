package com.meta.inklab

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/** Observes complete pointer streams before a canvas child claims the gesture. */
class CanvasInputHost(context: Context) : FrameLayout(context) {
    var onPointerEventObserved: ((MotionEvent) -> Boolean)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onPointerEventObserved?.invoke(event) == true) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (onPointerEventObserved?.invoke(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (onPointerEventObserved?.invoke(event) == true) return true
        return super.dispatchHoverEvent(event)
    }
}
