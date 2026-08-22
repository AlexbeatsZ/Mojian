package com.meta.inklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import kotlin.math.floor
import kotlin.math.sqrt

class DryInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    private val thinGridPaint = Paint().apply { color = Color.rgb(225, 232, 242) }
    private val majorGridPaint = Paint().apply { color = Color.rgb(207, 219, 235) }
    private val strokeRenderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private val gridStep = 28f * resources.displayMetrics.density
    private val documentToView = Matrix()
    private val eraserCursorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 96, 114)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
    private var eraserCursor: PointF? = null
    private var eraserRadius = 0f

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

    fun setViewport(matrix: Matrix) {
        documentToView.set(matrix)
        invalidate()
    }

    fun setEraserCursor(point: PointF?, radius: Float = 0f) {
        eraserCursor = point
        eraserRadius = radius
        invalidate()
    }

    /** First implementation deliberately erases complete strokes touched by the pressure eraser. */
    fun eraseAtDocument(x: Float, y: Float, radius: Float): Int {
        var removed = 0
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f)) {
                iterator.remove()
                removed++
            }
        }
        if (removed > 0) invalidate()
        return removed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(documentToView)
        drawGrid(canvas)
        strokeRenderer.drawWithStrokes(canvas) { scope ->
            for (stroke in strokes) scope.drawStroke(stroke)
        }
        eraserCursor?.let { cursor ->
            canvas.drawCircle(cursor.x, cursor.y, eraserRadius, eraserCursorPaint)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val visible = visibleDocumentBounds()
        var x = floor(visible.left / gridStep) * gridStep
        while (x <= visible.right) {
            val index = floor(x / gridStep).toInt()
            canvas.drawLine(x, visible.top, x, visible.bottom, if (index % 4 == 0) majorGridPaint else thinGridPaint)
            x += gridStep
        }

        var y = floor(visible.top / gridStep) * gridStep
        while (y <= visible.bottom) {
            val index = floor(y / gridStep).toInt()
            canvas.drawLine(visible.left, y, visible.right, y, if (index % 4 == 0) majorGridPaint else thinGridPaint)
            y += gridStep
        }
    }

    private fun visibleDocumentBounds(): RectF {
        val inverse = Matrix()
        if (!documentToView.invert(inverse)) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        return RectF(0f, 0f, width.toFloat(), height.toFloat()).also(inverse::mapRect)
    }

    private fun strokeTouchesCircle(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val inputs = stroke.inputs
        if (inputs.isEmpty()) return false
        val first = inputs.get(0)
        if (inputs.size == 1) return squaredDistance(first.x, first.y, x, y) <= radius * radius
        var previousX = first.x
        var previousY = first.y
        for (index in 1 until inputs.size) {
            val current = inputs.get(index)
            if (distanceToSegment(x, y, previousX, previousY, current.x, current.y) <= radius) return true
            previousX = current.x
            previousY = current.y
        }
        return false
    }

    private fun distanceToSegment(
        pointX: Float,
        pointY: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): Float {
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0f) return sqrt(squaredDistance(pointX, pointY, startX, startY))
        val projection =
            (((pointX - startX) * deltaX + (pointY - startY) * deltaY) / lengthSquared)
                .coerceIn(0f, 1f)
        val nearestX = startX + projection * deltaX
        val nearestY = startY + projection * deltaY
        return sqrt(squaredDistance(pointX, pointY, nearestX, nearestY))
    }

    private fun squaredDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x1 - x2
        val deltaY = y1 - y2
        return deltaX * deltaX + deltaY * deltaY
    }
}
