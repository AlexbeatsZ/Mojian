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
import android.view.View
import java.io.File
import kotlin.math.max

class InsertContentLayer(
    context: Context,
    private val viewport: ViewportState,
    private val repository: PageContentRepository,
) : View(context) {
    private var document: PageInsertDocument? = null
    private var selectedObject: InsertObject? = null
    private val lassoSelectedObjects = linkedSetOf<InsertObject>()
    private var selectionTransformMutated = false
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

    var onObjectContextMenuRequested: ((InsertObject, Float, Float) -> Unit)? = null
    var onSelectionChanged: ((InsertObject?) -> Unit)? = null
    var onDocumentChanged: ((PageInsertDocument) -> Unit)? = null
    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
    }

    fun bind(document: PageInsertDocument?) {
        if (this.document?.notebookId == document?.notebookId && this.document?.pageId == document?.pageId) return
        this.document = document
        select(null)
        bitmapCache.evictAll()
        invalidate()
    }

    fun currentDocument(): PageInsertDocument? = document

    fun contentBottom(): Float = document?.objects?.maxOfOrNull { it.documentY + it.height } ?: 0f

    fun contentBounds(): Sequence<StrokeViewportBounds> = document?.objects.orEmpty().asSequence().map {
        StrokeViewportBounds(it.documentX, it.documentY, it.documentX + it.width, it.documentY + it.height)
    }

    fun selectedCount(): Int = selectedItems().size

    fun selectAll(): Int {
        selectedObject = null
        lassoSelectedObjects.clear()
        document?.objects?.forEach { item ->
            if (item !is InsertObject.Image || !item.pageBackground) lassoSelectedObjects += item
        }
        selectionTransformMutated = false
        invalidate()
        return lassoSelectedObjects.size
    }

    fun selectedBounds(): RectF? =
        selectedItems()
            .map(::bounds)
            .reduceOrNull { combined, itemBounds -> combined.apply { union(itemBounds) } }

    fun clearSelection() {
        if (selectedCount() == 0) return
        select(null)
        invalidate()
    }

    fun selectInPolygon(polygon: List<DocumentPoint>): Int {
        selectedObject = null
        lassoSelectedObjects.clear()
        selectionTransformMutated = false
        if (polygon.size >= 3) {
            document?.objects?.forEach { item ->
                if (item is InsertObject.Image && item.pageBackground) return@forEach
                val itemBounds = bounds(item)
                if (
                    SelectionGeometry.polygonContainsRect(
                        polygon,
                        DocumentRect(itemBounds.left, itemBounds.top, itemBounds.right, itemBounds.bottom),
                    )
                ) {
                    lassoSelectedObjects += item
                }
            }
        }
        invalidate()
        return lassoSelectedObjects.size
    }

    fun isSelectedAtView(viewX: Float, viewY: Float): Boolean =
        hitSelectable(viewX, viewY)?.let { it in selectedItems() } == true

    fun hasSelectableAtView(viewX: Float, viewY: Float): Boolean = hitSelectable(viewX, viewY) != null

    fun selectAtView(viewX: Float, viewY: Float): Boolean {
        val item = hitSelectable(viewX, viewY) ?: return false
        select(item)
        invalidate()
        return true
    }

    fun requestContextMenuAt(viewX: Float, viewY: Float): Boolean {
        val item = hitSelectable(viewX, viewY) ?: return false
        select(item)
        invalidate()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onObjectContextMenuRequested?.invoke(item, viewX, viewY)
        return true
    }

    fun translateSelection(deltaX: Float, deltaY: Float) {
        selectedItems().forEach { item ->
            item.documentX += deltaX
            item.documentY += deltaY
        }
        if (selectedCount() > 0) selectionTransformMutated = true
        invalidate()
    }

    fun scaleSelection(anchorX: Float, anchorY: Float, factor: Float) {
        selectedItems().forEach { item ->
            val scaled = SelectionGeometry.scale(item.documentX, item.documentY, anchorX, anchorY, factor)
            item.documentX = scaled.x
            item.documentY = scaled.y
            item.width = (item.width * factor).coerceAtLeast(dp(24f) / viewport.scale.coerceAtLeast(0.01f))
            item.height = (item.height * factor).coerceAtLeast(dp(24f) / viewport.scale.coerceAtLeast(0.01f))
        }
        if (selectedCount() > 0) selectionTransformMutated = true
        invalidate()
    }

    fun finishSelectionTransform() {
        if (!selectionTransformMutated) return
        selectionTransformMutated = false
        document?.let(::changed)
    }

    fun deleteSelection(): Int {
        val target = document ?: return 0
        val selected = selectedItems()
        if (selected.isEmpty()) return 0
        val removed = selected.count(target.objects::remove)
        if (removed == 0) return 0
        select(null)
        changed(target)
        return removed
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
        select(copy)
        changed(target)
        return copy
    }

    fun bringSelectionToFront(): Boolean = reorderSelection(toFront = true)

    fun sendSelectionToBack(): Boolean = reorderSelection(toFront = false)

    fun moveSelectionForward(): Boolean = moveSelectionBy(1)

    fun moveSelectionBackward(): Boolean = moveSelectionBy(-1)

    fun selectedImage(): InsertObject.Image? = selectedItems().singleOrNull() as? InsertObject.Image

    fun rotateSelectedImage(clockwise: Boolean): Boolean {
        val target = document ?: return false
        val image = selectedImage() ?: return false
        val centerX = image.documentX + image.width * 0.5f
        val centerY = image.documentY + image.height * 0.5f
        val oldWidth = image.width
        image.width = image.height
        image.height = oldWidth
        image.documentX = centerX - image.width * 0.5f
        image.documentY = centerY - image.height * 0.5f
        image.rotationQuarterTurns = (image.rotationQuarterTurns + if (clockwise) 1 else 3).mod(4)
        changed(target)
        return true
    }

    fun flipSelectedImage(horizontal: Boolean): Boolean {
        val target = document ?: return false
        val image = selectedImage() ?: return false
        if (horizontal) image.flipHorizontal = !image.flipHorizontal else image.flipVertical = !image.flipVertical
        changed(target)
        return true
    }

    fun restoreSelectedImageSize(): Boolean {
        val target = document ?: return false
        val image = selectedImage() ?: return false
        val file = FileFor(target, image.assetName)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false
        val centerX = image.documentX + image.width * 0.5f
        val centerY = image.documentY + image.height * 0.5f
        image.width = (if (image.rotationQuarterTurns.mod(2) == 0) options.outWidth else options.outHeight).toFloat()
        image.height = (if (image.rotationQuarterTurns.mod(2) == 0) options.outHeight else options.outWidth).toFloat()
        image.documentX = centerX - image.width * 0.5f
        image.documentY = centerY - image.height * 0.5f
        changed(target)
        return true
    }

    fun setSelectedImageAsBackground(): Boolean {
        val target = document ?: return false
        val image = selectedImage() ?: return false
        image.pageBackground = true
        target.objects.remove(image)
        target.objects.add(0, image)
        select(null)
        changed(target)
        return true
    }

    fun addObject(item: InsertObject) {
        val target = document ?: return
        target.objects += item
        changed(target)
    }

    fun addObjectAndSelect(item: InsertObject) {
        val target = document ?: return
        target.objects += item
        select(item)
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
        select(null)
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
        canvas.restore()
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
            val paint = if (ImageViewFilter.shouldApply(image.viewInverted, forExport)) invertedImagePaint else bitmapPaint
            val quarterTurns = image.rotationQuarterTurns.mod(4)
            canvas.save()
            canvas.translate(destination.centerX(), destination.centerY())
            canvas.scale(if (image.flipHorizontal) -1f else 1f, if (image.flipVertical) -1f else 1f)
            canvas.rotate(quarterTurns * 90f)
            val localDestination =
                if (quarterTurns.mod(2) == 0) {
                    RectF(-destination.width() * 0.5f, -destination.height() * 0.5f, destination.width() * 0.5f, destination.height() * 0.5f)
                } else {
                    RectF(-destination.height() * 0.5f, -destination.width() * 0.5f, destination.height() * 0.5f, destination.width() * 0.5f)
                }
            canvas.drawBitmap(bitmap, null, localDestination, paint)
            canvas.restore()
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun changed(target: PageInsertDocument) {
        onDocumentChanged?.invoke(target)
        invalidate()
    }

    private fun hitSelectable(viewX: Float, viewY: Float): InsertObject? {
        val point = viewport.viewToDocument(viewX, viewY)
        return document?.objects?.asReversed()?.firstOrNull {
            !(it is InsertObject.Image && it.pageBackground) && bounds(it).contains(point.x, point.y)
        }
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

    private fun moveSelectionBy(delta: Int): Boolean {
        val target = document ?: return false
        val item = selectedObject ?: return false
        val current = target.objects.indexOf(item)
        if (current < 0) return false
        val firstMovable = target.objects.indexOfFirst { it !is InsertObject.Image || !it.pageBackground }.coerceAtLeast(0)
        val destination = (current + delta).coerceIn(firstMovable, target.objects.lastIndex)
        if (destination == current) return false
        target.objects.removeAt(current)
        target.objects.add(destination, item)
        changed(target)
        return true
    }

    private fun select(item: InsertObject?) {
        if (selectedObject === item && lassoSelectedObjects.isEmpty()) return
        lassoSelectedObjects.clear()
        selectedObject = item
        selectionTransformMutated = false
        onSelectionChanged?.invoke(item)
    }

    private fun selectedItems(): Set<InsertObject> =
        if (lassoSelectedObjects.isNotEmpty()) lassoSelectedObjects else selectedObject?.let(::setOf).orEmpty()

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

}
