package com.meta.inklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import kotlin.math.floor
import kotlin.math.sqrt

class DryInkView(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    private val selectedStrokes = mutableSetOf<Stroke>()
    private val lassoPoints = mutableListOf<PointF>()
    private val thinGridPaint = Paint().apply { color = Color.rgb(31, 48, 58) }
    private val majorGridPaint = Paint().apply { color = Color.rgb(42, 66, 79) }
    private val strokeRenderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private val gridStep = 28f * resources.displayMetrics.density
    private val documentToView = Matrix()
    private val eraserCursorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 190, 190)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
    private var eraserCursor: PointF? = null
    private var eraserRadius = 0f
    private val lassoPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 178, 233)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
    private val selectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 178, 233)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
    private val selectionHandlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f, Color.argb(90, 0, 0, 0))
        }

    init {
        setBackgroundColor(Color.rgb(27, 27, 27))
    }

    fun addStrokes(newStrokes: Collection<Stroke>) {
        selectedStrokes.clear()
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
        selectedStrokes.clear()
        lassoPoints.clear()
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

    fun beginLasso(point: PointF) {
        selectedStrokes.clear()
        lassoPoints.clear()
        lassoPoints += PointF(point.x, point.y)
        invalidate()
    }

    fun extendLasso(point: PointF) {
        val previous = lassoPoints.lastOrNull()
        if (previous == null || squaredDistance(previous.x, previous.y, point.x, point.y) >= 4f) {
            lassoPoints += PointF(point.x, point.y)
            invalidate()
        }
    }

    fun finishLasso(): Int {
        selectedStrokes.clear()
        if (lassoPoints.size >= 3) {
            for (stroke in strokes) {
                val inputs = stroke.inputs
                if ((0 until inputs.size).any { index ->
                        val input = inputs.get(index)
                        pointInPolygon(input.x, input.y, lassoPoints)
                    }
                ) {
                    selectedStrokes += stroke
                }
            }
        }
        lassoPoints.clear()
        invalidate()
        return selectedStrokes.size
    }

    fun selectedCount(): Int = selectedStrokes.size

    fun clearSelection() {
        selectedStrokes.clear()
        lassoPoints.clear()
        invalidate()
    }

    fun deleteSelection(): Int {
        val count = selectedStrokes.size
        if (count == 0) return 0
        strokes.removeAll(selectedStrokes)
        selectedStrokes.clear()
        invalidate()
        return count
    }

    /** First implementation deliberately erases complete strokes touched by the pressure eraser. */
    fun eraseAtDocument(x: Float, y: Float, radius: Float): Int {
        var removed = 0
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f)) {
                iterator.remove()
                selectedStrokes.remove(stroke)
                removed++
            }
        }
        if (removed > 0) invalidate()
        return removed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokeRenderer.drawWithStrokes(canvas) { transformedCanvas, scope ->
            transformedCanvas.save()
            // Apply the viewport after ViewStrokeRenderer captures its baseline canvas matrix.
            // Otherwise its screen-space antialiasing misses the zoom and the edge feathering is
            // enlarged together with the stroke.
            transformedCanvas.concat(documentToView)
            drawGrid(transformedCanvas)
            for (stroke in strokes) scope.drawStroke(stroke)
            drawLasso(transformedCanvas)
            drawSelection(transformedCanvas)
            eraserCursor?.let { cursor ->
                transformedCanvas.drawCircle(cursor.x, cursor.y, eraserRadius, eraserCursorPaint)
            }
            transformedCanvas.restore()
        }
    }

    private fun drawLasso(canvas: Canvas) {
        if (lassoPoints.isEmpty()) return
        val path = Path().apply {
            moveTo(lassoPoints.first().x, lassoPoints.first().y)
            for (index in 1 until lassoPoints.size) lineTo(lassoPoints[index].x, lassoPoints[index].y)
        }
        canvas.drawPath(path, lassoPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        val bounds = selectionBounds() ?: return
        val padding = 8f * resources.displayMetrics.density / currentViewportScale()
        bounds.inset(-padding, -padding)
        canvas.drawRect(bounds, selectionPaint)
        val handleRadius = 4f * resources.displayMetrics.density / currentViewportScale()
        val corners =
            listOf(
                PointF(bounds.left, bounds.top),
                PointF(bounds.right, bounds.top),
                PointF(bounds.right, bounds.bottom),
                PointF(bounds.left, bounds.bottom),
            )
        for (corner in corners) {
            canvas.drawCircle(corner.x, corner.y, handleRadius, selectionHandlePaint)
            canvas.drawCircle(corner.x, corner.y, handleRadius, selectionPaint)
        }
    }

    private fun selectionBounds(): RectF? {
        if (selectedStrokes.isEmpty()) return null
        var bounds: RectF? = null
        for (stroke in selectedStrokes) {
            val inputs = stroke.inputs
            for (index in 0 until inputs.size) {
                val input = inputs.get(index)
                val current = bounds
                if (current == null) bounds = RectF(input.x, input.y, input.x, input.y)
                else current.union(input.x, input.y)
            }
        }
        return bounds
    }

    private fun currentViewportScale(): Float {
        val values = FloatArray(9)
        documentToView.getValues(values)
        return values[Matrix.MSCALE_X].coerceAtLeast(0.01f)
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

    private fun pointInPolygon(x: Float, y: Float, polygon: List<PointF>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses =
                ((current.y > y) != (previous.y > y)) &&
                    (x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x)
            if (crosses) inside = !inside
            previous = current
        }
        return inside
    }
}
