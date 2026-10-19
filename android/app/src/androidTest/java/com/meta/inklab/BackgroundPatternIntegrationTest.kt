package com.meta.inklab

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class BackgroundPatternIntegrationTest {
    @Test fun densityZoomClipAndOutputKeepTheSameCanonicalPattern() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val toShared = SharedWorkspaceCodec.TO_SHARED.toFloat()
            for (format in CanvasBackgroundFormat.entries) for (dark in listOf(false, true)) {
                var baseline: IntArray? = null
                for (density in listOf(160, 320, 480)) {
                    val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { densityDpi = density })
                    val settings = PageViewSettings(backgroundFormat = format, darkBackground = dark, pageTitleVisible = false)
                    val view = DryInkView(configured).apply { configureOutputPage(settings); layout(0, 0, 320, 240) }
                    val matrix = Matrix().apply { setScale(toShared, toShared) }
                    val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                    try {
                        view.renderBackgroundForExport(Canvas(bitmap), matrix)
                        val pixels = IntArray(320 * 240).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 240) }
                        baseline?.let { assertArrayEquals("$format density=$density dark=$dark", it, pixels) }
                        baseline = pixels
                        val paper = bitmap.getPixel(7, 7)
                        if (format != CanvasBackgroundFormat.BLANK) {
                            val step = kotlin.math.round(format.pattern.spacing).toInt()
                            assertNotEquals(paper, bitmap.getPixel(step, step))
                        }
                        view.setViewport(Matrix().apply { setScale(4f, 4f); postTranslate(-40f, -70f) })
                        view.renderBackgroundForExport(Canvas(bitmap), matrix)
                        val afterZoom = IntArray(pixels.size).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 240) }
                        assertArrayEquals(pixels, afterZoom)
                        view.setViewport(matrix)
                        bitmap.eraseColor(settings.pageColor.displayArgb(dark))
                        view.renderBackground(Canvas(bitmap), listOf(RectF(0f, 0f, settings.documentWidth!!, settings.documentHeight!!)), matrix)
                        val live = IntArray(pixels.size).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 240) }
                        assertArrayEquals(pixels, live)
                        view.renderBackgroundForExport(Canvas(bitmap), matrix, OutputAppearance(printing = true, hideBackgroundFormat = true))
                        assertEquals(bitmap.getPixel(7, 7), bitmap.getPixel(64, 32))
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
