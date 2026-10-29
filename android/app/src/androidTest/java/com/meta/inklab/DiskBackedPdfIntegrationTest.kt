package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class DiskBackedPdfIntegrationTest {
    @Test fun separateNativePagesMergeWithVectorsTextAndCorrectPhysicalOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context.applicationContext)
        val root = File(context.cacheDir, "pdf-merge-test-${System.nanoTime()}").apply { mkdirs() }
        val output = File(root, "result.pdf")
        val cache = File(root, "scratch").apply { mkdirs() }
        val sheet = ExportSheet(StrokeViewportBounds(0f, 0f, 300f, 400f), 300f, 400f, 1f, 0f)
        val plan = ExportPlan(List(32) { ExportPlannedPage(it, sheet, Color.WHITE) })
        var active = 0
        var maximum = 0
        val provider = object : ExportPageProvider {
            override val cacheDirectory = cache
            override fun plan(options: ExportOptions, outputSize: PdfOutputPageSize?) = plan
            override fun acquire(sourceIndex: Int): ExportPageLease {
                active++
                maximum = maxOf(maximum, active)
                return object : ExportPageLease {
                    override val source = VectorPdfSource(PageViewSettings(), emptyList(), null) { canvas, matrix, _ ->
                        canvas.save()
                        try {
                            canvas.concat(matrix)
                            val paint = Paint().apply { color = if (sourceIndex % 2 == 0) Color.RED else Color.BLUE }
                            canvas.drawRect(20f, 20f, 100f, 100f, paint)
                            paint.color = Color.BLACK
                            paint.textSize = 18f
                            canvas.drawText("Page $sourceIndex", 20f, 160f, paint)
                        } finally { canvas.restore() }
                    }
                    override fun close() { active-- }
                }
            }
        }
        try {
            VectorPdfExporter.capturePages(plan, provider, ExportOptions()).use { document ->
                output.outputStream().use(document::writeTo)
            }
            assertEquals(0, active)
            assertEquals(1, maximum)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(32, renderer.pageCount)
                repeat(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        assertEquals(300, page.width)
                        assertEquals(400, page.height)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            assertEquals(if (index % 2 == 0) Color.RED else Color.BLUE, bitmap.getPixel(50, 50))
                            assertTrue((145 until 162).any { row -> (20 until 110).any { column ->
                                bitmap.getPixel(column, row) != Color.WHITE
                            } })
                        } finally { bitmap.recycle() }
                    }
                }
            }
            try {
                VectorPdfExporter.capturePages(plan, provider, ExportOptions(), cancelled = { true }).close()
                fail("Cancelled export completed")
            } catch (_: CancellationException) {}
            assertEquals(0, active)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
