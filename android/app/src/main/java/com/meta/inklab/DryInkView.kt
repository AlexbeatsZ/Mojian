package com.meta.inklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke

class DryInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    private val thinGridPaint = Paint().apply { color = Color.rgb(225, 232, 242) }
    private val majorGridPaint = Paint().apply { color = Color.rgb(207, 219, 235) }
    private val strokeRenderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private val gridStep = 28f * resources.displayMetrics.density

    init {
        setBackgroundColor(Color.rgb(247, 249, 252))
    }

    fun addStrokes(newStrokes: Collection<Stroke>) {
        strokes.addAll(newStrokes)
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    fun clear() {
        strokes.clear()
        invalidate()
    }

    fun strokeCount(): Int = strokes.size

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        strokeRenderer.drawWithStrokes(canvas) { scope ->
            for (stroke in strokes) scope.drawStroke(stroke)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        var index = 0
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), if (index % 4 == 0) majorGridPaint else thinGridPaint)
            x += gridStep
            index++
        }

        index = 0
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, if (index % 4 == 0) majorGridPaint else thinGridPaint)
            y += gridStep
            index++
        }
    }
}
