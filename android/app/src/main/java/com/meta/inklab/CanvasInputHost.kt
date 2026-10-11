package com.meta.inklab

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/** Observes complete pointer streams before a canvas child claims the gesture. */
class CanvasInputHost(context: Context) : FrameLayout(context) {
    var onPointerEventObserved: ((MotionEvent) -> Boolean)? = null
    private val childStream = ChildStreamHandoff()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val masked = event.actionMasked
        val claimed = onPointerEventObserved?.invoke(event) == true
        val cancelChildren =
            childStream.observe(
                startsSequence = masked == MotionEvent.ACTION_DOWN,
                endsSequence = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL,
                claimedByHost = claimed,
            )
        if (cancelChildren) {
            // A child that received DOWN must see the takeover, or its pending long-press
            // menu fires after a two-finger tap/pan has already ended.
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (claimed) return true
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

/** Decides when the host takes a touch sequence away from children that already own it. */
class ChildStreamHandoff {
    private var childrenOwnSequence = false

    /** Returns true when children must receive ACTION_CANCEL before the host keeps the event. */
    fun observe(startsSequence: Boolean, endsSequence: Boolean, claimedByHost: Boolean): Boolean {
        if (startsSequence) childrenOwnSequence = false
        if (claimedByHost) {
            val cancelChildren = childrenOwnSequence
            childrenOwnSequence = false
            return cancelChildren
        }
        childrenOwnSequence = !endsSequence
        return false
    }
}
