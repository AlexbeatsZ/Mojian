package com.meta.inklab

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
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
import kotlin.math.roundToInt

enum class FormatPainterResult {
    CAPTURED,
    APPLIED,
    UNAVAILABLE,
}

data class CanvasTextSnapshot(
    val text: CharSequence,
    val documentX: Float,
    val documentY: Float,
    val width: Int,
    val height: Int,
    val contentScale: Float,
    val lineSpacingExtra: Float,
    val lineSpacingMultiplier: Float,
)

private data class TextBoxState(
    var documentX: Float,
    var documentY: Float,
    var contentScale: Float = 1f,
)

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

/** Page-owned rich text boxes plus uncommitted blank-click editors. */
class CanvasTextInputLayer(
    context: Context,
    private val viewport: ViewportState,
) : FrameLayout(context) {
    private var activeEditor: EditText? = null
    private var pageGestureActive = false
    private var capturedFormat: CapturedTextFormat? = null
    private var darkBackground = true
    private val selectedEditors = linkedSetOf<EditText>()
    private val temporaryEditors = linkedSetOf<EditText>()
    private val formalEditingEditors = linkedSetOf<EditText>()

    var onSelectionChanged: ((Int) -> Unit)? = null
    var onEditingChanged: ((Boolean) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null
    var onPageGesture: ((MotionEvent) -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }

    fun isEditing(): Boolean = activeEditor?.hasFocus() == true

    fun isEditingAt(viewX: Float, viewY: Float): Boolean =
        activeEditor?.takeIf { it.hasFocus() && it in formalEditingEditors }
            ?.let { editorAt(viewX, viewY) === it } == true

    fun isSelectedAt(viewX: Float, viewY: Float): Boolean = editorAt(viewX, viewY) in selectedEditors

    fun hasEditorAt(viewX: Float, viewY: Float): Boolean = editorAt(viewX, viewY) != null

    fun selectAt(viewX: Float, viewY: Float): Boolean {
        val editor = editorAt(viewX, viewY) ?: return false
        abandonTemporaryEditors(except = editor)
        endEditing(discardEmptyTemporary = false)
        selectOnly(editor)
        return true
    }

    fun editSelectedAt(viewX: Float, viewY: Float, showIme: Boolean): Boolean {
        val editor = editorAt(viewX, viewY)?.takeIf { it in selectedEditors } ?: return false
        formalEditingEditors += editor
        beginEditing(editor, showIme)
        return true
    }

    fun createTemporaryEditor(viewX: Float, viewY: Float) {
        abandonTemporaryEditors()
        endEditing(discardEmptyTemporary = false)
        clearSelection()
        addEditor(viewX, viewY, temporary = true)
    }

    fun abandonTemporaryEditors() = abandonTemporaryEditors(except = null)

    fun beginEditingSelectionAt(viewX: Float, viewY: Float): Boolean {
        val editor = editorAt(viewX, viewY)?.takeIf { it in selectedEditors }
            ?: selectedEditors.singleOrNull()
            ?: return false
        selectOnly(editor)
        onSelectionChanged?.invoke(1)
        beginEditing(editor, showIme = true)
        return true
    }

    fun endEditing(discardEmptyTemporary: Boolean = true) {
        val editor = activeEditor ?: return
        activeEditor = null
        editor.clearFocus()
        editor.isEnabled = false
        hideKeyboard()
        if (discardEmptyTemporary) discardTemporaryIfEmpty(editor)
        onEditingChanged?.invoke(false)
    }

    fun setDarkBackground(enabled: Boolean) {
        darkBackground = enabled
        forEachEditor(::applyDefaultEditorColors)
    }

    fun clearAll() {
        endEditing()
        removeAllViews()
        capturedFormat = null
        selectedEditors.clear()
        temporaryEditors.clear()
        formalEditingEditors.clear()
        onSelectionChanged?.invoke(0)
    }

    fun applyViewport() {
        forEachEditor(::applyEditorTransform)
        invalidate()
    }

    fun selectedCount(): Int = selectedEditors.size

    fun selectAll(notifySelectionChange: Boolean = true): Int {
        endEditing()
        selectedEditors.clear()
        forEachEditor { editor ->
            if (editor.text.isNotBlank()) selectedEditors += editor
        }
        refreshEditorInteraction()
        if (notifySelectionChange) onSelectionChanged?.invoke(selectedEditors.size)
        return selectedEditors.size
    }

    fun clearSelection() {
        if (selectedEditors.isEmpty()) return
        selectedEditors.clear()
        refreshEditorInteraction()
        onSelectionChanged?.invoke(0)
    }

    fun selectInPolygon(polygon: List<DocumentPoint>, notifySelectionChange: Boolean = true): Int {
        endEditing()
        selectedEditors.clear()
        if (polygon.size >= 3) {
            forEachEditor { editor ->
                val bounds = documentBounds(editor)
                if (
                    SelectionGeometry.polygonContainsRect(
                        polygon,
                        DocumentRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    )
                ) {
                    selectedEditors += editor
                }
            }
        }
        refreshEditorInteraction()
        if (notifySelectionChange) onSelectionChanged?.invoke(selectedEditors.size)
        return selectedEditors.size
    }

    fun selectedBounds(): RectF? =
        selectedEditors
            .map(::documentBounds)
            .reduceOrNull { combined, bounds -> combined.apply { union(bounds) } }

    fun translateSelection(deltaX: Float, deltaY: Float) {
        selectedEditors.forEach { editor ->
            state(editor).apply {
                documentX += deltaX
                documentY += deltaY
            }
            applyEditorTransform(editor)
        }
    }

    fun scaleSelection(anchorX: Float, anchorY: Float, factor: Float) {
        selectedEditors.forEach { editor ->
            state(editor).apply {
                val scaled = SelectionGeometry.scale(documentX, documentY, anchorX, anchorY, factor)
                documentX = scaled.x
                documentY = scaled.y
                contentScale = (contentScale * factor).coerceIn(0.2f, 8f)
            }
            applyEditorTransform(editor)
        }
    }

    fun deleteSelection(): Int {
        val count = selectedEditors.size
        if (count == 0) return 0
        selectedEditors.toList().forEach(::removeView)
        if (activeEditor in selectedEditors) activeEditor = null
        selectedEditors.clear()
        onSelectionChanged?.invoke(0)
        return count
    }

    fun contentBottom(): Float =
        (0 until childCount)
            .mapNotNull { index ->
                (getChildAt(index) as? EditText)
                    ?.takeUnless { it in temporaryEditors && it.text.isBlank() }
                    ?.let(::documentBounds)
                    ?.bottom
            }
            .maxOrNull()
            ?: 0f

    fun snapshotEditors(): List<CanvasTextSnapshot> =
        buildList {
            forEachEditor { editor ->
                if (editor in temporaryEditors && editor.text.isBlank()) return@forEachEditor
                val params = editor.layoutParams as LayoutParams
                val state = state(editor)
                add(
                    CanvasTextSnapshot(
                        text = SpannableString(editor.text),
                        documentX = state.documentX,
                        documentY = state.documentY,
                        width = editor.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(300),
                        height = editor.height.takeIf { it > 0 } ?: params.height.takeIf { it > 0 } ?: dp(32),
                        contentScale = state.contentScale,
                        lineSpacingExtra = editor.lineSpacingExtra,
                        lineSpacingMultiplier = editor.lineSpacingMultiplier,
                    ),
                )
            }
        }

    fun contentBounds(): Sequence<StrokeViewportBounds> =
        (0 until childCount).asSequence().mapNotNull { index ->
            (getChildAt(index) as? EditText)?.takeIf { it.text.isNotBlank() }?.let(::documentBounds)?.let {
                StrokeViewportBounds(it.left, it.top, it.right, it.bottom)
            }
        }

    fun renderForExport(canvas: android.graphics.Canvas, documentMatrix: android.graphics.Matrix) {
        canvas.save()
        canvas.concat(documentMatrix)
        forEachEditor { editor ->
            if (editor.text.isBlank()) return@forEachEditor
            val originalLayout = editor.layout ?: return@forEachEditor
            val exportText = SpannableString(editor.text)
            exportText.getSpans(0, exportText.length, ForegroundColorSpan::class.java).forEach { span ->
                val color = ExportColorTransform.lightThemePixel(span.foregroundColor)
                if (color != span.foregroundColor) {
                    val start = exportText.getSpanStart(span)
                    val end = exportText.getSpanEnd(span)
                    val flags = exportText.getSpanFlags(span)
                    exportText.removeSpan(span)
                    exportText.setSpan(ForegroundColorSpan(color), start, end, flags)
                }
            }
            val exportPaint = TextPaint(originalLayout.paint).apply { color = ExportColorTransform.lightThemePixel(color) }
            val textLayout = StaticLayout.Builder.obtain(exportText, 0, exportText.length, exportPaint, originalLayout.width)
                .setAlignment(originalLayout.alignment)
                .setIncludePad(editor.includeFontPadding)
                .setLineSpacing(editor.lineSpacingExtra, editor.lineSpacingMultiplier)
                .setBreakStrategy(editor.breakStrategy)
                .setHyphenationFrequency(editor.hyphenationFrequency)
                .build()
            val state = state(editor)
            canvas.save()
            canvas.translate(state.documentX, state.documentY)
            canvas.scale(state.contentScale, state.contentScale)
            canvas.translate(editor.compoundPaddingLeft.toFloat(), editor.compoundPaddingTop.toFloat())
            textLayout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    fun replaceEditors(snapshots: List<CanvasTextSnapshot>) {
        clearAll()
        snapshots.forEach { snapshot ->
            val editor = createEditor().apply {
                setText(snapshot.text)
                setLineSpacing(snapshot.lineSpacingExtra, snapshot.lineSpacingMultiplier)
                tag = TextBoxState(snapshot.documentX, snapshot.documentY, snapshot.contentScale)
            }
            addView(
                editor,
                LayoutParams(snapshot.width, snapshot.height),
            )
            editor.post { applyEditorTransform(editor) }
        }
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
        val fingerEvent = isFingerOnlyEvent(event)
        val mouseEvent = isMouseOnlyEvent(event)
        if (fingerEvent && (pageGestureActive || event.pointerCount > 1)) {
            if (!pageGestureActive) {
                pageGestureActive = true
                endEditing()
            }
            onPageGesture?.invoke(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pageGestureActive = false
            }
            return true
        }
        if (!fingerEvent && !mouseEvent) return false

        val editor = editorAt(event.x, event.y)
        return if (activeEditor != null && editor === activeEditor) super.dispatchTouchEvent(event) else false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        applyViewport()
    }

    private fun addEditor(touchX: Float, touchY: Float, temporary: Boolean) {
        val editorWidth = dp(300)
        val editor = createEditor()
        val point = viewport.viewToDocument(touchX - dp(2), touchY - dp(18))
        editor.tag = TextBoxState(point.x, point.y)
        addView(editor, LayoutParams(editorWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (temporary) temporaryEditors += editor
        selectOnly(editor)
        beginEditing(editor, showIme = false)
        editor.post {
            applyEditorTransform(editor)
        }
    }

    private fun createEditor(): EditText =
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
            isEnabled = false
            showSoftInputOnFocus = false
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(value: Editable?) {
                        if (this@apply in temporaryEditors && value?.any { !it.isWhitespace() } == true) {
                            temporaryEditors.remove(this@apply)
                            formalEditingEditors += this@apply
                        }
                        post { onContentChanged?.invoke() }
                    }
                },
            )
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) onContentChanged?.invoke()
            }
            setOnFocusChangeListener { view, focused ->
                if (focused) {
                    activeEditor = view as EditText
                    onEditingChanged?.invoke(true)
                } else if (activeEditor === view) {
                    activeEditor = null
                    view.isEnabled = false
                    discardTemporaryIfEmpty(view as EditText)
                    onEditingChanged?.invoke(false)
                }
            }
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

    private fun selectOnly(editor: EditText) {
        if (selectedEditors.size == 1 && editor in selectedEditors) return
        selectedEditors.clear()
        selectedEditors += editor
        refreshEditorInteraction()
        onSelectionChanged?.invoke(1)
    }

    private fun beginEditing(editor: EditText, showIme: Boolean) {
        if (activeEditor !== editor) activeEditor?.let { previous ->
            previous.clearFocus()
            previous.isEnabled = false
        }
        activeEditor = editor
        editor.isEnabled = true
        editor.showSoftInputOnFocus = showIme
        editor.requestFocus()
        if (showIme) editor.post { showKeyboard(editor) }
        onEditingChanged?.invoke(true)
    }

    private fun abandonTemporaryEditors(except: EditText?) {
        var removedActiveEditor = false
        temporaryEditors.toList().forEach { editor ->
            if (editor !== except && editor.text.isBlank()) {
                if (activeEditor === editor) {
                    activeEditor = null
                    removedActiveEditor = true
                }
                selectedEditors.remove(editor)
                temporaryEditors.remove(editor)
                formalEditingEditors.remove(editor)
                removeView(editor)
            }
        }
        refreshEditorInteraction()
        if (removedActiveEditor) {
            hideKeyboard()
            onEditingChanged?.invoke(false)
        }
        onSelectionChanged?.invoke(selectedEditors.size)
    }

    private fun discardTemporaryIfEmpty(editor: EditText) {
        if (editor !in temporaryEditors || editor.text.isNotBlank()) return
        temporaryEditors.remove(editor)
        formalEditingEditors.remove(editor)
        selectedEditors.remove(editor)
        removeView(editor)
        onSelectionChanged?.invoke(selectedEditors.size)
    }

    private fun refreshEditorInteraction() {
        forEachEditor { editor -> editor.isEnabled = editor === activeEditor }
    }

    private fun state(editor: EditText): TextBoxState =
        (editor.tag as? TextBoxState)
            ?: viewport.viewToDocument(editor.x, editor.y).let { point ->
                TextBoxState(point.x, point.y).also { editor.tag = it }
            }

    private fun documentBounds(editor: EditText): RectF {
        val params = editor.layoutParams as? LayoutParams
        val width = editor.width.takeIf { it > 0 } ?: params?.width?.takeIf { it > 0 } ?: dp(300)
        val height = editor.height.takeIf { it > 0 } ?: params?.height?.takeIf { it > 0 } ?: dp(32)
        val state = state(editor)
        return RectF(
            state.documentX,
            state.documentY,
            state.documentX + width * state.contentScale,
            state.documentY + height * state.contentScale,
        )
    }

    private fun applyEditorTransform(editor: EditText) {
        val state = state(editor)
        editor.pivotX = 0f
        editor.pivotY = 0f
        editor.scaleX = viewport.scale * state.contentScale
        editor.scaleY = viewport.scale * state.contentScale
        editor.x = viewport.translationX + state.documentX * viewport.scale
        editor.y = viewport.translationY + state.documentY * viewport.scale
    }

    private fun editorAt(x: Float, y: Float): EditText? {
        val point = viewport.viewToDocument(x, y)
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index) as? EditText ?: continue
            if (documentBounds(child).contains(point.x, point.y)) return child
        }
        return null
    }

    private fun isFingerOnlyEvent(event: MotionEvent): Boolean =
        InputModality.isFingerOnly(
            pointerToolTypes = (0 until event.pointerCount).map(event::getToolType),
            fingerToolType = MotionEvent.TOOL_TYPE_FINGER,
        )

    private fun isMouseOnlyEvent(event: MotionEvent): Boolean =
        InputModality.isMouseOnly(
            pointerToolTypes = (0 until event.pointerCount).map(event::getToolType),
            mouseToolType = MotionEvent.TOOL_TYPE_MOUSE,
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
