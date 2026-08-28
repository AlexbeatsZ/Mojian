package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View

/** Draws page rules, grids, and dots beneath inserted content while ink remains above it. */
@SuppressLint("ViewConstructor")
class CanvasBackgroundLayer(
    context: Context,
    private val inkView: DryInkView,
) : View(context) {
    private var pageSettings = PageViewSettings()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        inkView.delegateBackgroundRendering(::invalidate)
    }

    fun updatePage(settings: PageViewSettings) {
        pageSettings = settings
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (pageSettings.paperSize) {
            PaperSize.BOUNDLESS -> inkView.renderBackground(canvas)
            PaperSize.A4 -> {
                val pages =
                    List(pageSettings.a4PageCount.coerceAtLeast(1)) { index ->
                        RectF(0f, A4PageLayout.pageTop(index), A4PageLayout.pageWidth, A4PageLayout.pageBottom(index))
                    }
                inkView.renderBackground(canvas, pages)
            }
            PaperSize.PDF -> {
                val width = pageSettings.documentWidth?.coerceAtLeast(1f) ?: A4PageLayout.pageWidth
                val height = pageSettings.documentHeight?.coerceAtLeast(1f) ?: A4PageLayout.pageHeight
                inkView.renderBackground(canvas, listOf(RectF(0f, 0f, width, height)))
            }
        }
    }
}
