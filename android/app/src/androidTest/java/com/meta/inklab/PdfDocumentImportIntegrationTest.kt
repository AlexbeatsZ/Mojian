package com.meta.inklab

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfDocumentImportIntegrationTest {
    @Test fun nativeImporterKeepsOneOwnerAndBothOriginalSheetsWithEitherDisplayChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scratch = File(context.cacheDir, "clipboard/pdf-document-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(scratch, "lecture.pdf")
            val pdf = PdfDocument()
            try {
                for ((index, size) in listOf(200 to 300, 300 to 200).withIndex()) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(size.first, size.second, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawRect(10f, 10f, 100f, 100f, Paint().apply { color = if (index == 0) Color.RED else Color.BLUE })
                    pdf.finishPage(page)
                }
                source.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val original = source.readBytes()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
            for (inverted in listOf(false, true)) {
                val repository = PageContentRepository(File(scratch, "workspace-$inverted"))
                val result = PdfPageImporter(repository, AssetStore(context, repository)).importDocument("book", uri, inverted)
                assertEquals("lecture", result.page.title)
                assertEquals(2, result.sourcePageCount)
                val document = repository.load("book", result.page.id)
                val backgrounds = document.objects.filterIsInstance<InsertObject.Image>()
                assertEquals(listOf(1, 2), backgrounds.map { it.sourcePageNumber })
                assertTrue(backgrounds.all { it.pageBackground && it.viewInverted == inverted })
                val assets = repository.assetDirectory("book", result.page.id).listFiles()!!
                assertEquals(3, assets.size)
                assertArrayEquals(original, assets.single { it.extension == "pdf" }.readBytes())
                val session = ExportProductSession(context, repository, "book", listOf(ExportPageIdentity(
                    result.page.id, result.page.title, result.page.viewSettings, PdfOutputPageSize(200f, 300f))))
                try {
                    val plan = session.plan(ExportOptions())
                    assertEquals(2, plan.pages.size)
                    assertEquals(300f, plan.pages[1].sheet.widthPoints, .001f)
                    assertEquals(200f, plan.pages[1].sheet.heightPoints, .001f)
                } finally { session.cancel() }
            }
            assertArrayEquals(original, source.readBytes())
        } finally { scratch.deleteRecursively() }
    }
}
