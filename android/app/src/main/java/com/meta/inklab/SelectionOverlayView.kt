package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class SelectionOverlayView(
    context: Context,
    private val viewport: ViewportState,
) : View(context) {
    private enum class GestureMode {
        NONE,
        MOVE,
        SCALE,
    }

    var selectionEnabled = false
        set(value) {
            field = value
            if (!value) {
                gestureMode = GestureMode.NONE
                gestureCommitted = false
                pageGestureActive = false
            }
            invalidate()
        }
    var selectionVisible = true
        set(value) {
            field = value
            invalidate()
        }
    var selectionBoundsProvider: (() -> RectF?)? = null
    var onTranslateSelection: ((Float, Float) -> Unit)? = null
    var onScaleSelection: ((Float, Float, Float) -> Unit)? = null
    var onTransformFinished: (() -> Unit)? = null
    var onSelectionTapped: ((Float, Float) -> Unit)? = null
    var onPageGesture: ((MotionEvent) -> Unit)? = null

    private var gestureMode = GestureMode.NONE
    private var gestureCommitted = false
    private var pageGestureActive = false
    private var downPoint = DocumentPoint(0f, 0f)
    private var lastPoint = DocumentPoint(0f, 0f)
    private var scaleAnchor = DocumentPoint(0f, 0f)
    private var lastScaleDistance = 1f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val selectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 178, 233)
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
        }
    private val handleFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(dp(2f), 0f, dp(1f), Color.argb(90, 0, 0, 0))
        }
    private val handleStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 178, 233)
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false
        if (pageGestureActive || event.pointerCount > 1) {
            if (!pageGestureActive) {
                pageGestureActive = true
                gestureMode = GestureMode.NONE
                gestureCommitted = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            onPageGesture?.invoke(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pageGestureActive = false
            }
            return true
        }
        val point = viewport.viewToDocument(event.x, event.y).let { DocumentPoint(it.x, it.y) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = selectionBoundsProvider?.invoke() ?: return false
                val padding = dp(8f) / viewport.scale
                val displayBounds = RectF(bounds).apply { inset(-padding, -padding) }
                val corners = corners(displayBounds)
                val hitRadius = dp(24f) / viewport.scale
                val handleIndex =
                    corners.indices.minByOrNull { index ->
                        SelectionGeometry.squaredDistance(point.x, point.y, corners[index].x, corners[index].y)
                    }?.takeIf { index ->
                        SelectionGeometry.squaredDistance(point.x, point.y, corners[index].x, corners[index].y) <= hitRadius * hitRadius
                    }
                if (handleIndex != null) {
                    gestureMode = GestureMode.SCALE
                    scaleAnchor = corners[(handleIndex + 2) % 4]
                    lastScaleDistance = hypot(point.x - scaleAnchor.x, point.y - scaleAnchor.y).coerceAtLeast(0.001f)
                } else {
                    if (!displayBounds.contains(point.x, point.y)) return false
                    gestureMode = GestureMode.MOVE
                }
                gestureCommitted = false
                downPoint = point
                lastPoint = point
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureCommitted) {
                    val viewDistance = hypot(point.x - downPoint.x, point.y - downPoint.y) * viewport.scale
                    if (viewDistance < touchSlop) return true
                    gestureCommitted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (gestureMode) {
                    GestureMode.MOVE -> {
                        onTranslateSelection?.invoke(point.x - lastPoint.x, point.y - lastPoint.y)
                        lastPoint = point
                    }
                    GestureMode.SCALE -> {
                        val distance = hypot(point.x - scaleAnchor.x, point.y - scaleAnchor.y).coerceAtLeast(0.001f)
                        val factor = (distance / lastScaleDistance).coerceIn(0.25f, 4f)
                        onScaleSelection?.invoke(scaleAnchor.x, scaleAnchor.y, factor)
                        lastScaleDistance = distance
                        lastPoint = point
                    }
                    GestureMode.NONE -> return false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (gestureMode == GestureMode.NONE) return false
                if (gestureCommitted) {
                    onTransformFinished?.invoke()
                } else {
                    performClick()
                    onSelectionTapped?.invoke(event.x, event.y)
                }
                gestureMode = GestureMode.NONE
                gestureCommitted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gestureMode == GestureMode.NONE) return false
                val transformed = gestureCommitted
                gestureMode = GestureMode.NONE
                gestureCommitted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (transformed) onTransformFinished?.invoke()
                return true
            }
        }
        return gestureMode != GestureMode.NONE
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!selectionVisible) return
        val bounds = selectionBoundsProvider?.invoke() ?: return
        canvas.save()
        canvas.concat(viewport.documentToViewMatrix())
        selectionPaint.strokeWidth = dp(1.5f) / viewport.scale
        handleStrokePaint.strokeWidth = dp(1.5f) / viewport.scale
        val padding = dp(8f) / viewport.scale
        val displayBounds = RectF(bounds).apply { inset(-padding, -padding) }
        canvas.drawRect(displayBounds, selectionPaint)
        val handleRadius = dp(4.5f) / viewport.scale
        for (corner in corners(displayBounds)) {
            canvas.drawCircle(corner.x, corner.y, handleRadius, handleFillPaint)
            canvas.drawCircle(corner.x, corner.y, handleRadius, handleStrokePaint)
        }
        canvas.restore()
    }

    private fun corners(bounds: RectF): List<DocumentPoint> =
        listOf(
            DocumentPoint(bounds.left, bounds.top),
            DocumentPoint(bounds.right, bounds.top),
            DocumentPoint(bounds.right, bounds.bottom),
            DocumentPoint(bounds.left, bounds.bottom),
        )

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
