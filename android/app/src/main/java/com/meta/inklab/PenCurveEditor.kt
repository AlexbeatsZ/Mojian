package com.meta.inklab

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

@SuppressLint("ViewConstructor") // Created with an explicit curve model; never inflated from XML.
class PenCurveEditor(context: Context, initial: List<CurvePoint>, private val maximumX: Double, private val axis: String, private val changed: (List<CurvePoint>) -> Unit) : View(context) {
    private var points = initial.toList()
    private var selected = -1
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(x: Float) = x * density
    private val plot get() = RectF(dp(42f), dp(12f), (width - dp(16f)).coerceAtLeast(dp(43f)), (height - dp(26f)).coerceAtLeast(dp(13f)))
    private fun px(p: CurvePoint) = plot.left + (p.x / maximumX).toFloat() * plot.width()
    private fun py(p: CurvePoint) = plot.bottom - (p.y / 3).toFloat() * plot.height()
    init {
        isFocusable = true
        contentDescription = "$axis 曲线，点击添加控制点，拖动调整，删除按钮移除所选控制点"
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(27, 27, 29))
        val p = plot
        paint.strokeWidth = dp(1f); paint.color = Color.rgb(56, 56, 59)
        for (i in 0..4) canvas.drawLine(p.left + p.width() * i / 4, p.top, p.left + p.width() * i / 4, p.bottom, paint)
        for (i in 0..3) canvas.drawLine(p.left, p.bottom - p.height() * i / 3, p.right, p.bottom - p.height() * i / 3, paint)
        paint.color = Color.rgb(160, 160, 165); paint.textSize = dp(10f)
        for (i in 0..3) canvas.drawText("${i * 100}%", dp(3f), p.bottom - p.height() * i / 3 + dp(4f), paint)
        canvas.drawText("0", p.left, p.bottom + dp(17f), paint)
        canvas.drawText(if (maximumX == 1.0) "100" else maximumX.toInt().toString(), p.right - dp(20f), p.bottom + dp(17f), paint)
        canvas.drawText(axis, p.centerX() - dp(30f), p.bottom + dp(17f), paint)
        paint.color = Color.rgb(193, 163, 224); paint.strokeWidth = dp(2f)
        for (i in 1 until points.size) canvas.drawLine(px(points[i - 1]), py(points[i - 1]), px(points[i]), py(points[i]), paint)
        points.forEachIndexed { i, point ->
            paint.color = if (i == selected) Color.WHITE else Color.rgb(193, 163, 224)
            canvas.drawCircle(px(point), py(point), dp(if (i == selected) 5f else 3.5f), paint)
        }
    }
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!plot.contains(event.x, event.y)) return false
                parent.requestDisallowInterceptTouchEvent(true)
                selected = points.indices.minByOrNull { hypot(px(points[it]) - event.x, py(points[it]) - event.y) } ?: -1
                if (selected >= 0 && hypot(px(points[selected]) - event.x, py(points[selected]) - event.y) > dp(15f)) selected = -1
                if (selected < 0 && points.size < 33) {
                    val x = ((event.x - plot.left) / plot.width() * maximumX).coerceIn(.001 * maximumX, .999 * maximumX)
                    if (points.all { abs(it.x - x) > maximumX * .001 }) {
                        val point = CurvePoint(x, ((plot.bottom - event.y) / plot.height() * 3.0).coerceIn(0.0, 3.0))
                        points = (points + point).sortedBy { it.x }; selected = points.indexOf(point); changed(points)
                    }
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selected >= 0) {
                    val x = if (selected == 0 || selected == points.lastIndex) points[selected].x else
                        ((event.x - plot.left) / plot.width() * maximumX).coerceIn(points[selected - 1].x + maximumX * .0001, points[selected + 1].x - maximumX * .0001)
                    val y = ((plot.bottom - event.y) / plot.height() * 3.0).coerceIn(0.0, 3.0)
                    points = points.toMutableList().apply { set(selected, CurvePoint(round(x * 1e6) / 1e6, round(y * 1e4) / 1e4)) }
                    changed(points); invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        return false
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun deleteSelected() {
        if (selected <= 0 || selected >= points.lastIndex) return
        points = points.toMutableList().apply { removeAt(selected) }; selected = -1; changed(points); invalidate()
    }
}
