package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

data class CanvasTag(
    val kind: CanvasTagKind,
    val documentX: Float,
    val documentY: Float,
)

/** Session-only page tags placed in document coordinates. */
@SuppressLint("ViewConstructor")
class CanvasTagLayer(
    context: Context,
    private val viewport: ViewportState,
) : View(context) {
    private val tags = mutableListOf<CanvasTag>()
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

    var onPlacementFinished: ((CanvasTag) -> Unit)? = null

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
        cancelPlacement()
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
        val kind = pendingKind ?: return false
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
