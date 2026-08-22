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
import android.util.TypedValue
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.floor
import kotlin.math.sqrt

enum class BackgroundCategory(val label: String) {
    BLANK("空白"),
    RULE_LINES("横线"),
    GRID("网格"),
    DOTS("点阵"),
}

enum class CanvasBackgroundFormat(
    val label: String,
    val category: BackgroundCategory,
    val stepMultiplier: Float = 1f,
    val showMarginLine: Boolean = false,
) {
    BLANK("空白 (无底纹)", BackgroundCategory.BLANK),
    RULE_STANDARD("标准横线 (带边线)", BackgroundCategory.RULE_LINES, 1f, showMarginLine = true),
    RULE_NARROW("窄行距横线", BackgroundCategory.RULE_LINES, 0.75f, showMarginLine = true),
    RULE_WIDE("宽行距横线", BackgroundCategory.RULE_LINES, 1.35f, showMarginLine = true),
    RULE_CLEAN("纯横线 (无边线)", BackgroundCategory.RULE_LINES, 1f, showMarginLine = false),
    GRID_STANDARD("标准方格", BackgroundCategory.GRID, 1f),
    GRID_DENSE("密集方格", BackgroundCategory.GRID, 0.5f),
    GRID_WIDE("宽距方格", BackgroundCategory.GRID, 1.5f),
    DOTS_STANDARD("标准点阵", BackgroundCategory.DOTS, 1f),
    DOTS_DENSE("密集点阵", BackgroundCategory.DOTS, 0.5f),
    DOTS_WIDE("宽距点阵", BackgroundCategory.DOTS, 1.5f),
}

class DryInkView(context: Context) : View(context) {
    var backgroundFormat: CanvasBackgroundFormat = CanvasBackgroundFormat.GRID_STANDARD
        set(value) {
            field = value
            invalidateBackground()
        }

    private var renderBackgroundInInkLayer = true
    private var backgroundInvalidator: (() -> Unit)? = null

    private val strokes = mutableListOf<Stroke>()
    private val selectedStrokes = mutableSetOf<Stroke>()
    private val lassoPoints = mutableListOf<DocumentPoint>()
    private val thinGridPaint = Paint().apply { color = Color.rgb(31, 48, 58) }
    private val majorGridPaint = Paint().apply { color = Color.rgb(42, 66, 79) }
    private val dotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 76, 92)
            style = Paint.Style.FILL
        }
    private val marginLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(160, 50, 50)
            strokeWidth = 1.2f * resources.displayMetrics.density
        }
    private val exportThinGridPaint = Paint().apply { color = Color.rgb(220, 229, 235) }
    private val exportMajorGridPaint = Paint().apply { color = Color.rgb(198, 215, 224) }
    private val exportDotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 207, 216)
            style = Paint.Style.FILL
        }
    private val exportMarginLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(213, 125, 125)
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

    private var insertSpaceHoverY: Float? = null
    private var insertSpaceStartY: Float? = null
    private var insertSpaceCurrentY: Float? = null

    private val insertSpaceLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 200, 200)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
    private val insertSpaceMovingLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 120, 215)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
    private val insertSpaceBandPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 0, 120, 215)
            style = Paint.Style.FILL
        }
    private val insertSpaceBadgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 32, 32)
            style = Paint.Style.FILL
        }
    private val insertSpaceBadgeBorderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 120, 215)
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
        }
    private val insertSpaceTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(240, 240, 240)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    private val insertSpaceArrowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 120, 215)
            style = Paint.Style.FILL
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
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

    fun snapshotStrokes(): List<Stroke> = strokes.toList()

    fun replaceStrokes(replacement: List<Stroke>) {
        strokes.clear()
        strokes.addAll(replacement)
        selectedStrokes.clear()
        lassoPoints.clear()
        invalidate()
    }

    fun contentBottom(): Float {
        var bottom = 0f
        for (stroke in strokes) {
            val inputs = stroke.inputs
            for (index in 0 until inputs.size) {
                bottom = maxOf(bottom, inputs.get(index).y + stroke.brush.size * 0.5f)
            }
        }
        return bottom
    }

    fun setViewport(matrix: Matrix) {
        documentToView.set(matrix)
        invalidate()
        backgroundInvalidator?.invoke()
    }

    internal fun delegateBackgroundRendering(invalidator: () -> Unit) {
        renderBackgroundInInkLayer = false
        backgroundInvalidator = invalidator
        invalidate()
        invalidator()
    }

    fun setDarkBackground(enabled: Boolean) {
        val replacements = LinkedHashMap<Stroke, Stroke>()
        for (stroke in strokes) {
            val color = stroke.brush.colorIntArgb
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val isMappedBlack =
                (red == 239 && green == 239 && blue == 239) ||
                    (red == 27 && green == 27 && blue == 31)
            if (!isMappedBlack) continue
            val mappedRgb = if (enabled) 0xFFEFEFEF.toInt() else InkPalette.BLACK.argb
            val mapped = Color.argb(Color.alpha(color), Color.red(mappedRgb), Color.green(mappedRgb), Color.blue(mappedRgb))
            if (mapped == color) continue
            replacements[stroke] = Stroke(stroke.brush.copyWithColorIntArgb(mapped), stroke.inputs)
        }
        if (replacements.isEmpty()) return
        for (index in strokes.indices) replacements[strokes[index]]?.let { strokes[index] = it }
        val selectedReplacements = selectedStrokes.mapNotNull(replacements::get)
        selectedStrokes.removeAll(replacements.keys)
        selectedStrokes.addAll(selectedReplacements)
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

    fun setInsertSpaceHoverY(hoverY: Float?) {
        if (insertSpaceHoverY != hoverY) {
            insertSpaceHoverY = hoverY
            invalidate()
        }
    }

    fun setInsertSpaceGuide(startY: Float?, currentY: Float?) {
        insertSpaceStartY = startY
        insertSpaceCurrentY = currentY
        invalidate()
    }

    fun insertVerticalSpace(startY: Float, deltaY: Float) {
        if (deltaY == 0f || strokes.isEmpty()) return
        val replacements = LinkedHashMap<Stroke, Stroke>()
        for (stroke in strokes) {
            val inputs = stroke.inputs
            if (inputs.isEmpty()) continue
            val minY = (0 until inputs.size).minOf { inputs.get(it).y }
            if (minY >= startY) {
                val transformedInputs = MutableStrokeInputBatch()
                for (index in 0 until inputs.size) {
                    val input = inputs.get(index)
                    transformedInputs.add(
                        input.toolType,
                        input.x,
                        input.y + deltaY,
                        input.elapsedTimeMillis,
                        input.strokeUnitLengthCm,
                        input.pressure,
                        input.tiltRadians,
                        input.orientationRadians,
                    )
                }
                replacements[stroke] = Stroke(stroke.brush, transformedInputs)
            }
        }
        for (index in strokes.indices) {
            replacements[strokes[index]]?.let { strokes[index] = it }
        }
        invalidate()
    }

    fun renderBackgroundForExport(canvas: Canvas, documentMatrix: Matrix = documentToView) {
        canvas.drawColor(Color.WHITE)
        renderBackground(canvas, documentMatrix, exportTheme = true)
    }

    fun renderBackground(canvas: Canvas, documentMatrix: Matrix = documentToView) {
        renderBackground(canvas, documentMatrix, exportTheme = false)
    }

    private fun renderBackground(canvas: Canvas, documentMatrix: Matrix, exportTheme: Boolean) {
        canvas.save()
        canvas.concat(documentMatrix)
        drawGrid(canvas, exportTheme)
        canvas.restore()
    }

    fun renderInkForExport(canvas: Canvas, documentMatrix: Matrix = documentToView) {
        strokeRenderer.drawWithStrokes(canvas) { transformedCanvas, scope ->
            transformedCanvas.save()
            transformedCanvas.concat(documentMatrix)
            strokes.forEach(scope::drawStroke)
            transformedCanvas.restore()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokeRenderer.drawWithStrokes(canvas) { transformedCanvas, scope ->
            transformedCanvas.save()
            transformedCanvas.concat(documentToView)
            if (renderBackgroundInInkLayer) drawGrid(transformedCanvas)
            val startY = insertSpaceStartY
            val currentY = insertSpaceCurrentY
            for (stroke in strokes) {
                if (startY != null && currentY != null) {
                    val deltaY = currentY - startY
                    val inputs = stroke.inputs
                    val isBelow = if (inputs.isEmpty()) false else (0 until inputs.size).minOf { inputs.get(it).y } >= startY
                    if (isBelow) {
                        transformedCanvas.save()
                        transformedCanvas.translate(0f, deltaY)
                        scope.drawStroke(stroke)
                        transformedCanvas.restore()
                        continue
                    }
                }
                scope.drawStroke(stroke)
            }
            drawLasso(transformedCanvas)
            eraserCursor?.let { cursor ->
                transformedCanvas.drawCircle(cursor.x, cursor.y, eraserRadius, eraserCursorPaint)
            }
            drawInsertSpaceGuide(transformedCanvas)
            transformedCanvas.restore()
        }
    }

    private fun drawInsertSpaceGuide(canvas: Canvas) {
        val visible = visibleDocumentBounds()
        val startY = insertSpaceStartY
        val currentY = insertSpaceCurrentY

        if (startY != null && currentY != null) {
            // Baseline at touch down point
            canvas.drawLine(visible.left, startY, visible.right, startY, insertSpaceLinePaint)
            // Moving line at current touch point
            canvas.drawLine(visible.left, currentY, visible.right, currentY, insertSpaceMovingLinePaint)

            val topY = minOf(startY, currentY)
            val bottomY = maxOf(startY, currentY)
            if (bottomY - topY >= 1f) {
                canvas.drawRect(visible.left, topY, visible.right, bottomY, insertSpaceBandPaint)

                val deltaY = currentY - startY
                val badgeText = if (deltaY >= 0) "+${deltaY.toInt()} px" else "${deltaY.toInt()} px"
                val centerX = (visible.left + visible.right) * 0.5f
                val centerY = (topY + bottomY) * 0.5f
                val badgeWidth = 76f * resources.displayMetrics.density
                val badgeHeight = 24f * resources.displayMetrics.density
                val badgeRect = RectF(
                    centerX - badgeWidth * 0.5f,
                    centerY - badgeHeight * 0.5f,
                    centerX + badgeWidth * 0.5f,
                    centerY + badgeHeight * 0.5f,
                )
                canvas.drawRoundRect(badgeRect, 4f * resources.displayMetrics.density, 4f * resources.displayMetrics.density, insertSpaceBadgePaint)
                canvas.drawRoundRect(badgeRect, 4f * resources.displayMetrics.density, 4f * resources.displayMetrics.density, insertSpaceBadgeBorderPaint)
                val fontMetrics = insertSpaceTextPaint.fontMetrics
                val textY = centerY - (fontMetrics.ascent + fontMetrics.descent) * 0.5f
                canvas.drawText(badgeText, centerX, textY, insertSpaceTextPaint)
            }
            return
        }

        val hoverY = insertSpaceHoverY
        if (hoverY != null) {
            canvas.drawLine(visible.left, hoverY, visible.right, hoverY, insertSpaceLinePaint)
            val centerX = (visible.left + visible.right) * 0.5f
            val size = 6f * resources.displayMetrics.density
            val arrowPath = Path().apply {
                moveTo(centerX, hoverY - size * 1.6f)
                lineTo(centerX - size * 0.9f, hoverY - size * 0.5f)
                lineTo(centerX + size * 0.9f, hoverY - size * 0.5f)
                close()

                moveTo(centerX, hoverY + size * 1.6f)
                lineTo(centerX - size * 0.9f, hoverY + size * 0.5f)
                lineTo(centerX + size * 0.9f, hoverY + size * 0.5f)
                close()
            }
            canvas.drawPath(arrowPath, insertSpaceArrowPaint)
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

    private fun drawGrid(canvas: Canvas, exportTheme: Boolean = false) {
        if (backgroundFormat.category == BackgroundCategory.BLANK) return
        val visible = visibleDocumentBounds()
        val step = gridStep * backgroundFormat.stepMultiplier

        when (backgroundFormat.category) {
            BackgroundCategory.BLANK -> return

            BackgroundCategory.RULE_LINES -> {
                if (backgroundFormat.showMarginLine) {
                    val marginX = 64f * resources.displayMetrics.density
                    if (visible.left <= marginX && visible.right >= marginX) {
                        canvas.drawLine(marginX, visible.top, marginX, visible.bottom, if (exportTheme) exportMarginLinePaint else marginLinePaint)
                    }
                }
                var y = floor(visible.top / step) * step
                while (y <= visible.bottom) {
                    val index = floor(y / step).toInt()
                    val paint =
                        if (exportTheme) {
                            if (index % 4 == 0) exportMajorGridPaint else exportThinGridPaint
                        } else if (index % 4 == 0) majorGridPaint else thinGridPaint
                    canvas.drawLine(visible.left, y, visible.right, y, paint)
                    y += step
                }
            }

            BackgroundCategory.GRID -> {
                var x = floor(visible.left / step) * step
                while (x <= visible.right) {
                    val index = floor(x / step).toInt()
                    val paint =
                        if (exportTheme) {
                            if (index % 4 == 0) exportMajorGridPaint else exportThinGridPaint
                        } else if (index % 4 == 0) majorGridPaint else thinGridPaint
                    canvas.drawLine(x, visible.top, x, visible.bottom, paint)
                    x += step
                }
                var y = floor(visible.top / step) * step
                while (y <= visible.bottom) {
                    val index = floor(y / step).toInt()
                    val paint =
                        if (exportTheme) {
                            if (index % 4 == 0) exportMajorGridPaint else exportThinGridPaint
                        } else if (index % 4 == 0) majorGridPaint else thinGridPaint
                    canvas.drawLine(visible.left, y, visible.right, y, paint)
                    y += step
                }
            }

            BackgroundCategory.DOTS -> {
                val dotRadius = 1.25f * resources.displayMetrics.density
                var x = floor(visible.left / step) * step
                while (x <= visible.right) {
                    var y = floor(visible.top / step) * step
                    while (y <= visible.bottom) {
                        canvas.drawCircle(x, y, dotRadius, if (exportTheme) exportDotPaint else dotPaint)
                        y += step
                    }
                    x += step
                }
            }
        }
    }

    private fun visibleDocumentBounds(): RectF {
        val inverse = Matrix()
        if (!documentToView.invert(inverse)) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        return RectF(0f, 0f, width.toFloat(), height.toFloat()).also(inverse::mapRect)
    }

    private fun invalidateBackground() {
        if (renderBackgroundInInkLayer) invalidate() else backgroundInvalidator?.invoke()
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
