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

data class PdfPageImportResult(
    val pages: List<NotePage>,
    val sourceDocumentName: String,
)

class PdfPageImporter(
    private val repository: PageContentRepository,
    private val assetStore: AssetStore,
) {
    fun importPages(notebookId: String, uri: Uri): PdfPageImportResult {
        val allocatedPageIds = mutableListOf<String>()
        val sourceOwnerPageId = nextPageId().also(allocatedPageIds::add)
        try {
            val source = assetStore.importUri(notebookId, sourceOwnerPageId, uri)
            val pages = mutableListOf<NotePage>()
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "PDF 没有可导入的页面" }
                    val pageIds =
                        List(renderer.pageCount) { index ->
                            if (index == 0) sourceOwnerPageId else nextPageId().also(allocatedPageIds::add)
                        }
                    val baseTitle = source.displayName.withoutPdfSuffix().ifBlank { "PDF" }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val pageId = pageIds[index]
                            val pageNumber = index + 1
                            val widthPoints = page.width.toFloat().coerceAtLeast(1f)
                            val heightPoints = page.height.toFloat().coerceAtLeast(1f)
                            val documentWidth = widthPoints * DOCUMENT_UNITS_PER_PDF_POINT
                            val documentHeight = heightPoints * DOCUMENT_UNITS_PER_PDF_POINT
                            val renderedAssetName = "${UUID.randomUUID()}-pdf-page-$pageNumber.png"
                            // The canonical workspace resolves assets in each page's own directory.
                            if(pageId!=sourceOwnerPageId) source.file.copyTo(File(repository.assetDirectory(notebookId,pageId),source.storedName),overwrite=true)
                            renderPage(page, File(repository.assetDirectory(notebookId, pageId), renderedAssetName))

                            val pdfInfo =
                                PdfPageInfo(
                                    sourceOwnerPageId = pageId,
                                    sourceAssetName = source.storedName,
                                    renderedAssetName = renderedAssetName,
                                    sourceDocumentName = source.displayName,
                                    pageNumber = pageNumber,
                                    pageCount = renderer.pageCount,
                                    widthPoints = widthPoints,
                                    heightPoints = heightPoints,
                                )
                            val settings =
                                PageViewSettings(
                                    paperSize = PaperSize.PDF,
                                    pageColor = PageColor.WHITE,
                                    backgroundFormat = CanvasBackgroundFormat.BLANK,
                                    pageTitleVisible = false,
                                    darkBackground = false,
                                    a4PageCount = 1,
                                    customDocumentWidth = documentWidth,
                                    customDocumentHeight = documentHeight,
                                )
                            val title = if (renderer.pageCount == 1) baseTitle else "$baseTitle · $pageNumber"
                            pages +=
                                NotePage(
                                    id = pageId,
                                    title = title,
                                    subtitle = "$pageNumber / ${renderer.pageCount}",
                                    viewSettings = settings,
                                    pdfPage = pdfInfo,
                                )
                            repository.save(
                                PageInsertDocument(
                                    notebookId = notebookId,
                                    pageId = pageId,
                                    objects =
                                        mutableListOf(
                                            InsertObject.Image(
                                                id = "pdf-background-${UUID.randomUUID()}",
                                                documentX = 0f,
                                                documentY = 0f,
                                                width = documentWidth,
                                                height = documentHeight,
                                                assetName = renderedAssetName,
                                                displayName = "$title.pdf-page",
                                                mimeType = "image/png",
                                                sourceKind = ImageSourceKind.PDF_PAGE,
                                                viewInverted = false,
                                                sourceAssetName = source.storedName,
                                                sourcePageNumber = pageNumber,
                                                pageBackground = true,
                                            ),
                                        ),
                                ),
                            )
                        }
                    }
                }
            }
            return PdfPageImportResult(pages, source.displayName)
        } catch (error: Throwable) {
            allocatedPageIds.forEach { pageId -> repository.deletePageData(notebookId, pageId) }
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

    private fun nextPageId(): String = "pdf-page-${UUID.randomUUID()}"

    private fun String.withoutPdfSuffix(): String =
        if (endsWith(".pdf", ignoreCase = true)) dropLast(4) else this

    companion object {
        private const val MAX_RENDER_SIDE_PX = 2400f
    }
}
