package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Display-only canvas status label. It never consumes touch, stylus hover or generic motion,
 * so an accidental contact falls through to the canvas below and cannot open configuration.
 * Do not add click listeners or tooltips: a tooltip alone makes Android views consume touches.
 */
internal class PassiveStatusView(context: Context) : TextView(context) {
    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isHapticFeedbackEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = false

    override fun onHoverEvent(event: MotionEvent): Boolean = false

    override fun setOnClickListener(listener: OnClickListener?) {
        require(listener == null) { "PassiveStatusView is display-only" }
        super.setOnClickListener(null)
        isClickable = false
    }
}
