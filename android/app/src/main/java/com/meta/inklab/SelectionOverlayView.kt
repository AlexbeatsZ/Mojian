package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
        MARQUEE,
    }

    var selectionEnabled = true
        set(value) {
            field = value
            if (!value) {
                cancelDirectGesture()
            }
            invalidate()
        }
    var selectionVisible = true
        set(value) {
            field = value
            invalidate()
        }
    var fingerDirectInputEnabled = true
    var selectionBoundsProvider: (() -> RectF?)? = null
    var onTranslateSelection: ((Float, Float) -> Unit)? = null
    var onScaleSelection: ((Float, Float, Float, Float) -> Unit)? = null
    var onTransformFinished: (() -> Unit)? = null
    var onBeginMove: (() -> Unit)? = null
    var onFinishMove: ((Boolean) -> Unit)? = null
    var drawMoveOriginal: ((Canvas) -> Unit)? = null
    private val moveCopy = SelectionMoveCopy()
    private var controlDown = false
    private var stylusMovePointerId: Int? = null
    private var stylusDeselectOnly = false
    var blankContentHitTest: ((Float, Float) -> Boolean)? = null
    var onPenDismissSelection: (() -> Unit)? = null

    /** The host claims pen selection moves before an editor or wet-ink child consumes DOWN. */
    fun handleStylusSelection(event: MotionEvent, canStart: Boolean): Boolean {
        if (stylusMovePointerId == null) {
            if (!canStart || !selectionEnabled || event.actionMasked != MotionEvent.ACTION_DOWN ||
                event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS) return false
            val point = viewport.viewToDocument(event.x, event.y)
            val bounds = selectionBoundsProvider?.invoke()?.let { RectF(it).apply {
                inset(-dp(8f) / viewport.scale, -dp(8f) / viewport.scale)
            } } ?: return false
            if (!bounds.contains(point.x, point.y)) {
                if (blankContentHitTest?.invoke(event.x, event.y) != true) return false
                // The complete contact only deselects. It cannot turn into ink after DOWN.
                stylusDeselectOnly = true
            }
            stylusMovePointerId = event.getPointerId(event.actionIndex)
            if (stylusDeselectOnly) onPenDismissSelection?.invoke()
        }
        val pointerIndex = event.findPointerIndex(stylusMovePointerId ?: return false)
        if (pointerIndex < 0 || event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS) return false
        if (event.pointerCount > 1) {
            cancelDirectGesture()
            return true
        }
        if (stylusDeselectOnly) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                stylusMovePointerId = null
                stylusDeselectOnly = false
            }
            return true
        }
        return onTouchEvent(event)
    }

    fun cancelStylusSelectionMove() {
        if (stylusMovePointerId != null) cancelDirectGesture()
    }

    fun updateControlKey(control: Boolean) {
        controlDown = control
        moveCopy.update(control)
        invalidate()
    }
    var onDirectTap: ((Float, Float, Boolean) -> Unit)? = null
    var onDirectLongPress: ((Float, Float) -> Boolean)? = null
    var onBeginMarquee: ((Float, Float) -> Unit)? = null
    var onExtendMarquee: ((Float, Float) -> Unit)? = null
    var onFinishMarquee: (() -> Unit)? = null
    var onCancelMarquee: (() -> Unit)? = null
    var selectedContentHitTest: ((Float, Float) -> Boolean)? = null
    var editingTextHitTest: ((Float, Float) -> Boolean)? = null

    private var gestureMode = GestureMode.NONE
    private var gestureCommitted = false
    private var downPoint = DocumentPoint(0f, 0f)
    private var downViewX = 0f
    private var downViewY = 0f
    private var lastPoint = DocumentPoint(0f, 0f)
    private var scaleAnchor = DocumentPoint(0f, 0f)
    private var resizeHandle = -1
    private var resizeStartBounds = DocumentRect(0f, 0f, 0f, 0f)
    private var resizeAppliedBounds = resizeStartBounds
    private var longPressTriggered = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    if (!gestureCommitted && onDirectLongPress?.invoke(event.x, event.y) == true) {
                        longPressTriggered = true
                        gestureMode = GestureMode.NONE
                    }
                }
            },
        )
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
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // Host takeovers cancel with any pointer count/tool; always stop the long-press timer.
            val active = gestureMode != GestureMode.NONE || longPressTriggered
            cancelDirectGesture()
            return active
        }
        if (!selectionEnabled) return false
        if (event.pointerCount > 1) return false
        val toolType = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE
        val isPenMove = toolType == MotionEvent.TOOL_TYPE_STYLUS &&
            stylusMovePointerId == event.getPointerId(event.actionIndex)
        if (!isFinger && !isMouse && !isPenMove) return false
        if (isMouse || isPenMove) updateControlKey(event.metaState and android.view.KeyEvent.META_CTRL_ON != 0)
        if (isFinger && !fingerDirectInputEnabled) return false
        if (isMouse && event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
            if (editingTextHitTest?.invoke(event.x, event.y) == true) return false
            return onDirectLongPress?.invoke(event.x, event.y) == true
        }
        val primaryPointer = isFinger || isPenMove || event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 || gestureMode != GestureMode.NONE
        if (!primaryPointer) return false
        if (!isPenMove) longPressDetector.onTouchEvent(event)
        val point = viewport.viewToDocument(event.x, event.y).let { DocumentPoint(it.x, it.y) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bounds = selectionBoundsProvider?.invoke()
                val displayBounds = bounds?.let { RectF(it).apply { inset(-dp(8f) / viewport.scale, -dp(8f) / viewport.scale) } }
                val handleIndex = if (!isPenMove && bounds != null && displayBounds != null) {
                    SelectionGeometry.hitSelectionHandle(point, bounds.documentRect(), displayBounds.documentRect(), dp(if (isFinger) 24f else 12f) / viewport.scale)
                        .takeIf { it >= 0 }
                } else null
                val intent =
                    DirectInputPolicy.pointerDownIntent(
                        pointerCount = 1,
                        primaryDirectPointer = primaryPointer,
                        editingTextHit = !isPenMove && editingTextHitTest?.invoke(event.x, event.y) == true,
                        selectedContentHit = isPenMove || selectedContentHitTest?.invoke(event.x, event.y) == true,
                        selectionHandleHit = handleIndex != null,
                        // The whole visible frame moves the selection, including gaps between strokes.
                        selectionFrameHit = displayBounds?.contains(point.x, point.y) == true,
                    )
                if (intent == DirectPointerDownIntent.NATIVE_TEXT_EDIT) return false
                if (intent == DirectPointerDownIntent.IGNORE) return false
                if (handleIndex != null && bounds != null) {
                    gestureMode = GestureMode.SCALE
                    resizeHandle = handleIndex
                    resizeStartBounds = bounds.documentRect()
                    resizeAppliedBounds = resizeStartBounds
                    scaleAnchor = SelectionGeometry.selectionHandles(resizeStartBounds)[(handleIndex + 4) % 8]
                } else if (intent == DirectPointerDownIntent.TRANSFORM_SELECTION) {
                    gestureMode = GestureMode.MOVE
                } else {
                    gestureMode = GestureMode.MARQUEE
                }
                gestureCommitted = false
                longPressTriggered = false
                downPoint = point
                downViewX = event.x
                downViewY = event.y
                lastPoint = point
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureCommitted) {
                    val viewDistance = hypot(point.x - downPoint.x, point.y - downPoint.y) * viewport.scale
                    if (viewDistance < touchSlop) return true
                    gestureCommitted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (gestureMode == GestureMode.MARQUEE) onBeginMarquee?.invoke(downViewX, downViewY)
                    if (gestureMode == GestureMode.MOVE) {
                        onBeginMove?.invoke()
                        moveCopy.begin(controlDown)
                    }
                }
                when (gestureMode) {
                    GestureMode.MOVE -> {
                        onTranslateSelection?.invoke(point.x - lastPoint.x, point.y - lastPoint.y)
                        lastPoint = point
                    }
                    GestureMode.SCALE -> {
                        val handle = SelectionGeometry.selectionHandles(resizeStartBounds)[resizeHandle]
                        val target = DocumentPoint(handle.x + point.x - downPoint.x, handle.y + point.y - downPoint.y)
                        val resized = SelectionGeometry.resizeBounds(resizeStartBounds, resizeHandle, target, dp(24f) / viewport.scale)
                        val factorX = (resized.right - resized.left) / (resizeAppliedBounds.right - resizeAppliedBounds.left)
                        val factorY = (resized.bottom - resized.top) / (resizeAppliedBounds.bottom - resizeAppliedBounds.top)
                        onScaleSelection?.invoke(scaleAnchor.x, scaleAnchor.y, factorX, factorY)
                        resizeAppliedBounds = resized
                        lastPoint = point
                    }
                    GestureMode.MARQUEE -> onExtendMarquee?.invoke(event.x, event.y)
                    GestureMode.NONE -> return false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (longPressTriggered) {
                    longPressTriggered = false
                    gestureMode = GestureMode.NONE
                    gestureCommitted = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (gestureMode == GestureMode.NONE) return false
                if (gestureCommitted) {
                    // The release position may be newer than the final move event.
                    if (gestureMode == GestureMode.MOVE) {
                        onTranslateSelection?.invoke(point.x - lastPoint.x, point.y - lastPoint.y)
                        onFinishMove?.invoke(moveCopy.finish(commit = true))
                    }
                    if (gestureMode == GestureMode.MARQUEE) onFinishMarquee?.invoke() else onTransformFinished?.invoke()
                } else if (!isPenMove) {
                    performClick()
                    onDirectTap?.invoke(event.x, event.y, isFinger)
                }
                gestureMode = GestureMode.NONE
                gestureCommitted = false
                stylusMovePointerId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return gestureMode != GestureMode.NONE || longPressTriggered
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
        if (moveCopy.preview) drawMoveOriginal?.invoke(canvas)
        selectionPaint.strokeWidth = dp(1.5f) / viewport.scale
        handleStrokePaint.strokeWidth = dp(1.5f) / viewport.scale
        val padding = dp(8f) / viewport.scale
        val displayBounds = RectF(bounds).apply { inset(-padding, -padding) }
        canvas.drawRect(displayBounds, selectionPaint)
        val handleHalf = dp(4.5f) / viewport.scale
        for (handle in handles(displayBounds)) {
            val rect = RectF(handle.x - handleHalf, handle.y - handleHalf, handle.x + handleHalf, handle.y + handleHalf)
            canvas.drawRect(rect, handleFillPaint)
            canvas.drawRect(rect, handleStrokePaint)
        }
        canvas.restore()
    }

    fun cancelDirectGesture() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        longPressDetector.onTouchEvent(cancel)
        cancel.recycle()
        val mode = gestureMode
        if (moveCopy.active) onFinishMove?.invoke(moveCopy.finish(commit = false))
        // A selection already moved/resized by one finger is kept and saved, not left half-applied.
        if (mode == GestureMode.MARQUEE) onCancelMarquee?.invoke()
        else if (mode != GestureMode.NONE && gestureCommitted) onTransformFinished?.invoke()
        gestureMode = GestureMode.NONE
        gestureCommitted = false
        stylusMovePointerId = null
        stylusDeselectOnly = false
        longPressTriggered = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun handles(bounds: RectF): List<DocumentPoint> = SelectionGeometry.selectionHandles(bounds.documentRect())

    private fun RectF.documentRect() = DocumentRect(left, top, right, bottom)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
