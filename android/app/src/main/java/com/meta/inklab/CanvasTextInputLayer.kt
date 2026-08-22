package com.meta.inklab

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ParagraphStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
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

enum class FormatPainterResult {
    CAPTURED,
    APPLIED,
    UNAVAILABLE,
}

private data class CapturedTextFormat(
    val typefaceFamily: String?,
    val textSizeSp: Int?,
    val style: Int?,
    val underline: Boolean,
    val strikethrough: Boolean,
    val foregroundColor: Int?,
    val backgroundColor: Int?,
    val subscript: Boolean,
)

/** Session-only rich text boxes placed directly on the visible page. */
class CanvasTextInputLayer(context: Context) : FrameLayout(context) {
    private var textModeEnabled = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = dp(8).toFloat()
    private var activeEditor: EditText? = null
    private var capturedFormat: CapturedTextFormat? = null
    private var darkBackground = true

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }

    fun setTextMode(enabled: Boolean) {
        textModeEnabled = enabled
        forEachEditor { it.isEnabled = enabled }
        if (!enabled) hideKeyboard()
    }

    fun setDarkBackground(enabled: Boolean) {
        darkBackground = enabled
        forEachEditor(::applyDefaultEditorColors)
    }

    fun clearAll() {
        removeAllViews()
        activeEditor = null
        capturedFormat = null
    }

    fun paste(): Boolean = contextAction(android.R.id.paste)

    fun cut(): Boolean = contextAction(android.R.id.cut)

    fun copy(): Boolean = contextAction(android.R.id.copy)

    fun toggleBold(): Boolean = toggleStyle(Typeface.BOLD)

    fun toggleItalic(): Boolean = toggleStyle(Typeface.ITALIC)

    fun toggleUnderline(): Boolean = toggleSimpleSpan(UnderlineSpan::class.java) { UnderlineSpan() }

    fun toggleStrikethrough(): Boolean = toggleSimpleSpan(StrikethroughSpan::class.java) { StrikethroughSpan() }

    fun toggleSubscript(): Boolean = toggleSimpleSpan(SubscriptSpan::class.java) { SubscriptSpan() }

    fun applyFontFamily(family: String): Boolean =
        applyExclusiveCharacterSpan(TypefaceSpan::class.java) { TypefaceSpan(family) }

    fun applyFontSize(sizeSp: Int): Boolean =
        applyExclusiveCharacterSpan(AbsoluteSizeSpan::class.java) { AbsoluteSizeSpan(sizeSp, true) }

    fun applyForegroundColor(color: Int): Boolean =
        applyExclusiveCharacterSpan(ForegroundColorSpan::class.java) { ForegroundColorSpan(color) }

    fun applyBackgroundColor(color: Int): Boolean =
        applyExclusiveCharacterSpan(BackgroundColorSpan::class.java) { BackgroundColorSpan(color) }

    fun clearFormatting(): Boolean =
        withSelection(paragraph = false) { editor, start, end ->
            editor.text.getSpans(start, end, Any::class.java)
                .filter { it is CharacterStyle || it is ParagraphStyle }
                .forEach(editor.text::removeSpan)
        }

    fun formatPainter(): FormatPainterResult {
        val editor = currentEditor() ?: return FormatPainterResult.UNAVAILABLE
        val range = characterRange(editor) ?: return FormatPainterResult.UNAVAILABLE
        val saved = capturedFormat
        if (saved == null) {
            capturedFormat = captureFormat(editor.text, range.first)
            return FormatPainterResult.CAPTURED
        }
        applyCapturedFormat(editor.text, range.first, range.last + 1, saved)
        capturedFormat = null
        return FormatPainterResult.APPLIED
    }

    fun applyBullets(): Boolean = transformSelectedParagraphs { lines -> lines.map { "• ${stripListPrefix(it)}" } }

    fun applyNumbering(): Boolean =
        transformSelectedParagraphs { lines -> lines.mapIndexed { index, line -> "${index + 1}. ${stripListPrefix(line)}" } }

    fun changeIndent(increase: Boolean): Boolean =
        withSelection(paragraph = true) { editor, start, end ->
            val existing = editor.text.getSpans(start, end, LeadingMarginSpan::class.java)
            val current = existing.maxOfOrNull { it.getLeadingMargin(true) } ?: 0
            existing.forEach(editor.text::removeSpan)
            val next = (current + if (increase) dp(18) else -dp(18)).coerceAtLeast(0)
            if (next > 0) editor.text.setSpan(LeadingMarginSpan.Standard(next), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    fun align(alignment: Layout.Alignment): Boolean =
        withSelection(paragraph = true) { editor, start, end ->
            editor.text.getSpans(start, end, AlignmentSpan::class.java).forEach(editor.text::removeSpan)
            editor.text.setSpan(AlignmentSpan.Standard(alignment), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    fun setLineSpacing(multiplier: Float): Boolean {
        val editor = currentEditor() ?: return false
        editor.setLineSpacing(0f, multiplier)
        return true
    }

    fun applyNamedStyle(style: String): Boolean =
        withSelection(paragraph = true) { editor, start, end ->
            editor.text.getSpans(start, end, AbsoluteSizeSpan::class.java).forEach(editor.text::removeSpan)
            editor.text.getSpans(start, end, StyleSpan::class.java).forEach(editor.text::removeSpan)
            val (size, typefaceStyle) =
                when (style) {
                    "标题" -> 28 to Typeface.BOLD
                    "标题 1" -> 22 to Typeface.BOLD
                    "标题 2" -> 18 to Typeface.BOLD
                    else -> 16 to Typeface.NORMAL
                }
            editor.text.setSpan(AbsoluteSizeSpan(size, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (typefaceStyle != Typeface.NORMAL) {
                editor.text.setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled || !isFingerEvent(event)) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled || !isFingerEvent(event)) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        return editorAt(event.x, event.y) == null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!textModeEnabled || !isFingerEvent(event)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (hypot(event.x - downX, event.y - downY) <= touchSlop) addEditor(event.x, event.y)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        forEachEditor { editor ->
            val params = editor.layoutParams as LayoutParams
            val editorWidth = if (editor.width > 0) editor.width else dp(300)
            val editorHeight = if (editor.height > 0) editor.height else dp(32)
            params.leftMargin = params.leftMargin.coerceIn(0, (w - editorWidth).coerceAtLeast(0))
            params.topMargin = params.topMargin.coerceIn(0, (h - editorHeight).coerceAtLeast(0))
            editor.layoutParams = params
        }
    }

    private fun addEditor(touchX: Float, touchY: Float) {
        val editorWidth = dp(300)
        val editor =
            EditText(context).apply {
                applyDefaultEditorColors(this)
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
                    if (focused) {
                        activeEditor = view as EditText
                        if (textModeEnabled) showKeyboard(view)
                    }
                }
            }
        val left = (touchX - dp(2)).roundToInt().coerceIn(0, (width - editorWidth).coerceAtLeast(0))
        val top = (touchY - dp(18)).roundToInt().coerceIn(0, (height - dp(40)).coerceAtLeast(0))
        addView(editor, LayoutParams(editorWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = left; topMargin = top })
        activeEditor = editor
        editor.requestFocus()
        editor.post { showKeyboard(editor) }
    }

    private fun currentEditor(): EditText? = activeEditor?.takeIf { it.parent === this && it.isEnabled }

    private fun contextAction(actionId: Int): Boolean = currentEditor()?.onTextContextMenuItem(actionId) ?: false

    private fun toggleStyle(style: Int): Boolean =
        withSelection(paragraph = false) { editor, start, end ->
            val matching = editor.text.getSpans(start, end, StyleSpan::class.java).filter { it.style == style }
            if (matching.isNotEmpty()) matching.forEach(editor.text::removeSpan)
            else editor.text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun <T : CharacterStyle> toggleSimpleSpan(type: Class<T>, create: () -> T): Boolean =
        withSelection(paragraph = false) { editor, start, end ->
            val existing = editor.text.getSpans(start, end, type)
            if (existing.isNotEmpty()) existing.forEach(editor.text::removeSpan)
            else editor.text.setSpan(create(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun <T : CharacterStyle> applyExclusiveCharacterSpan(type: Class<T>, create: () -> T): Boolean =
        withSelection(paragraph = false) { editor, start, end ->
            editor.text.getSpans(start, end, type).forEach(editor.text::removeSpan)
            editor.text.setSpan(create(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun withSelection(paragraph: Boolean, action: (EditText, Int, Int) -> Unit): Boolean {
        val editor = currentEditor() ?: return false
        val range = if (paragraph) paragraphRange(editor) else characterRange(editor)
        if (range == null) return false
        action(editor, range.first, range.last + 1)
        editor.requestFocus()
        return true
    }

    private fun characterRange(editor: EditText): IntRange? {
        val editable = editor.text
        if (editable.isEmpty()) return null
        val rawStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val rawEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        if (rawStart != rawEnd) return rawStart until rawEnd
        var start = rawStart.coerceAtMost(editable.length)
        var end = start
        while (start > 0 && !editable[start - 1].isWhitespace()) start--
        while (end < editable.length && !editable[end].isWhitespace()) end++
        return if (start == end) null else start until end
    }

    private fun paragraphRange(editor: EditText): IntRange? {
        val editable = editor.text
        if (editable.isEmpty()) return null
        var start = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        var end = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        while (start > 0 && editable[start - 1] != '\n') start--
        while (end < editable.length && editable[end] != '\n') end++
        if (end < editable.length) end++
        return start until end
    }

    private fun transformSelectedParagraphs(transform: (List<String>) -> List<String>): Boolean {
        val editor = currentEditor() ?: return false
        val range = paragraphRange(editor) ?: return false
        val start = range.first
        val end = range.last + 1
        val original = editor.text.substring(start, end)
        val trailingNewline = original.endsWith('\n')
        val lines = original.removeSuffix("\n").split('\n')
        var replacement = transform(lines).joinToString("\n")
        if (trailingNewline) replacement += "\n"
        editor.text.replace(start, end, replacement)
        editor.setSelection((start + replacement.length).coerceAtMost(editor.text.length))
        editor.requestFocus()
        return true
    }

    private fun stripListPrefix(line: String): String = line.replaceFirst(Regex("^(?:•\\s+|\\d+\\.\\s+)"), "")

    private fun captureFormat(text: Spannable, position: Int): CapturedTextFormat {
        val probe = position.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        fun <T> last(type: Class<T>): T? = text.getSpans(probe, (probe + 1).coerceAtMost(text.length), type).lastOrNull()
        return CapturedTextFormat(
            last(TypefaceSpan::class.java)?.family,
            last(AbsoluteSizeSpan::class.java)?.size,
            last(StyleSpan::class.java)?.style,
            last(UnderlineSpan::class.java) != null,
            last(StrikethroughSpan::class.java) != null,
            last(ForegroundColorSpan::class.java)?.foregroundColor,
            last(BackgroundColorSpan::class.java)?.backgroundColor,
            last(SubscriptSpan::class.java) != null,
        )
    }

    private fun applyCapturedFormat(text: Spannable, start: Int, end: Int, format: CapturedTextFormat) {
        text.getSpans(start, end, CharacterStyle::class.java).forEach(text::removeSpan)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        format.typefaceFamily?.let { text.setSpan(TypefaceSpan(it), start, end, flag) }
        format.textSizeSp?.let { text.setSpan(AbsoluteSizeSpan(it, true), start, end, flag) }
        format.style?.let { text.setSpan(StyleSpan(it), start, end, flag) }
        if (format.underline) text.setSpan(UnderlineSpan(), start, end, flag)
        if (format.strikethrough) text.setSpan(StrikethroughSpan(), start, end, flag)
        format.foregroundColor?.let { text.setSpan(ForegroundColorSpan(it), start, end, flag) }
        format.backgroundColor?.let { text.setSpan(BackgroundColorSpan(it), start, end, flag) }
        if (format.subscript) text.setSpan(SubscriptSpan(), start, end, flag)
    }

    private fun editorAt(x: Float, y: Float): EditText? {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index) as? EditText ?: continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    private fun isFingerEvent(event: MotionEvent): Boolean =
        InputModality.acceptsTextInput(
            pointerToolTypes = (0 until event.pointerCount).map(event::getToolType),
            fingerToolType = MotionEvent.TOOL_TYPE_FINGER,
        )

    private fun forEachEditor(action: (EditText) -> Unit) {
        for (index in 0 until childCount) (getChildAt(index) as? EditText)?.let(action)
    }

    private fun applyDefaultEditorColors(editor: EditText) {
        editor.setTextColor(if (darkBackground) Color.rgb(239, 239, 239) else Color.rgb(27, 27, 31))
        editor.setHintTextColor(if (darkBackground) Color.rgb(155, 155, 155) else Color.rgb(100, 100, 100))
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
