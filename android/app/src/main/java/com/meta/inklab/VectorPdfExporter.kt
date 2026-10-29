package com.meta.inklab

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlin.math.roundToInt

data class VectorPdfSource(val settings: PageViewSettings, val content: List<StrokeViewportBounds>, val originalSize: PdfOutputPageSize?,
    val render: (Canvas, Matrix, ExportOptions) -> Unit)
data class VectorPdfPage(val sheet: ExportSheet, val backgroundArgb: Int = android.graphics.Color.WHITE, val render: (Canvas, Matrix) -> Unit)

object VectorPdfExporter {
    fun capturePages(plan: ExportPlan, provider: ExportPageProvider, options: ExportOptions,
        indices: List<Int> = plan.pages.indices.toList(), cancelled: () -> Boolean = { false }): DiskBackedPdfDocument {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "逐页导出必须在工作线程启动" }
        require(indices.isNotEmpty()) { "当前范围没有页面" }
        require(indices.all { it in plan.pages.indices }) { "输出页码越界" }
        val document = DiskBackedPdfDocument(provider.cacheDirectory, cancelled)
        try {
            forEachPage(plan, provider, options, indices, cancelled) { _, item ->
                val singlePage = PdfDocument()
                try {
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    val sheet = item.sheet
                    val page = singlePage.startPage(PdfDocument.PageInfo.Builder(
                        sheet.widthPoints.roundToInt().coerceAtLeast(1),
                        sheet.heightPoints.roundToInt().coerceAtLeast(1), 1).create())
                    try { renderPage(page.canvas, item) } finally { singlePage.finishPage(page) }
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    document.pageFile.outputStream().use(singlePage::writeTo)
                } finally { singlePage.close() }
                document.appendPage()
            }
            return document
        } catch (error: Throwable) {
            document.close()
            throw error
        }
    }

    fun forEachPage(plan: ExportPlan, provider: ExportPageProvider, options: ExportOptions,
        indices: List<Int> = plan.pages.indices.toList(), cancelled: () -> Boolean = { false },
        render: (Int, VectorPdfPage) -> Unit) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "逐页准备必须在工作线程启动" }
        require(indices.all { it in plan.pages.indices }) { "输出页码越界" }
        ExportPageTraversal.visit(indices, { plan.pages[it].sourceIndex }, provider::acquire, cancelled) { index, lease ->
            val page = plan.pages[index]
            render(index, VectorPdfPage(page.sheet, page.backgroundArgb) { canvas, matrix ->
                lease.source.render(canvas, matrix, options)
            })
        }
    }

    fun pages(sources: List<VectorPdfSource>, options: ExportOptions, outputSize: PdfOutputPageSize? = null): List<VectorPdfPage> {
        val pages = sources.flatMap { source ->
            val paper=source.settings.pageColor.displayArgb(source.settings.darkBackground)
            val color=if(options.printing && source.settings.darkBackground) OutputAppearance.printPaper(paper) else paper
            ExportLayout.plan(source.settings, source.content, options, source.originalSize, outputSize).map { sheet -> VectorPdfPage(sheet,color) { canvas,matrix -> source.render(canvas,matrix,options) } }
        }
        return ExportLayout.selectPages(options.pageRange, pages.size).map(pages::get)
    }

    fun capturePages(pages: List<VectorPdfPage>): PdfDocument {
        require(pages.isNotEmpty()) { "当前范围没有页面" }
        val document = PdfDocument()
        var active: PdfDocument.Page? = null
        try {
            pages.forEachIndexed { index, item ->
                val sheet = item.sheet
                val width = sheet.widthPoints.roundToInt().coerceAtLeast(1)
                val height = sheet.heightPoints.roundToInt().coerceAtLeast(1)
                val page = document.startPage(PdfDocument.PageInfo.Builder(width,height,index+1).create())
                active = page
                renderPage(page.canvas, item)
                document.finishPage(page)
                active = null
            }
            return document
        } catch (error: Throwable) {
            active?.let { runCatching { document.finishPage(it) } }
            document.close()
            throw error
        }
    }

    fun renderPage(canvas: Canvas, page: VectorPdfPage) {
        val sheet = page.sheet
        canvas.drawColor(page.backgroundArgb)
        canvas.save()
        canvas.clipRect(sheet.marginPoints,sheet.marginPoints,sheet.widthPoints-sheet.marginPoints,sheet.heightPoints-sheet.marginPoints)
        val matrix = Matrix().apply { setValues(floatArrayOf(sheet.scale,0f,sheet.marginPoints-sheet.source.left*sheet.scale,
            0f,sheet.scale,sheet.marginPoints-sheet.source.top*sheet.scale,0f,0f,1f)) }
        val clip = RectF(sheet.source.left,sheet.source.top,sheet.source.right,sheet.source.bottom)
        matrix.mapRect(clip)
        canvas.clipRect(clip)
        page.render(canvas,matrix)
        canvas.restore()
    }
    /** Native PdfDocument records paths/text; callers must draw ink outlines rather than bitmaps. */
    fun capture(bounds: RectF, size: PdfOutputPageSize?, render: (Canvas, Matrix) -> Unit): PdfDocument {
        require(bounds.width() > 0f && bounds.height() > 0f)
        val width = (size?.widthPoints ?: bounds.width() / DOCUMENT_UNITS_PER_PDF_POINT).roundToInt().coerceAtLeast(1)
        val height = (size?.heightPoints ?: bounds.height() / DOCUMENT_UNITS_PER_PDF_POINT).roundToInt().coerceAtLeast(1)
        val document = PdfDocument()
        var activePage: PdfDocument.Page? = null
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            activePage = page
            val matrix = Matrix().apply {
                val sx = width / bounds.width()
                val sy = height / bounds.height()
                setValues(floatArrayOf(sx, 0f, -bounds.left * sx, 0f, sy, -bounds.top * sy, 0f, 0f, 1f))
            }
            render(page.canvas, matrix)
            document.finishPage(page)
            activePage = null
            return document
        } catch (error: Throwable) {
            // A started page must be finished before the native PDF document can close.
            activePage?.let { runCatching { document.finishPage(it) } }
            document.close()
            throw error
        }
    }
}
