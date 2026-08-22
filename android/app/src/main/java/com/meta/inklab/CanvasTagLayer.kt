package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class CanvasTag(
    val kind: CanvasTagKind,
    var documentX: Float,
    var documentY: Float,
)

/** Session-only page tags placed in document coordinates. */
@SuppressLint("ViewConstructor")
class CanvasTagLayer(
    context: Context,
    private val viewport: ViewportState,
) : View(context) {
    private val tags = mutableListOf<CanvasTag>()
    private val selectedTags = linkedSetOf<CanvasTag>()
    private val tagPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TAG_RED
            style = Paint.Style.FILL
        }
    private val focusPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TAG_RED
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
    private var pendingKind: CanvasTagKind? = null
    private var focusedIndex: Int? = null
    private var pressedTag: CanvasTag? = null

    var onPlacementFinished: ((CanvasTag) -> Unit)? = null
    var onTagClicked: ((CanvasTag) -> Unit)? = null
    var selectionModeEnabled = false

    fun beginPlacement(kind: CanvasTagKind) {
        pendingKind = kind
        focusedIndex = null
        isClickable = true
        invalidate()
    }

    fun cancelPlacement() {
        pendingKind = null
        isClickable = false
        invalidate()
    }

    fun allTags(): List<CanvasTag> = tags.toList()

    fun clear() {
        tags.clear()
        selectedTags.clear()
        cancelPlacement()
    }

    fun selectedCount(): Int = selectedTags.size

    fun clearSelection() {
        selectedTags.clear()
        pressedTag = null
        invalidate()
    }

    fun selectInPolygon(polygon: List<DocumentPoint>): Int {
        selectedTags.clear()
        if (polygon.size >= 3) {
            selectedTags += tags.filter { SelectionGeometry.pointInPolygon(it.documentX, it.documentY, polygon) }
        }
        invalidate()
        return selectedTags.size
    }

    fun deleteSelection(): Int {
        val count = selectedTags.size
        if (count == 0) return 0
        tags.removeAll(selectedTags)
        selectedTags.clear()
        focusedIndex = null
        invalidate()
        return count
    }

    fun selectedBounds(): RectF? {
        if (selectedTags.isEmpty()) return null
        val halfSize = dp(10f)
        val first = selectedTags.first()
        return RectF(
            first.documentX - halfSize,
            first.documentY - halfSize,
            first.documentX + halfSize,
            first.documentY + halfSize,
        ).also { bounds ->
            selectedTags.drop(1).forEach { tag ->
                bounds.union(
                    tag.documentX - halfSize,
                    tag.documentY - halfSize,
                    tag.documentX + halfSize,
                    tag.documentY + halfSize,
                )
            }
        }
    }

    fun translateSelection(deltaX: Float, deltaY: Float) {
        selectedTags.forEach { tag ->
            val point = SelectionGeometry.translate(tag.documentX, tag.documentY, deltaX, deltaY)
            tag.documentX = point.x
            tag.documentY = point.y
        }
        if (selectedTags.isNotEmpty()) invalidate()
    }

    fun scaleSelection(anchorX: Float, anchorY: Float, factor: Float) {
        selectedTags.forEach { tag ->
            val point = SelectionGeometry.scale(tag.documentX, tag.documentY, anchorX, anchorY, factor)
            tag.documentX = point.x
            tag.documentY = point.y
        }
        if (selectedTags.isNotEmpty()) invalidate()
    }

    fun insertVerticalSpace(startY: Float, deltaY: Float) {
        if (deltaY == 0f || tags.isEmpty()) return
        var changed = false
        tags.forEach { tag ->
            if (tag.documentY >= startY) {
                tag.documentY += deltaY
                changed = true
            }
        }
        if (changed) invalidate()
    }

    fun eraseAtDocument(x: Float, y: Float, radius: Float): Int {
        val hitRadius = radius + dp(10f)
        val removed =
            tags.filter { tag ->
                SelectionGeometry.squaredDistance(x, y, tag.documentX, tag.documentY) <= hitRadius * hitRadius
            }
        if (removed.isEmpty()) return 0
        tags.removeAll(removed.toSet())
        selectedTags.removeAll(removed.toSet())
        focusedIndex = null
        invalidate()
        return removed.size
    }

    fun focusTag(index: Int) {
        if (index !in tags.indices) return
        focusedIndex = index
        invalidate()
        postDelayed(
            {
                if (focusedIndex == index) {
                    focusedIndex = null
                    invalidate()
                }
            },
            1600L,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kind = pendingKind
        if (kind == null) return handleSelectionTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                performClick()
                val point = viewport.viewToDocument(event.x, event.y)
                val tag = CanvasTag(kind, point.x, point.y)
                tags.add(tag)
                focusedIndex = null
                pendingKind = null
                isClickable = false
                invalidate()
                onPlacementFinished?.invoke(tag)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        val toolType = event.getToolType(event.actionIndex.coerceAtMost(event.pointerCount - 1))
        val maySelect = toolType == MotionEvent.TOOL_TYPE_FINGER || selectionModeEnabled
        if (!maySelect) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = viewport.viewToDocument(event.x, event.y)
                val hitRadius = dp(16f) / viewport.scale
                pressedTag =
                    tags.asReversed().firstOrNull { tag ->
                        SelectionGeometry.squaredDistance(point.x, point.y, tag.documentX, tag.documentY) <= hitRadius * hitRadius
                    } ?: return false
                return true
            }
            MotionEvent.ACTION_MOVE -> return pressedTag != null
            MotionEvent.ACTION_UP -> {
                val tag = pressedTag ?: return false
                pressedTag = null
                selectedTags.clear()
                selectedTags += tag
                focusedIndex = null
                performClick()
                invalidate()
                onTagClicked?.invoke(tag)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = pressedTag != null
                pressedTag = null
                return consumed
            }
        }
        return pressedTag != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(viewport.documentToViewMatrix())
        tags.forEachIndexed { index, tag ->
            when (tag.kind) {
                CanvasTagKind.IMPORTANT -> drawStar(canvas, tag.documentX, tag.documentY)
                CanvasTagKind.QUESTION -> drawQuestion(canvas, tag.documentX, tag.documentY)
            }
            if (focusedIndex == index) {
                canvas.drawCircle(tag.documentX, tag.documentY, dp(14f), focusPaint)
            }
        }
        canvas.restore()
    }

    private fun drawStar(canvas: Canvas, centerX: Float, centerY: Float) {
        val outer = dp(9f)
        val inner = dp(3.8f)
        val path = Path()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = -Math.PI / 2 + point * Math.PI / 5
            val x = centerX + cos(angle).toFloat() * radius
            val y = centerY + sin(angle).toFloat() * radius
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, tagPaint)
    }

    private fun drawQuestion(canvas: Canvas, centerX: Float, centerY: Float) {
        tagPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        tagPaint.textAlign = Paint.Align.CENTER
        tagPaint.textSize = dp(21f)
        val baseline = centerY - (tagPaint.ascent() + tagPaint.descent()) * 0.5f
        canvas.drawText("?", centerX, baseline, tagPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        val TAG_RED: Int = Color.rgb(232, 49, 57)
    }
}
