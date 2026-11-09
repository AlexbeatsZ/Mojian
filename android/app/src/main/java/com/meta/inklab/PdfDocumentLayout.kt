package com.meta.inklab

import java.util.UUID

data class ImportedPdfSheet(val pageNumber: Int, val widthPoints: Float, val heightPoints: Float, val renderedAssetName: String)
data class ImportedPdfNote(val page: NotePage, val document: PageInsertDocument)

/** One navigation entry and one original asset, with independently rendered immutable sheets. */
object PdfDocumentLayout {
    fun attach(library: NotebookLibrary, notebookId: String, sectionId: String, page: NotePage,
        publish: (NotebookLibrary) -> Unit): NotebookLibrary {
        val candidate = checkNotNull(NotebookLibrary.fromJson(library.toJsonString()))
        val notebook = candidate.notebooks.firstOrNull { it.id == notebookId }
        require(notebook != null && notebook.addPages(sectionId, listOf(page))) { "PDF 导入位置已不存在" }
        candidate.selectNotebook(notebookId)
        publish(candidate)
        return candidate
    }

    fun create(notebookId: String, pageId: String, sourceName: String, sourceAssetName: String,
        sheets: List<ImportedPdfSheet>, inverted: Boolean): ImportedPdfNote {
        require(sheets.isNotEmpty()) { "PDF 没有可导入的页面" }
        require(sheets.all { it.widthPoints.isFinite() && it.widthPoints > 0 && it.heightPoints.isFinite() && it.heightPoints > 0 }) {
            "PDF 页面尺寸无效"
        }
        val title = sourceName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").ifBlank { "PDF" }
        var y = 0f
        val objects = sheets.mapTo(mutableListOf<InsertObject>()) { sheet ->
            val height = sheet.heightPoints * DOCUMENT_UNITS_PER_PDF_POINT
            InsertObject.Image(id = "pdf-background-${UUID.randomUUID()}", documentX = 0f, documentY = y,
                width = sheet.widthPoints * DOCUMENT_UNITS_PER_PDF_POINT, height = height,
                assetName = sheet.renderedAssetName, displayName = "$sourceName · ${sheet.pageNumber}/${sheets.size}",
                mimeType = "image/png", sourceKind = ImageSourceKind.PDF_PAGE, viewInverted = inverted,
                sourceAssetName = sourceAssetName, sourcePageNumber = sheet.pageNumber, pageBackground = true)
                .also { y += height }
        }
        val first = sheets.first()
        return ImportedPdfNote(
            NotePage(id = pageId, title = title, subtitle = "${sheets.size} 页 PDF",
                viewSettings = PageViewSettings(paperSize = PaperSize.PDF, pageColor = PageColor.WHITE,
                    backgroundFormat = CanvasBackgroundFormat.BLANK, pageTitleVisible = false, darkBackground = false,
                    customDocumentWidth = sheets.maxOf { it.widthPoints } * DOCUMENT_UNITS_PER_PDF_POINT,
                    customDocumentHeight = y),
                pdfPage = PdfPageInfo(pageId, sourceAssetName, first.renderedAssetName, sourceName,
                    first.pageNumber, sheets.size, first.widthPoints, first.heightPoints)),
            PageInsertDocument(notebookId, pageId, objects = objects))
    }

    fun sheetBounds(objects: List<InsertObject>): List<StrokeViewportBounds> = objects.filterIsInstance<InsertObject.Image>()
        .filter { it.pageBackground && it.sourceKind == ImageSourceKind.PDF_PAGE }.sortedBy { it.documentY }
        .map { StrokeViewportBounds(it.documentX, it.documentY, it.documentX + it.width, it.documentY + it.height) }
}
