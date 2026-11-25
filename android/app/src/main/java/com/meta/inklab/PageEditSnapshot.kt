package com.meta.inklab

import android.text.Html
import android.text.SpannableString
import androidx.ink.strokes.Stroke

/** Immutable ink is shared; small mutable objects and rich text get independent versions. */
internal data class PageEditSnapshot(
    val ink: List<Stroke>,
    val tags: List<CanvasTagSnapshot>,
    val text: List<HistoryText>,
    val objects: List<InsertObject>,
    val settings: PageViewSettings,
    val tagSource: String,
    val textSource: String,
    val objectSource: String,
)

internal class HistoryText(val snapshot: CanvasTextSnapshot) {
    // Editor focus/selection spans must not create document edits; actual formatting must.
    private val key = snapshot.copy(text = Html.toHtml(SpannableString(snapshot.text), Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL))
    override fun equals(other: Any?): Boolean = other is HistoryText && key == other.key
    override fun hashCode(): Int = key.hashCode()
}
