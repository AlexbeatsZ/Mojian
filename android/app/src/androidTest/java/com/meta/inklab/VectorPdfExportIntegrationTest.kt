package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorPdfExportIntegrationTest {
    @Test
    fun displayedExportAndPrintKeepSeparatePaperAndPatternAppearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val settings = PageViewSettings(pageTitleVisible = false, backgroundFormat = CanvasBackgroundFormat.GRID_STANDARD)
            val view = DryInkView(instrumentation.targetContext).apply { configureOutputPage(settings) }
            fun render(output: OutputAppearance) = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).also {
                view.renderBackgroundForExport(Canvas(it), Matrix(), output)
            }
            val shown = render(OutputAppearance())
            val patterned = render(OutputAppearance(printing = true))
            val hidden = render(OutputAppearance(printing = true, hideBackgroundFormat = true))
            val ignored = render(OutputAppearance(hideBackgroundFormat = true))
            try {
                assertEquals(PageColor.WHITE.darkArgb, shown.getPixel(10, 10))
                assertEquals(Color.WHITE, hidden.getPixel(10, 10))
                val pixels = IntArray(512 * 256)
                patterned.getPixels(pixels, 0, 512, 0, 0, 512, 256)
                assertTrue(pixels.any { it != Color.WHITE })
                hidden.getPixels(pixels, 0, 512, 0, 0, 512, 256)
                assertTrue(pixels.all { it == Color.WHITE })
                assertTrue(shown.sameAs(ignored))
                assertEquals(CanvasBackgroundFormat.GRID_STANDARD, settings.backgroundFormat)
            } finally { listOf(shown, patterned, hidden, ignored).forEach(Bitmap::recycle) }
        }
    }

    @Test
    fun nativeStrokeOutlinesBecomePdfPathsAndRenderBlackOnWhite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "vector-export-test.pdf")
        try {
            instrumentation.runOnMainSync {
                val view = DryInkView(context).apply { backgroundFormat = CanvasBackgroundFormat.BLANK }
                val stroke = ShapeStrokeCodec.create(listOf(ShapePoint(100f, 100f), ShapePoint(600f, 250f)), InkPalette.BLACK, 2f, 100, Color.rgb(239, 239, 239))
                view.replaceStrokes(listOf(stroke))
                val pdf = VectorPdfExporter.capture(RectF(0f, 0f, 800f, 400f), null) { canvas, matrix ->
                    view.renderBackgroundForExport(canvas, matrix, OutputAppearance(printing = true))
                    view.renderVectorInkForExport(canvas, matrix, output = OutputAppearance(printing = true))
                }
                try { file.outputStream().use(pdf::writeTo) } finally { pdf.close() }
            }
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("Ink must not become an image XObject", bytes.contains("/Subtype /Image"))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(800 * 400)
                        bitmap.getPixels(pixels, 0, 800, 0, 0, 800, 400)
                        assertTrue(pixels.any { Color.red(it) < 30 && Color.green(it) < 30 && Color.blue(it) < 30 })
                        assertTrue(pixels.any { it == Color.WHITE })
                        bitmap.recycle()
                    }
                }
            }
        } finally { file.delete() }
    }
}
