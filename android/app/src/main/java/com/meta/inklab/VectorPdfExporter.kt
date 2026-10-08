package com.meta.inklab

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlin.math.roundToInt

object VectorPdfExporter {
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
