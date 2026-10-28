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
import androidx.ink.geometry.MutableVec
import java.util.IdentityHashMap
import kotlin.math.floor
import kotlin.math.sqrt

class DryInkView(context: Context) : View(context) {
    var sceneRenderer: ((Canvas,List<Stroke>,(Stroke)->Unit)->Unit)? = null
    var onContentChanged: (() -> Unit)? = null
    var backgroundFormat: CanvasBackgroundFormat = CanvasBackgroundFormat.GRID_STANDARD
        set(value) {
            field = value
            invalidateBackground()
        }

    private var renderBackgroundInInkLayer = true
    private var backgroundInvalidator: (() -> Unit)? = null
    private var outputPageSettings = PageViewSettings()
    private var outputPageTitle = ""

    fun configureOutputPage(settings: PageViewSettings, title: String = "") {
        updateBackgroundPage(settings)
        outputPageTitle = title
    }

    internal fun updateBackgroundPage(settings: PageViewSettings) {
        outputPageSettings = settings.copy()
        backgroundFormat = settings.backgroundFormat
    }

    var shapePreview: Stroke? = null
        set(value) { field=value; invalidate() }
    private val strokes = mutableListOf<Stroke>()
    var contentRevision = 0L
        private set
    private val strokeBounds = IdentityHashMap<Stroke, StrokeViewportBounds>()
    private var cachedContentBottom = 0f
    private val redoStrokes = ArrayDeque<Stroke>()
    private val selectedStrokes = mutableSetOf<Stroke>()
    private val lassoPoints = mutableListOf<DocumentPoint>()
    private var lassoShape = LassoShape.FREEFORM
    private var lassoStartPoint: DocumentPoint? = null
    private val thinGridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marginLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeRenderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
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
        redoStrokes.clear()
        strokes.addAll(newStrokes)
        invalidate()
        newStrokes.forEach(::registerStroke)
        onContentChanged?.invoke()
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        redoStrokes.addLast(strokes.removeAt(strokes.lastIndex))
        rebuildStrokeMetrics()
        invalidate()
        return true
    }

    fun redo(): Boolean {
        if (redoStrokes.isEmpty()) return false
        val restored = redoStrokes.removeLast()
        strokes.add(restored)
        registerStroke(restored)
        onContentChanged?.invoke()
        invalidate()
        return true
    }

    fun clear() {
        contentRevision++
        strokes.clear()
        strokeBounds.clear()
        cachedContentBottom = 0f
        redoStrokes.clear()
        selectedStrokes.clear()
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
        onContentChanged?.invoke()
    }

    fun strokeCount(): Int = strokes.size

    fun snapshotStrokes(): List<Stroke> = strokes.toList()

    fun snapshotSelectedStrokes(): List<Stroke> = strokes.filter { it in selectedStrokes }

    fun appendSelectedStrokes(items: List<Stroke>, select: Boolean = true) {
        val previous = if (select) emptyList() else selectedStrokes.toList()
        addStrokes(items)
        selectedStrokes.addAll(if (select) items else previous)
    }

    fun replaceStrokes(replacement: List<Stroke>) {
        strokes.clear()
        strokes.addAll(replacement)
        rebuildStrokeMetrics()
        redoStrokes.clear()
        selectedStrokes.clear()
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
    }

    fun contentBottom(): Float = cachedContentBottom

    fun contentBounds(): Sequence<StrokeViewportBounds> = strokes.asSequence().filter { !it.inputs.isEmpty() }.map(::boundsFor)

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
        outputPageSettings.darkBackground = enabled
        invalidateBackground()
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
            replacements[stroke] = Stroke(stroke.brush.copyWithColorIntArgb(mapped), stroke.inputs).also { SharedStrokeCodec.recolored(stroke,it) }
        }
        if (replacements.isEmpty()) return
        for (index in strokes.indices) replacements[strokes[index]]?.let { strokes[index] = it }
        val selectedReplacements = selectedStrokes.mapNotNull(replacements::get)
        selectedStrokes.removeAll(replacements.keys)
        selectedStrokes.addAll(selectedReplacements)
        rebuildStrokeMetrics(notify=false)
        invalidate()
    }

    fun setEraserCursor(point: PointF?, radius: Float = 0f) {
        eraserCursor = point
        eraserRadius = radius
        invalidate()
    }

    fun beginLasso(point: PointF, shape: LassoShape = LassoShape.FREEFORM) {
        selectedStrokes.clear()
        beginLassoPreview(point, shape)
    }

    fun beginLassoPreview(point: PointF, shape: LassoShape = LassoShape.FREEFORM) {
        lassoPoints.clear()
        lassoShape = shape
        lassoStartPoint = DocumentPoint(point.x, point.y)
        lassoPoints += requireNotNull(lassoStartPoint)
        invalidate()
    }

    fun extendLasso(point: PointF) {
        if (lassoShape == LassoShape.RECTANGLE) {
            val start = lassoStartPoint ?: DocumentPoint(point.x, point.y)
            lassoPoints.clear()
            lassoPoints += SelectionGeometry.rectanglePolygon(start, DocumentPoint(point.x, point.y))
            invalidate()
            return
        }
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
                if(!SharedStrokeCodec.visible(stroke)) continue
                val outline = SharedStrokeCodec.bounds(stroke)
                if(outline != null) {
                    if(listOf(DocumentPoint(outline.left,outline.top),DocumentPoint(outline.right,outline.top),
                        DocumentPoint(outline.right,outline.bottom),DocumentPoint(outline.left,outline.bottom)).all {
                            SelectionGeometry.pointInPolygonOrBoundary(it.x,it.y,polygon)
                        }) selectedStrokes += stroke
                    continue
                }
                val inputs = stroke.inputs
                if (inputs.size > 0 && (0 until inputs.size).all { index ->
                        val input = inputs.get(index)
                        SelectionGeometry.pointInPolygonOrBoundary(input.x, input.y, polygon)
                    }
                ) {
                    selectedStrokes += stroke
                }
            }
        }
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
        return polygon
    }

    fun cancelLassoPreview() {
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
    }

    fun selectedCount(): Int = selectedStrokes.size

    fun selectAll(): Int {
        selectedStrokes.clear()
        selectedStrokes.addAll(strokes.filter(SharedStrokeCodec::visible))
        invalidate()
        return selectedStrokes.size
    }

    fun isSelectedAtDocument(x: Float, y: Float, radius: Float): Boolean =
        selectedStrokes.any { stroke -> strokeMayTouchCircle(stroke, x, y, radius) && strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f) }

    fun hasStrokeAtDocument(x: Float, y: Float, radius: Float): Boolean =
        strokes.any { stroke -> strokeMayTouchCircle(stroke, x, y, radius) && strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f) }

    fun selectAtDocument(x: Float, y: Float, radius: Float): Boolean {
        val hit = strokes.asReversed().firstOrNull { stroke ->
            strokeMayTouchCircle(stroke, x, y, radius) && strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f)
        }
            ?: return false
        selectedStrokes.clear()
        selectedStrokes += hit
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
        return true
    }

    fun clearSelection() {
        selectedStrokes.clear()
        lassoPoints.clear()
        lassoStartPoint = null
        invalidate()
    }

    fun deleteSelection(): Int {
        val count = selectedStrokes.size
        if (count == 0) return 0
        redoStrokes.clear()
        strokes.removeAll(selectedStrokes)
        selectedStrokes.clear()
        rebuildStrokeMetrics()
        invalidate()
        return count
    }

    fun selectedBounds(): RectF? {
        if (selectedStrokes.isEmpty()) return null
        var bounds: RectF? = null
        for (stroke in selectedStrokes) {
            if (stroke.inputs.isEmpty()) continue
            // Include the brush extent so a horizontal/vertical stroke has resizeable bounds.
            val cached = boundsFor(stroke)
            val rect = RectF(cached.left, cached.top, cached.right, cached.bottom)
            val current = bounds
            if (current == null) bounds = rect else current.union(rect)
        }
        return bounds
    }

    fun translateSelection(deltaX: Float, deltaY: Float) {
        transformSelection(pointTransform = { x, y -> SelectionGeometry.translate(x, y, deltaX, deltaY) })
    }

    fun scaleSelection(anchorX: Float, anchorY: Float, factorX: Float, factorY: Float = factorX) {
        transformSelection(
            pointTransform = { x, y -> SelectionGeometry.scale(x, y, anchorX, anchorY, factorX, factorY) },
            brushScale = kotlin.math.sqrt(factorX * factorY),
        )
    }

    /** First implementation deliberately erases complete strokes touched by the pressure eraser. */
    fun eraseAtDocument(x: Float, y: Float, radius: Float): Int {
        var removed = 0
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (strokeMayTouchCircle(stroke, x, y, radius) && strokeTouchesCircle(stroke, x, y, radius + stroke.brush.size * 0.5f)) {
                iterator.remove()
                selectedStrokes.remove(stroke)
                removed++
            }
        }
        if (removed > 0) {
            rebuildStrokeMetrics()
            invalidate()
        }
        if (removed > 0) redoStrokes.clear()
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
                replacements[stroke] = Stroke(stroke.brush, transformedInputs).also { SharedStrokeCodec.transformed(stroke,it,{ x,y -> DocumentPoint(x,y+deltaY) }) }
            }
        }
        for (index in strokes.indices) {
            replacements[strokes[index]]?.let { strokes[index] = it }
        }
        rebuildStrokeMetrics()
        invalidate()
    }

    fun renderBackgroundForExport(canvas: Canvas, documentMatrix: Matrix = documentToView, output: OutputAppearance = OutputAppearance()) {
        val settings = outputPageSettings
        val printingDark = output.printsDark(settings.darkBackground)
        val displayedPaper = settings.pageColor.displayArgb(settings.darkBackground)
        val paperColor = if (printingDark) OutputAppearance.printPaper(displayedPaper) else displayedPaper
        canvas.drawColor(if (settings.paperSize == PaperSize.A4) {
            if (printingDark) Color.WHITE else 0xFF0A0A0A.toInt()
        } else paperColor)
        val inverse = Matrix()
        val visible = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        if (documentMatrix.invert(inverse)) inverse.mapRect(visible)
        val pageWidth = settings.documentWidth ?: A4PageLayout.pageWidth
        val pageHeight = settings.documentHeight ?: A4PageLayout.pageHeight
        val stride = pageHeight + A4PageLayout.pageGap
        val first = if (settings.paperSize == PaperSize.A4) maxOf(0, floor(visible.top / stride).toInt()) else 0
        val last = if (settings.paperSize == PaperSize.A4) minOf(settings.a4PageCount - 1, floor(visible.bottom / stride).toInt()) else 0
        canvas.save()
        canvas.concat(documentMatrix)
        for (index in first..last) {
            val top = if (settings.paperSize == PaperSize.A4) index * stride else 0f
            val pageRect = RectF(0f, top, pageWidth, top + pageHeight)
            val clip = RectF(pageRect)
            if (!clip.intersect(visible)) continue
            canvas.drawRect(pageRect, Paint().apply { color = paperColor })
            canvas.save()
            canvas.clipRect(pageRect)
            if (!output.printing || !output.hideBackgroundFormat) drawGrid(canvas, exportTheme = printingDark, visible = clip, forOutput = true)
            canvas.restore()
        }
        if (settings.pageTitleVisible && outputPageTitle.isNotBlank() && settings.paperSize != PaperSize.BOUNDLESS) {
            val displayedTitle = if (settings.darkBackground) 0xFFEFEFEF.toInt() else 0xFF202020.toInt()
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (printingDark) OutputAppearance.printInk(displayedTitle) else displayedTitle
                textSize = 5.2f * DOCUMENT_UNITS_PER_MM
                typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            }
            canvas.drawText(outputPageTitle, 18f * DOCUMENT_UNITS_PER_MM, 22f * DOCUMENT_UNITS_PER_MM, titlePaint)
        }
        canvas.restore()
    }

    fun renderBackground(canvas: Canvas, documentMatrix: Matrix = documentToView) {
        renderBackground(canvas, documentMatrix, exportTheme = false)
    }

    fun renderBackground(canvas: Canvas, documentClips: Iterable<RectF>, documentMatrix: Matrix = documentToView) {
        canvas.save()
        canvas.concat(documentMatrix)
        documentClips.forEach { clip ->
            val visible = visibleDocumentBounds()
            if (!visible.intersect(clip)) return@forEach
            canvas.save()
            canvas.clipRect(clip)
            drawGrid(canvas, visible = visible, viewMatrix = documentMatrix)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun renderBackground(canvas: Canvas, documentMatrix: Matrix, exportTheme: Boolean) {
        val bounds = if (exportTheme) {
            val inverse = Matrix()
            RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()).also {
                if (documentMatrix.invert(inverse)) inverse.mapRect(it)
            }
        } else visibleDocumentBounds()
        canvas.save()
        canvas.concat(documentMatrix)
        drawGrid(canvas, exportTheme, bounds, viewMatrix = documentMatrix)
        canvas.restore()
    }

    fun renderInkForExport(canvas: Canvas, documentMatrix: Matrix = documentToView) {
        strokeRenderer.drawWithStrokes(canvas) { transformedCanvas, scope ->
            transformedCanvas.save()
            transformedCanvas.concat(documentMatrix)
            strokes.forEach { if (!SharedStrokeCodec.draw(transformedCanvas,it)) scope.drawStroke(it) }
            transformedCanvas.restore()
        }
    }

    /** PDF canvases receive the native stroke outlines, never a screenshot or GPU mesh draw. */
    fun renderVectorInkForExport(canvas: Canvas, documentMatrix: Matrix, selectedOnly: Boolean = false, output: OutputAppearance = OutputAppearance(), onlyStroke: Stroke? = null) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val position = MutableVec()
        canvas.save()
        canvas.concat(documentMatrix)
        (onlyStroke?.let(::listOf) ?: strokes).forEach { stroke ->
            if (selectedOnly && stroke !in selectedStrokes) return@forEach
            if (SharedStrokeCodec.draw(canvas,stroke,output.printsDark(outputPageSettings.darkBackground))) return@forEach
            val shape = stroke.shape
            paint.color = if (output.printsDark(outputPageSettings.darkBackground)) OutputAppearance.printInk(stroke.brush.colorIntArgb) else stroke.brush.colorIntArgb
            for (group in 0 until shape.getRenderGroupCount()) {
                val path = Path().apply { fillType = Path.FillType.WINDING }
                for (outline in 0 until shape.getOutlineCount(group)) {
                    val count = shape.getOutlineVertexCount(group, outline)
                    if (count == 0) continue
                    for (vertex in 0 until count) {
                        shape.populateOutlinePosition(group, outline, vertex, position)
                        if (vertex == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
                    }
                    path.close()
                }
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visible = visibleStrokeBounds()
        strokeRenderer.drawWithStrokes(canvas) { transformedCanvas, scope ->
            transformedCanvas.save()
            transformedCanvas.concat(documentToView)
            if (renderBackgroundInInkLayer) drawGrid(transformedCanvas)
            val startY = insertSpaceStartY
            val currentY = insertSpaceCurrentY
            val renderScene=sceneRenderer
            if(renderScene != null && startY == null && currentY == null) {
                renderScene(transformedCanvas,strokes.filter { boundsFor(it).intersects(visible) }) { stroke -> if(!SharedStrokeCodec.draw(transformedCanvas,stroke)) scope.drawStroke(stroke) }
            } else for (stroke in strokes) {
                val bounds = boundsFor(stroke)
                if (startY != null && currentY != null) {
                    val deltaY = currentY - startY
                    val inputs = stroke.inputs
                    val isBelow = if (inputs.isEmpty()) false else (0 until inputs.size).minOf { inputs.get(it).y } >= startY
                    if (isBelow) {
                        if (!bounds.translated(deltaY).intersects(visible)) continue
                        transformedCanvas.save()
                        transformedCanvas.translate(0f, deltaY)
                        if (!SharedStrokeCodec.draw(transformedCanvas,stroke)) scope.drawStroke(stroke)
                        transformedCanvas.restore()
                        continue
                    }
                }
                if (!bounds.intersects(visible)) continue
                if (!SharedStrokeCodec.draw(transformedCanvas,stroke)) scope.drawStroke(stroke)
            }
            shapePreview?.let { scope.drawStroke(it) }
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
            if (lassoShape == LassoShape.RECTANGLE && lassoPoints.size >= 3) close()
        }
        canvas.drawPath(path, lassoPaint)
    }

    private fun drawGrid(canvas: Canvas, exportTheme: Boolean = false, visible: RectF = visibleDocumentBounds(), forOutput: Boolean = false, viewMatrix: Matrix = documentToView) {
        if (backgroundFormat.category == BackgroundCategory.BLANK) return
        val spec = BackgroundPatternCatalog
        val step = spec.documentUnits(backgroundFormat.pattern.spacing)
        val palette = if (exportTheme || !outputPageSettings.darkBackground) spec.light else spec.dark
        thinGridPaint.color = palette.line
        majorGridPaint.color = palette.major
        dotPaint.color = palette.dot
        marginLinePaint.color = palette.margin
        thinGridPaint.strokeWidth = if (spec.screenLineWidth && !forOutput) {
            val values = FloatArray(9).also(viewMatrix::getValues)
            spec.lineWidth * resources.displayMetrics.density / kotlin.math.abs(values[Matrix.MSCALE_X]).coerceAtLeast(.1f)
        } else spec.documentUnits(spec.lineWidth)
        majorGridPaint.strokeWidth = thinGridPaint.strokeWidth
        marginLinePaint.strokeWidth = spec.documentUnits(spec.marginWidth)
        fun linePaint(index: Int) = if (spec.majorEvery > 0 && index % spec.majorEvery == 0) majorGridPaint else thinGridPaint

        when (backgroundFormat.category) {
            BackgroundCategory.BLANK -> return

            BackgroundCategory.RULE_LINES -> {
                if (backgroundFormat.showColumnDivider) {
                    // Paper width is independent of the visible viewport/export crop.
                    val centerX = (outputPageSettings.documentWidth ?: A4PageLayout.pageWidth) * 0.5f
                    if (visible.left <= centerX && visible.right >= centerX) {
                        canvas.drawLine(centerX, visible.top, centerX, visible.bottom, majorGridPaint)
                    }
                }
                if (backgroundFormat.showMarginLine) {
                    val marginX = spec.documentUnits(spec.marginX)
                    if (visible.left <= marginX && visible.right >= marginX) {
                        canvas.drawLine(marginX, visible.top, marginX, visible.bottom, marginLinePaint)
                    }
                }
                var index = kotlin.math.ceil(visible.top / step).toInt()
                while (index * step <= visible.bottom) {
                    val y = index * step
                    canvas.drawLine(visible.left, y, visible.right, y, linePaint(index))
                    index++
                }
            }

            BackgroundCategory.GRID -> {
                var column = kotlin.math.ceil(visible.left / step).toInt()
                while (column * step <= visible.right) {
                    val x = column * step
                    canvas.drawLine(x, visible.top, x, visible.bottom, linePaint(column))
                    column++
                }
                var row = kotlin.math.ceil(visible.top / step).toInt()
                while (row * step <= visible.bottom) {
                    val y = row * step
                    canvas.drawLine(visible.left, y, visible.right, y, linePaint(row))
                    row++
                }
            }

            BackgroundCategory.DOTS -> {
                val dotRadius = spec.documentUnits(spec.dotRadius)
                var column = kotlin.math.ceil(visible.left / step).toInt()
                while (column * step <= visible.right) {
                    var row = kotlin.math.ceil(visible.top / step).toInt()
                    while (row * step <= visible.bottom) {
                        canvas.drawCircle(column * step, row * step, dotRadius, dotPaint)
                        row++
                    }
                    column++
                }
            }
        }
    }

    private fun visibleDocumentBounds(): RectF {
        val inverse = Matrix()
        if (!documentToView.invert(inverse)) return RectF(0f, 0f, width.toFloat(), height.toFloat())
        return RectF(0f, 0f, width.toFloat(), height.toFloat()).also(inverse::mapRect)
    }

    private fun visibleStrokeBounds(): StrokeViewportBounds =
        visibleDocumentBounds().let { StrokeViewportBounds(it.left, it.top, it.right, it.bottom) }

    private fun registerStroke(stroke: Stroke) {
        contentRevision++
        val bounds = computeStrokeBounds(stroke)
        strokeBounds[stroke] = bounds
        cachedContentBottom = maxOf(cachedContentBottom, bounds.bottom)
    }

    private fun rebuildStrokeMetrics(notify: Boolean = true) {
        contentRevision++
        strokeBounds.clear()
        cachedContentBottom = 0f
        strokes.forEach(::registerStroke)
        if(notify) onContentChanged?.invoke()
    }

    private fun boundsFor(stroke: Stroke): StrokeViewportBounds =
        strokeBounds[stroke] ?: computeStrokeBounds(stroke).also {
            strokeBounds[stroke] = it
            cachedContentBottom = maxOf(cachedContentBottom, it.bottom)
        }

    private fun computeStrokeBounds(stroke: Stroke): StrokeViewportBounds {
        SharedStrokeCodec.bounds(stroke)?.let { return it }
        val inputs = stroke.inputs
        if (inputs.isEmpty()) return StrokeViewportBounds(0f, 0f, 0f, 0f)
        val first = inputs.get(0)
        var left = first.x
        var top = first.y
        var right = first.x
        var bottom = first.y
        for (index in 1 until inputs.size) {
            val input = inputs.get(index)
            left = minOf(left, input.x)
            top = minOf(top, input.y)
            right = maxOf(right, input.x)
            bottom = maxOf(bottom, input.y)
        }
        val margin = stroke.brush.size * 0.5f
        return StrokeViewportBounds(left - margin, top - margin, right + margin, bottom + margin)
    }

    private fun strokeMayTouchCircle(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val margin = radius + stroke.brush.size * 0.5f
        return boundsFor(stroke).intersects(StrokeViewportBounds(x - margin, y - margin, x + margin, y + margin))
    }

    private fun invalidateBackground() {
        if (renderBackgroundInInkLayer) invalidate() else backgroundInvalidator?.invoke()
    }

    private fun strokeTouchesCircle(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        SharedStrokeCodec.hits(stroke,x,y,radius)?.let { return it }
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
            replacements[stroke] = Stroke(brush, transformedInputs).also { SharedStrokeCodec.transformed(stroke,it,pointTransform) }
        }
        for (index in strokes.indices) {
            replacements[strokes[index]]?.let { strokes[index] = it }
        }
        selectedStrokes.clear()
        selectedStrokes.addAll(replacements.values)
        rebuildStrokeMetrics()
        invalidate()
    }
}
