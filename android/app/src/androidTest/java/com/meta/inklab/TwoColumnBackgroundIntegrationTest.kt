package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TwoColumnBackgroundIntegrationTest {
    @Test
    fun nativeBackgroundKeepsPaperCenterThroughCropsAndHidesPrintedDivider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val settings = PageViewSettings(
                paperSize = PaperSize.PDF, customDocumentWidth = 400f, customDocumentHeight = 300f,
                backgroundFormat = CanvasBackgroundFormat.RULE_TWO_COLUMN_STANDARD,
                pageTitleVisible = false,
            )
            val view = DryInkView(instrumentation.targetContext).apply { configureOutputPage(settings) }
            fun render(offset: Float = 0f, output: OutputAppearance = OutputAppearance()) =
                Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888).also {
                    view.renderBackgroundForExport(Canvas(it), Matrix().apply { setTranslate(-offset, 0f) }, output)
                }
            val whole = render()
            val crop = render(100f)
            val hidden = render(output = OutputAppearance(printing = true, hideBackgroundFormat = true))
            val native = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888)
            try {
                assertNotEquals(whole.getPixel(200, 10), whole.getPixel(210, 10))
                assertNotEquals(crop.getPixel(100, 10), crop.getPixel(110, 10))
                assertEquals(hidden.getPixel(200, 10), hidden.getPixel(210, 10))
                view.layout(0, 0, 300, 180)
                view.renderBackground(Canvas(native), listOf(RectF(0f, 0f, 400f, 300f)), Matrix())
                assertNotEquals(native.getPixel(200, 10), native.getPixel(210, 10))
            } finally { listOf(whole, crop, hidden, native).forEach(Bitmap::recycle) }
        }
    }
}
