package com.meta.inklab

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Session-only text boxes placed directly on the visible page. */
class CanvasTextInputLayer(context: Context) : FrameLayout(context) {
    private var textModeEnabled = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = dp(8).toFloat()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }

    fun setTextMode(enabled: Boolean) {
        textModeEnabled = enabled
        forEachEditor { it.isEnabled = enabled }
        if (!enabled) {
            clearFocus()
            hideKeyboard()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        return editorAt(event.x, event.y) == null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (hypot(event.x - downX, event.y - downY) <= touchSlop) {
                    addEditor(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        forEachEditor { editor ->
            val params = editor.layoutParams as LayoutParams
            val editorWidth = if (editor.width > 0) editor.width else dp(260)
            val editorHeight = if (editor.height > 0) editor.height else dp(32)
            params.leftMargin = params.leftMargin.coerceIn(0, (w - editorWidth).coerceAtLeast(0))
            params.topMargin = params.topMargin.coerceIn(0, (h - editorHeight).coerceAtLeast(0))
            editor.layoutParams = params
        }
    }

    private fun addEditor(touchX: Float, touchY: Float) {
        val editorWidth = dp(260)
        val editor =
            EditText(context).apply {
                setTextColor(Color.rgb(239, 239, 239))
                setHintTextColor(Color.rgb(155, 155, 155))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                minHeight = dp(32)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSingleLine = false
                isFocusableInTouchMode = true
                setOnFocusChangeListener { view, focused ->
                    if (focused && textModeEnabled) showKeyboard(view)
                }
            }
        val left = (touchX - dp(2)).roundToInt().coerceIn(0, (width - editorWidth).coerceAtLeast(0))
        val top = (touchY - dp(18)).roundToInt().coerceIn(0, (height - dp(40)).coerceAtLeast(0))
        addView(
            editor,
            LayoutParams(editorWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
            },
        )
        editor.requestFocus()
        editor.post { showKeyboard(editor) }
    }

    private fun editorAt(x: Float, y: Float): EditText? {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index) as? EditText ?: continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    private fun forEachEditor(action: (EditText) -> Unit) {
        for (index in 0 until childCount) (getChildAt(index) as? EditText)?.let(action)
    }

    private fun showKeyboard(view: View) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()
}
