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
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.floor
import kotlin.math.sqrt

enum class CanvasBackgroundFormat(val label: String) {
    GRID("默认网格"),
    RULE_LINES("标尺横线"),
    DENSE_GRID("密集网格"),
    BLANK("空白页面"),
}

class DryInkView(context: Context) : View(context) {
    var backgroundFormat: CanvasBackgroundFormat = CanvasBackgroundFormat.GRID
        set(value) {
            field = value
            invalidate()
        }

    private val strokes = mutableListOf<Stroke>()
    private val selectedStrokes = mutableSetOf<Stroke>()
    private val lassoPoints = mutableListOf<DocumentPoint>()
    private val thinGridPaint = Paint().apply { color = Color.rgb(31, 48, 58) }
    private val majorGridPaint = Paint().apply { color = Color.rgb(42, 66, 79) }
    private val marginLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(160, 50, 50)
            strokeWidth = 1.2f * resources.displayMetrics.density
        }
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
        lassoPoints += DocumentPoint(point.x, point.y)
        invalidate()
    }

    fun extendLasso(point: PointF) {
        val previous = lassoPoints.lastOrNull()
        if (previous == null || SelectionGeometry.squaredDistance(previous.x, previous.y, point.x, point.y) >= 4f) {
            lassoPoints += DocumentPoint(point.x, point.y)
            invalidate()
        }
    }

    fun finishLasso(): List<DocumentPoint> {
        val polygon = lassoPoints.toList()
        selectedStrokes.clear()
        if (polygon.size >= 3) {
            for (stroke in strokes) {
                val inputs = stroke.inputs
                if ((0 until inputs.size).any { index ->
                        val input = inputs.get(index)
                        SelectionGeometry.pointInPolygon(input.x, input.y, polygon)
                    }
                ) {
                    selectedStrokes += stroke
                }
            }
        }
        lassoPoints.clear()
        invalidate()
        return polygon
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

    fun selectedBounds(): RectF? {
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

    fun translateSelection(deltaX: Float, deltaY: Float) {
        transformSelection(pointTransform = { x, y -> SelectionGeometry.translate(x, y, deltaX, deltaY) })
    }

    fun scaleSelection(anchorX: Float, anchorY: Float, factor: Float) {
        transformSelection(
            pointTransform = { x, y -> SelectionGeometry.scale(x, y, anchorX, anchorY, factor) },
            brushScale = factor,
        )
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

    private fun drawGrid(canvas: Canvas) {
        if (backgroundFormat == CanvasBackgroundFormat.BLANK) return
        val visible = visibleDocumentBounds()
        val step = if (backgroundFormat == CanvasBackgroundFormat.DENSE_GRID) gridStep * 0.5f else gridStep

        if (backgroundFormat != CanvasBackgroundFormat.RULE_LINES) {
            var x = floor(visible.left / step) * step
            while (x <= visible.right) {
                val index = floor(x / step).toInt()
                canvas.drawLine(x, visible.top, x, visible.bottom, if (index % 4 == 0) majorGridPaint else thinGridPaint)
                x += step
            }
        } else {
            val marginX = 64f * resources.displayMetrics.density
            if (visible.left <= marginX && visible.right >= marginX) {
                canvas.drawLine(marginX, visible.top, marginX, visible.bottom, marginLinePaint)
            }
        }

        var y = floor(visible.top / step) * step
        while (y <= visible.bottom) {
            val index = floor(y / step).toInt()
            val paint = if (index % 4 == 0) majorGridPaint else thinGridPaint
            canvas.drawLine(visible.left, y, visible.right, y, paint)
            y += step
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
        if (inputs.size == 1) return SelectionGeometry.squaredDistance(first.x, first.y, x, y) <= radius * radius
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
        if (lengthSquared == 0f) return sqrt(SelectionGeometry.squaredDistance(pointX, pointY, startX, startY))
        val projection =
            (((pointX - startX) * deltaX + (pointY - startY) * deltaY) / lengthSquared)
                .coerceIn(0f, 1f)
        val nearestX = startX + projection * deltaX
        val nearestY = startY + projection * deltaY
        return sqrt(SelectionGeometry.squaredDistance(pointX, pointY, nearestX, nearestY))
    }

    private fun transformSelection(
        pointTransform: (Float, Float) -> DocumentPoint,
        brushScale: Float = 1f,
    ) {
        if (selectedStrokes.isEmpty()) return
        val replacements = LinkedHashMap<Stroke, Stroke>()
        for (stroke in selectedStrokes) {
            val transformedInputs = MutableStrokeInputBatch()
            val inputs = stroke.inputs
            for (index in 0 until inputs.size) {
                val input = inputs.get(index)
                val point = pointTransform(input.x, input.y)
                transformedInputs.add(
                    input.toolType,
                    point.x,
                    point.y,
                    input.elapsedTimeMillis,
                    input.strokeUnitLengthCm,
                    input.pressure,
                    input.tiltRadians,
                    input.orientationRadians,
                )
            }
            val brush =
                if (brushScale == 1f) stroke.brush
                else stroke.brush.copy(size = (stroke.brush.size * brushScale).coerceAtLeast(0.01f))
            replacements[stroke] = Stroke(brush, transformedInputs)
        }
        for (index in strokes.indices) {
            replacements[strokes[index]]?.let { strokes[index] = it }
        }
        selectedStrokes.clear()
        selectedStrokes.addAll(replacements.values)
        invalidate()
    }
}
