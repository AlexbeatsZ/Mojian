package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class PdfPageImportResult(val page: NotePage, val sourcePageCount: Int)

class PdfPageImporter(private val repository: PageContentRepository, private val assetStore: AssetStore) {
    fun importDocument(notebookId: String, uri: Uri, inverted: Boolean): PdfPageImportResult {
        val pageId = "pdf-page-${UUID.randomUUID()}"
        try {
            val source = assetStore.importUri(notebookId, pageId, uri)
            val sheets = mutableListOf<ImportedPdfSheet>()
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "PDF 没有可导入的页面" }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val assetName = "${UUID.randomUUID()}-pdf-page-${index + 1}.png"
                            renderPage(page, File(repository.assetDirectory(notebookId, pageId), assetName))
                            sheets += ImportedPdfSheet(index + 1, page.width.toFloat(), page.height.toFloat(), assetName)
                        }
                    }
                }
            }
            val note = PdfDocumentLayout.create(notebookId, pageId, source.displayName, source.storedName, sheets, inverted)
            repository.save(note.document)
            return PdfPageImportResult(note.page, sheets.size)
        } catch (error: Throwable) {
            repository.deletePageData(notebookId, pageId)
            throw error
        }
    }

    private fun renderPage(page: PdfRenderer.Page, destination: File) {
        val largestSide = max(page.width, page.height).coerceAtLeast(1)
        val scale = minOf(2f, MAX_RENDER_SIDE_PX / largestSide.toFloat())
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            destination.parentFile?.mkdirs()
            destination.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PDF 页面图像写入失败" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_RENDER_SIDE_PX = 2400f
    }
}
