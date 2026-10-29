package com.meta.inklab

import android.graphics.Bitmap
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedPagePresentationIntegrationTest {
    @Test
    fun nativeBitmapKeepsBlackAndWhiteFormulaVisibleAndRetainsSourcePixels() {
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFF0000B8.toInt())
        source.setPixel(1, 1, 0xFF000000.toInt())
        source.setPixel(2, 1, 0xFFFFFFFF.toInt())
        val display = ImportedImageAppearance.adapt(source, true)
        try {
            assertEquals(0xFF1B1B1B.toInt(), display.getPixel(0, 0))
            assertEquals(0xFFFFFFFF.toInt(), display.getPixel(1, 1))
            assertEquals(0xFFFFFFFF.toInt(), display.getPixel(2, 1))
            assertEquals(0xFF0000B8.toInt(), source.getPixel(0, 0))
            assertEquals(0xFF000000.toInt(), source.getPixel(1, 1))
        } finally { display.recycle(); source.recycle() }
    }

    @Test
    fun displayOnlyTextSpansKeepOriginalColorAndHtmlAcrossThemeSwitches() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            val source = SpannableString("formula").apply {
                setSpan(ForegroundColorSpan(0xFF000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val expectedHtml = Html.toHtml(source, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
            layer.replaceEditors(listOf(CanvasTextSnapshot(source, 0f, 0f, 300, 60, contentScale = 1f,
                lineSpacingExtra = 0f, lineSpacingMultiplier = 1f, id = "text-notein-example")))
            try {
                for (dark in listOf(true, false, true)) {
                    layer.setDarkBackground(dark)
                    layer.configurePage(ImportedPagePresentation.display(PageViewSettings(darkBackground = dark,
                        sourcePageColor = 0xFFFFEABD.toInt()), "page-notein-example"))
                    val stored = layer.snapshotEditors().single().text as Spanned
                    assertEquals(expectedHtml, Html.toHtml(stored, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL))
                    val span = stored.getSpans(0, stored.length, ForegroundColorSpan::class.java).single()
                    assertEquals(0xFF000000.toInt(), span.foregroundColor)
                    val paint = TextPaint()
                    span.updateDrawState(paint)
                    assertEquals(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), paint.color)
                }
            } finally { layer.replaceEditors(emptyList()) }
        }
    }
}
