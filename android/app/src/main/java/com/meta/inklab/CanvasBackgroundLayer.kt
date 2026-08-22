package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

/** Draws page rules, grids, and dots beneath inserted content while ink remains above it. */
@SuppressLint("ViewConstructor")
class CanvasBackgroundLayer(
    context: Context,
    private val inkView: DryInkView,
) : View(context) {
    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        inkView.delegateBackgroundRendering(::invalidate)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        inkView.renderBackground(canvas)
    }
}
