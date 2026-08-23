package com.meta.inklab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.max
import kotlin.math.hypot

class InsertContentLayer(
    context: Context,
    private val viewport: ViewportState,
    private val repository: PageContentRepository,
) : View(context) {
    private var document: PageInsertDocument? = null
    private var pressedObject: InsertObject? = null
    private var selectedObject: InsertObject? = null
    private var gestureObject: InsertObject? = null
    private var gestureMode = ObjectGestureMode.NONE
    private var resizeHandle = -1
    private var lastDocumentPoint = DocumentPoint(0f, 0f)
    private var resizeAnchor = DocumentPoint(0f, 0f)
    private var resizeStartCorner = DocumentPoint(0f, 0f)
    private var resizeStartBounds = RectF()
    private var objectMutated = false
    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 241, 241)
        textSize = sp(14f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 180, 180)
        textSize = sp(10f)
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 153, 255)
        textSize = sp(16f)
        isUnderlineText = true
    }
    private val invertedImagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter =
                ColorMatrixColorFilter(
                    ColorMatrix(ImageViewFilter.colorMatrix),
                )
        }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    var onObjectActivated: ((InsertObject) -> Unit)? = null
    var onObjectContextMenuRequested: ((InsertObject) -> Unit)? = null
    var onDocumentChanged: ((PageInsertDocument) -> Unit)? = null

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = pressedObject != null

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    val item = hitSelectable(event.x, event.y) ?: return false
                    if (selectedObject !== item && isInteractive(item)) onObjectActivated?.invoke(item)
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    val item = hitSelectable(event.x, event.y) ?: return false
                    selectedObject = item
                    invalidate()
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    val item = hitSelectable(event.x, event.y) ?: return
                    selectedObject = item
                    gestureMode = ObjectGestureMode.NONE
                    invalidate()
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onObjectContextMenuRequested?.invoke(item)
                }
            },
        )

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
    }

    fun bind(document: PageInsertDocument?) {
        if (this.document?.notebookId == document?.notebookId && this.document?.pageId == document?.pageId) return
        this.document = document
        pressedObject = null
        selectedObject = null
        bitmapCache.evictAll()
        invalidate()
    }

    fun currentDocument(): PageInsertDocument? = document

    fun selectedCount(): Int = if (selectedObject != null) 1 else 0

    fun selectedBounds(): RectF? = selectedObject?.let(::bounds)

    fun clearSelection() {
        if (selectedObject == null) return
        selectedObject = null
        gestureMode = ObjectGestureMode.NONE
        invalidate()
    }

    fun deleteSelection(): Int {
        val target = document ?: return 0
        val selected = selectedObject ?: return 0
        if (!target.objects.remove(selected)) return 0
        selectedObject = null
        changed(target)
        return 1
    }

    fun duplicateSelection(): InsertObject? {
        val target = document ?: return null
        val source = selectedObject ?: return null
        val copy = InsertObject.fromJson(source.toJson().apply {
            put("id", "copy-${java.util.UUID.randomUUID()}")
            put("x", source.documentX + dp(18f))
            put("y", source.documentY + dp(18f))
        })
        target.objects += copy
        selectedObject = copy
        changed(target)
        return copy
    }

    fun bringSelectionToFront(): Boolean = reorderSelection(toFront = true)

    fun sendSelectionToBack(): Boolean = reorderSelection(toFront = false)

    fun addObject(item: InsertObject) {
        val target = document ?: return
        target.objects += item
        changed(target)
    }

    fun addObjects(items: Collection<InsertObject>) {
        if (items.isEmpty()) return
        val target = document ?: return
        target.objects += items
        changed(target)
    }

    fun notifyObjectChanged() {
        document?.let(::changed)
    }

    fun clearObjects(preservePageBackgrounds: Boolean = true) {
        val target = document ?: return
        if (preservePageBackgrounds) {
            target.objects.removeAll { item -> item !is InsertObject.Image || !item.pageBackground }
        } else {
            target.objects.clear()
        }
        bitmapCache.evictAll()
        changed(target)
    }

    fun retainedPageAssetNames(): Set<String> =
        document?.objects
            ?.filterIsInstance<InsertObject.Image>()
            ?.filter(InsertObject.Image::pageBackground)
            ?.flatMap { image -> listOfNotNull(image.assetName, image.sourceAssetName) }
            ?.toSet()
            .orEmpty()

    fun nextInsertionPoint(): DocumentPoint {
        val visibleTopLeft = viewport.viewToDocument(dp(34f), dp(68f))
        val baseline =
            document?.objects
                ?.filterNot { item -> item is InsertObject.Image && item.pageBackground }
                ?.maxOfOrNull { it.documentY + it.height + dp(18f) }
                ?: visibleTopLeft.y
        return DocumentPoint(visibleTopLeft.x, max(visibleTopLeft.y, baseline))
    }

    fun insertVerticalSpace(startY: Float, deltaY: Float) {
        val target = document ?: return
        if (deltaY == 0f) return
        var changed = false
        target.objects.forEach { item ->
            if (item !is InsertObject.Image || !item.pageBackground) {
                if (item.documentY < startY) return@forEach
                item.documentY += deltaY
                changed = true
            }
        }
        if (changed) changed(target)
    }

    fun renderForExport(canvas: Canvas, documentMatrix: android.graphics.Matrix = viewport.documentToViewMatrix()) {
        drawObjects(canvas, forExport = true, documentMatrix = documentMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawObjects(canvas, forExport = false, documentMatrix = viewport.documentToViewMatrix())
    }

    private fun drawObjects(canvas: Canvas, forExport: Boolean, documentMatrix: android.graphics.Matrix) {
        val target = document ?: return
        canvas.save()
        canvas.concat(documentMatrix)
        target.objects.forEach { item ->
            when (item) {
                is InsertObject.Table -> drawTable(canvas, item, forExport)
                is InsertObject.Attachment -> drawAttachment(canvas, item)
                is InsertObject.Image -> drawImage(canvas, target, item, forExport)
                is InsertObject.Spreadsheet -> drawSpreadsheet(canvas, item, forExport)
                is InsertObject.NoteLink -> drawLink(canvas, item)
            }
        }
        if (!forExport) drawSelection(canvas)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas) {
        val item = selectedObject ?: return
        val box = bounds(item)
        val scale = viewport.scale.coerceAtLeast(0.01f)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 178, 233)
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f) / scale
        }.also { canvas.drawRect(box, it) }
        val radius = dp(5.5f) / scale
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            style = Paint.Style.FILL
        }.also { fill ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(218, 178, 233)
                style = Paint.Style.STROKE
                strokeWidth = dp(1.5f) / scale
            }.also { stroke ->
                selectionCorners(box).forEach { point ->
                    canvas.drawCircle(point.x, point.y, radius, fill)
                    canvas.drawCircle(point.x, point.y, radius, stroke)
                }
            }
        }
    }

    private fun drawTable(canvas: Canvas, table: InsertObject.Table, forExport: Boolean) {
        val bounds = bounds(table)
        fillPaint.color = if (forExport) Color.rgb(252, 252, 252) else Color.rgb(ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY)
        canvas.drawRect(bounds, fillPaint)
        strokePaint.color = if (forExport) Color.rgb(185, 185, 185) else Color.rgb(70, 77, 82)
        val cellWidth = table.width / table.columns
        val cellHeight = table.height / table.rows
        for (column in 0..table.columns) {
            val x = table.documentX + column * cellWidth
            canvas.drawLine(x, table.documentY, x, table.documentY + table.height, strokePaint)
        }
        for (row in 0..table.rows) {
            val y = table.documentY + row * cellHeight
            canvas.drawLine(table.documentX, y, table.documentX + table.width, y, strokePaint)
        }
        val cellPaint = Paint(textPaint).apply { color = if (forExport) Color.rgb(38, 38, 38) else Color.rgb(235, 235, 235); textSize = sp(11f) }
        table.cells.forEachIndexed { index, value ->
            if (value.isBlank()) return@forEachIndexed
            val row = index / table.columns
            val column = index % table.columns
            val x = table.documentX + column * cellWidth + dp(6f)
            val y = table.documentY + row * cellHeight + cellHeight * 0.62f
            canvas.save()
            canvas.clipRect(x, table.documentY + row * cellHeight, x + cellWidth - dp(10f), table.documentY + (row + 1) * cellHeight)
            canvas.drawText(value, x, y, cellPaint)
            canvas.restore()
        }
    }

    private fun drawAttachment(canvas: Canvas, attachment: InsertObject.Attachment) {
        val bounds = bounds(attachment)
        fillPaint.color = Color.rgb(33, 33, 33)
        canvas.drawRoundRect(bounds, dp(3f), dp(3f), fillPaint)
        strokePaint.color = Color.rgb(78, 78, 78)
        canvas.drawRoundRect(bounds, dp(3f), dp(3f), strokePaint)
        val iconBounds = RectF(bounds.left + dp(10f), bounds.top + dp(10f), bounds.left + dp(54f), bounds.bottom - dp(10f))
        fillPaint.color = Color.rgb(77, 54, 88)
        canvas.drawRoundRect(iconBounds, dp(3f), dp(3f), fillPaint)
        drawPaperclip(canvas, iconBounds.centerX(), iconBounds.centerY())
        canvas.drawText(ellipsize(attachment.displayName, 34), bounds.left + dp(67f), bounds.top + dp(28f), textPaint)
        canvas.drawText(formatSize(attachment.sizeBytes), bounds.left + dp(67f), bounds.top + dp(48f), smallTextPaint)
    }

    private fun drawImage(canvas: Canvas, document: PageInsertDocument, image: InsertObject.Image, forExport: Boolean) {
        val file = FileFor(document, image.assetName)
        val bitmap = bitmap(file)
        val destination = bounds(image)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, destination, if (ImageViewFilter.shouldApply(image.viewInverted, forExport)) invertedImagePaint else bitmapPaint)
        } else {
            fillPaint.color = Color.rgb(43, 43, 43)
            canvas.drawRect(destination, fillPaint)
            canvas.drawText("图片不可用", destination.left + dp(12f), destination.centerY(), smallTextPaint)
        }
        if (image.sourceKind != ImageSourceKind.STICKER && !image.pageBackground) {
            strokePaint.color = Color.rgb(78, 78, 78)
            canvas.drawRect(destination, strokePaint)
        }
        if (image.sourceKind == ImageSourceKind.PDF_PAGE && !image.pageBackground) {
            fillPaint.color = Color.argb(205, 28, 28, 28)
            val labelBounds = RectF(destination.left + dp(8f), destination.top + dp(8f), destination.left + dp(72f), destination.top + dp(30f))
            canvas.drawRoundRect(labelBounds, dp(2f), dp(2f), fillPaint)
            canvas.drawText("PDF · ${image.sourcePageNumber ?: 1}", labelBounds.left + dp(7f), labelBounds.top + dp(15f), smallTextPaint)
        }
    }

    private fun drawSpreadsheet(canvas: Canvas, sheet: InsertObject.Spreadsheet, forExport: Boolean) {
        val bounds = bounds(sheet)
        fillPaint.color = if (forExport) Color.WHITE else Color.rgb(29, 29, 29)
        canvas.drawRect(bounds, fillPaint)
        strokePaint.color = if (forExport) Color.rgb(190, 190, 190) else Color.rgb(70, 77, 82)
        canvas.drawRect(bounds, strokePaint)
        val headerHeight = dp(48f)
        fillPaint.color = Color.rgb(28, 94, 57)
        canvas.drawRoundRect(
            RectF(bounds.left + dp(10f), bounds.top + dp(9f), bounds.left + dp(42f), bounds.top + dp(39f)),
            dp(3f), dp(3f), fillPaint,
        )
        Paint(textPaint).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }.also {
            canvas.drawText("X", bounds.left + dp(26f), bounds.top + dp(30f), it)
        }
        val headerText = Paint(textPaint).apply { color = if (forExport) Color.rgb(32, 32, 32) else Color.rgb(241, 241, 241) }
        canvas.drawText(ellipsize(sheet.displayName, 42), bounds.left + dp(52f), bounds.top + dp(29f), headerText)

        val rows = sheet.previewRows.ifEmpty { mutableListOf(mutableListOf("空工作表")) }
        val columns = rows.maxOfOrNull(List<String>::size)?.coerceAtLeast(1) ?: 1
        val gridTop = bounds.top + headerHeight
        val cellWidth = sheet.width / columns
        val cellHeight = (sheet.height - headerHeight) / rows.size
        fillPaint.color = if (forExport) Color.rgb(252, 252, 252) else Color.rgb(ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY)
        canvas.drawRect(bounds.left, gridTop, bounds.right, bounds.bottom, fillPaint)
        strokePaint.color = if (forExport) Color.rgb(208, 208, 208) else Color.rgb(60, 69, 74)
        val gridText = Paint(smallTextPaint).apply { color = if (forExport) Color.rgb(52, 52, 52) else Color.rgb(226, 226, 226); textSize = sp(9f) }
        for (rowIndex in rows.indices) {
            for (columnIndex in 0 until columns) {
                val left = bounds.left + columnIndex * cellWidth
                val top = gridTop + rowIndex * cellHeight
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, strokePaint)
                rows[rowIndex].getOrNull(columnIndex)?.takeIf(String::isNotBlank)?.let { value ->
                    canvas.save()
                    canvas.clipRect(left + dp(4f), top, left + cellWidth - dp(4f), top + cellHeight)
                    canvas.drawText(ellipsize(value, 18), left + dp(5f), top + cellHeight * 0.64f, gridText)
                    canvas.restore()
                }
            }
        }
    }

    private fun drawLink(canvas: Canvas, link: InsertObject.NoteLink) {
        canvas.drawText(ellipsize(link.targetTitle, 58), link.documentX, link.documentY + link.height * 0.7f, linkPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
            pressedObject = null
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedObject = hitSelectable(event.x, event.y)
                if (pressedObject == null) {
                    clearSelection()
                    return false
                }
                gestureObject = pressedObject
                objectMutated = false
                val point = viewport.viewToDocument(event.x, event.y)
                lastDocumentPoint = DocumentPoint(point.x, point.y)
                gestureMode = ObjectGestureMode.NONE
                if (selectedObject === pressedObject) {
                    val selectedBounds = bounds(pressedObject!!)
                    resizeHandle = hitResizeHandle(point.x, point.y, selectedBounds)
                    if (resizeHandle >= 0) {
                        gestureMode = ObjectGestureMode.RESIZE
                        resizeStartBounds = RectF(selectedBounds)
                        val corners = selectionCorners(selectedBounds)
                        resizeStartCorner = corners[resizeHandle]
                        resizeAnchor = corners[(resizeHandle + 2) % 4]
                    } else if (selectedBounds.contains(point.x, point.y)) {
                        gestureMode = ObjectGestureMode.MOVE
                    }
                }
                gestureDetector.onTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureDetector.onTouchEvent(event)
                val item = gestureObject ?: return false
                val point = viewport.viewToDocument(event.x, event.y)
                when (gestureMode) {
                    ObjectGestureMode.MOVE -> {
                        item.documentX += point.x - lastDocumentPoint.x
                        item.documentY += point.y - lastDocumentPoint.y
                        lastDocumentPoint = DocumentPoint(point.x, point.y)
                        objectMutated = true
                        invalidate()
                    }
                    ObjectGestureMode.RESIZE -> {
                        val startDistance = hypot(resizeStartCorner.x - resizeAnchor.x, resizeStartCorner.y - resizeAnchor.y).coerceAtLeast(1f)
                        val currentDistance = hypot(point.x - resizeAnchor.x, point.y - resizeAnchor.y).coerceAtLeast(1f)
                        val factor = (currentDistance / startDistance).coerceIn(0.1f, 10f)
                        val scaledCorner =
                            DocumentPoint(
                                resizeAnchor.x + (resizeStartCorner.x - resizeAnchor.x) * factor,
                                resizeAnchor.y + (resizeStartCorner.y - resizeAnchor.y) * factor,
                            )
                        item.documentX = minOf(resizeAnchor.x, scaledCorner.x)
                        item.documentY = minOf(resizeAnchor.y, scaledCorner.y)
                        item.width = (resizeStartBounds.width() * factor).coerceAtLeast(dp(24f) / viewport.scale)
                        item.height = (resizeStartBounds.height() * factor).coerceAtLeast(dp(24f) / viewport.scale)
                        objectMutated = true
                        invalidate()
                    }
                    ObjectGestureMode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                gestureDetector.onTouchEvent(event)
                val pressed = pressedObject ?: return false
                pressedObject = null
                gestureObject = null
                gestureMode = ObjectGestureMode.NONE
                if (objectMutated) document?.let(::changed) else performClick()
                objectMutated = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = pressedObject != null
                gestureDetector.onTouchEvent(event)
                pressedObject = null
                gestureObject = null
                gestureMode = ObjectGestureMode.NONE
                if (objectMutated) document?.let(::changed)
                objectMutated = false
                return consumed
            }
        }
        return pressedObject != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun changed(target: PageInsertDocument) {
        onDocumentChanged?.invoke(target)
        invalidate()
    }

    private fun isInteractive(item: InsertObject): Boolean =
        item is InsertObject.Attachment || item is InsertObject.Spreadsheet || item is InsertObject.NoteLink || item is InsertObject.Table

    private fun hitSelectable(viewX: Float, viewY: Float): InsertObject? {
        val point = viewport.viewToDocument(viewX, viewY)
        return document?.objects?.asReversed()?.firstOrNull {
            !(it is InsertObject.Image && it.pageBackground) && bounds(it).contains(point.x, point.y)
        }
    }

    private fun selectionCorners(bounds: RectF): List<DocumentPoint> =
        listOf(
            DocumentPoint(bounds.left, bounds.top),
            DocumentPoint(bounds.right, bounds.top),
            DocumentPoint(bounds.right, bounds.bottom),
            DocumentPoint(bounds.left, bounds.bottom),
        )

    private fun hitResizeHandle(x: Float, y: Float, bounds: RectF): Int {
        val radius = dp(14f) / viewport.scale.coerceAtLeast(0.01f)
        return selectionCorners(bounds).indexOfFirst { point -> hypot(point.x - x, point.y - y) <= radius }
    }

    private fun reorderSelection(toFront: Boolean): Boolean {
        val target = document ?: return false
        val item = selectedObject ?: return false
        if (!target.objects.remove(item)) return false
        if (toFront) target.objects.add(item) else {
            val firstMovable = target.objects.indexOfFirst { it !is InsertObject.Image || !it.pageBackground }.coerceAtLeast(0)
            target.objects.add(firstMovable, item)
        }
        changed(target)
        return true
    }

    private fun bounds(item: InsertObject): RectF =
        RectF(item.documentX, item.documentY, item.documentX + item.width, item.documentY + item.height)

    private fun FileFor(document: PageInsertDocument, assetName: String) =
        File(repository.assetDirectory(document.notebookId, document.pageId), PageContentRepository.safeId(assetName))

    private fun bitmap(file: java.io.File): Bitmap? {
        bitmapCache.get(file.absolutePath)?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / sample > 1800 || options.outHeight / sample > 1800) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        bitmapCache.put(file.absolutePath, decoded)
        return decoded
    }

    private fun drawPaperclip(canvas: Canvas, centerX: Float, centerY: Float) {
        val path = Path().apply {
            moveTo(centerX + dp(6f), centerY - dp(10f))
            cubicTo(centerX + dp(13f), centerY - dp(3f), centerX + dp(8f), centerY + dp(11f), centerX, centerY + dp(11f))
            cubicTo(centerX - dp(8f), centerY + dp(11f), centerX - dp(10f), centerY + dp(2f), centerX - dp(5f), centerY - dp(4f))
            lineTo(centerX + dp(3f), centerY - dp(12f))
        }
        Paint(strokePaint).apply { color = Color.WHITE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }.also { canvas.drawPath(path, it) }
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes < 0 -> "已嵌入"
            bytes < 1024 -> "$bytes B · 已嵌入"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB · 已嵌入"
            else -> String.format("%.1f MB · 已嵌入", bytes / 1024f / 1024f)
        }

    private fun ellipsize(value: String, maxCharacters: Int): String =
        if (value.length <= maxCharacters) value else value.take(maxCharacters - 1) + "…"

    private fun dp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private enum class ObjectGestureMode { NONE, MOVE, RESIZE }
}
