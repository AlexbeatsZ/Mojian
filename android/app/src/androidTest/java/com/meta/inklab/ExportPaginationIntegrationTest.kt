package com.meta.inklab

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Run only on an isolated device/package, never the sole session-bound tablet product. */
class ExportPaginationIntegrationTest {
    @Test fun vectorPaginationKeepsTwoSheetsAndUsesExportOrPrintPaperInTheMargins() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        for(printing in listOf(false,true)) {
            val file=File(context.cacheDir,"pagination-${printing}.pdf")
            try {
                instrumentation.runOnMainSync {
                    val settings=PageViewSettings(paperSize=PaperSize.A4,a4PageCount=8,pageTitleVisible=false,backgroundFormat=CanvasBackgroundFormat.BLANK)
                    val view=DryInkView(context).apply {
                        configureOutputPage(settings); setDarkBackground(true)
                        replaceStrokes(listOf(ShapeStrokeCodec.create(listOf(ShapePoint(100f,150f),ShapePoint(600f,200f)),InkPalette.BLACK,1f,100,0xFFEFEFEF.toInt()),
                            ShapeStrokeCodec.create(listOf(ShapePoint(100f,A4PageLayout.pageTop(1)+150f),ShapePoint(600f,A4PageLayout.pageTop(1)+200f)),InkPalette.RED,1f,100,Color.RED)))
                    }
                    val source=VectorPdfSource(settings,view.contentBounds().toList(),null) { canvas,matrix,options ->
                        val output=OutputAppearance(options.printing,options.invertedImageIds,options.hideBackgroundFormat)
                        view.renderBackgroundForExport(canvas,matrix,output)
                        view.renderVectorInkForExport(canvas,matrix,output=output)
                    }
                    val pages=VectorPdfExporter.pages(listOf(source),ExportOptions(printing=printing))
                    assertEquals(2,pages.size)
                    val pdf=VectorPdfExporter.capturePages(pages)
                    try { file.outputStream().use(pdf::writeTo) } finally { pdf.close() }
                    assertEquals(8,settings.a4PageCount)
                }
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertEquals(2,renderer.pageCount)
                    for(index in 0..1) renderer.openPage(index).use { page ->
                        assertEquals(595,page.width); assertEquals(842,page.height)
                        val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                        try { page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            assertEquals(if(printing) Color.WHITE else PageColor.WHITE.darkArgb,bitmap.getPixel(3,3))
                        } finally { bitmap.recycle() }
                    }
                }
            } finally { file.delete() }
        }
    }
}
