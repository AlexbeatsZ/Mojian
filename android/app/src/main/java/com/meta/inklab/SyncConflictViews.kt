package com.meta.inklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

internal object ConflictColors {
    val REMOTE = ConflictMark.ADD.color
    val LOCAL = ConflictMark.DELETE.color
    val CHANGED = ConflictMark.MODIFY.color
    const val BAR = 0xF0262626.toInt()
    const val TEXT = 0xFFE8E8E8.toInt()
    const val BUTTON = 0xFF3A3A3A.toInt()
}

/**
 * Read-only diff over the canvas: strokes the server lacks tinted red, strokes changed on both sides
 * yellow, server-only strokes as a translucent green layer. Never consumes input.
 */
internal class ConflictPreviewLayer(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    private val documentToView = Matrix()
    private var remote: List<Stroke> = emptyList()
    private var local: List<Stroke> = emptyList()
    private var changed: List<Stroke> = emptyList()
    private val remotePaint = Paint().apply { colorFilter = PorterDuffColorFilter(ConflictColors.REMOTE, PorterDuff.Mode.SRC_IN); alpha = 210 }
    private val localPaint = Paint().apply { colorFilter = PorterDuffColorFilter(ConflictColors.LOCAL, PorterDuff.Mode.SRC_IN); alpha = 230 }
    private val changedPaint = Paint().apply { colorFilter = PorterDuffColorFilter(ConflictColors.CHANGED, PorterDuff.Mode.SRC_IN); alpha = 230 }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val active get() = remote.isNotEmpty() || local.isNotEmpty() || changed.isNotEmpty()

    fun show(remote: List<Stroke>, local: List<Stroke>, changed: List<Stroke>) {
        this.remote = remote
        this.local = local
        this.changed = changed
        invalidate()
    }

    fun clear() = show(emptyList(), emptyList(), emptyList())

    fun setViewport(matrix: Matrix) {
        documentToView.set(matrix)
        if (active) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        draw(canvas, local, localPaint)
        draw(canvas, changed, changedPaint)
        draw(canvas, remote, remotePaint)
    }

    private fun draw(canvas: Canvas, strokes: List<Stroke>, paint: Paint) {
        if (strokes.isEmpty()) return
        val save = canvas.saveLayer(null, paint)
        canvas.concat(documentToView)
        strokes.forEach { if (!SharedStrokeCodec.draw(canvas, it)) renderer.draw(canvas, it, documentToView) }
        canvas.restoreToCount(save)
    }
}

/** Top-of-canvas review bar: letter, counts, and the per-file choices. */
internal class ConflictReviewBar(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val letter = TextView(context)
    private val summary = TextView(context)
    private val keepLocal = button("保留本机")
    private val takeRemote = button("采用服务器")
    private val keepBoth = button("两边都要")
    var onChoice: ((NativeSyncChoice) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true // the bar sits on the canvas: touches on it must not draw
        setPadding(dp(10), dp(5), dp(6), dp(5))
        background = GradientDrawable().apply { setColor(ConflictColors.BAR); cornerRadius = dp(6).toFloat() }
        elevation = dp(3).toFloat()
        letter.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        summary.apply {
            setTextColor(ConflictColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        addView(letter, LayoutParams(dp(18), dp(24)).apply { marginEnd = dp(6) })
        addView(summary, LayoutParams(LayoutParams.WRAP_CONTENT, dp(24)).apply { marginEnd = dp(10) })
        listOf(keepLocal to NativeSyncChoice.LOCAL, takeRemote to NativeSyncChoice.REMOTE, keepBoth to NativeSyncChoice.BOTH).forEach { (view, choice) ->
            view.setOnClickListener { onChoice?.invoke(choice) }
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(4) })
        }
        summary.gravity = Gravity.CENTER_VERTICAL
    }

    fun bind(entry: ConflictEntry) {
        letter.text = entry.mark.letter
        letter.setTextColor(entry.mark.color)
        summary.text = SyncConflictReview.summaryText(entry.item.summary).ifEmpty { entry.label }
        keepBoth.visibility = if (entry.canKeepBoth) VISIBLE else GONE
        contentDescription = "同步冲突 ${entry.label} ${summary.text}"
    }

    fun setBusy(busy: Boolean) {
        listOf(keepLocal, takeRemote, keepBoth).forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.4f else 1f }
    }

    private fun button(label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(ConflictColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { setColor(ConflictColors.BUTTON); cornerRadius = dp(4).toFloat() }
        isClickable = true
        isFocusable = true
    }
}

/** Colored M/A/D letter at the right of a navigation row. */
internal fun conflictLetter(context: Context, mark: ConflictMark): TextView = TextView(context).apply {
    text = mark.letter
    gravity = Gravity.CENTER
    setTextColor(mark.color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    contentDescription = when (mark) { ConflictMark.MODIFY -> "冲突：两边都改过"; ConflictMark.ADD -> "冲突：服务器新增"; ConflictMark.DELETE -> "冲突：服务器已删除" }
}
