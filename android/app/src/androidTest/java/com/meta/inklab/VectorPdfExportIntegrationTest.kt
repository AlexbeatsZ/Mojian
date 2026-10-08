package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorPdfExportIntegrationTest {
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
                    view.renderBackgroundForExport(canvas, matrix)
                    view.renderVectorInkForExport(canvas, matrix)
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
