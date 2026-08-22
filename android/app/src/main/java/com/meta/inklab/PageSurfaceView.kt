package com.meta.inklab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

enum class PageSurfaceLayer {
    BACKGROUND,
    FOREGROUND,
}

/** Draws an unbounded canvas, a Word-like A4 stack, or one fixed-size imported PDF page. */
@SuppressLint("ViewConstructor")
class PageSurfaceView(
    context: Context,
    private val viewport: ViewportState,
    private val layer: PageSurfaceLayer,
) : View(context) {
    private val workspacePaint = Paint().apply { color = WORKSPACE_COLOR }
    private val pagePaint = Paint()
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
    private val documentPageRect = RectF()
    private val nextMappedPageRect = RectF()
    private var settings = PageViewSettings()
    private var pageTitle = ""

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    fun updatePage(
        pageTitle: String,
        settings: PageViewSettings,
    ) {
        this.pageTitle = pageTitle
        this.settings = settings
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val pageColor = settings.pageColor.displayArgb(settings.darkBackground)
        pagePaint.color = pageColor
        workspacePaint.color = WORKSPACE_COLOR
        borderPaint.color = if (settings.darkBackground) 0xFF4B4B4B.toInt() else 0xFFB8B8B8.toInt()
        titlePaint.color = if (settings.darkBackground) 0xFFEFEFEF.toInt() else 0xFF202020.toInt()
        titlePaint.textSize = TITLE_SIZE_MM * DOCUMENT_UNITS_PER_MM

        if (settings.paperSize == PaperSize.BOUNDLESS) {
            drawBoundless(canvas)
            return
        }

        val pageCount = if (settings.paperSize == PaperSize.A4) settings.a4PageCount.coerceAtLeast(1) else 1
        val documentToView = viewport.documentToViewMatrix()
        if (layer == PageSurfaceLayer.BACKGROUND) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), workspacePaint)
            canvas.save()
            canvas.concat(documentToView)
            for (index in 0 until pageCount) {
                setDocumentPageRect(index, documentPageRect)
                canvas.drawRect(documentPageRect, pagePaint)
            }
            canvas.restore()
            return
        }

        for (index in 0 until pageCount) {
            mapPageRect(index, documentToView, nextMappedPageRect)
            canvas.drawRect(nextMappedPageRect, borderPaint)
        }

        if (settings.pageTitleVisible && pageTitle.isNotBlank()) {
            canvas.save()
            canvas.concat(documentToView)
            setDocumentPageRect(0, documentPageRect)
            canvas.clipRect(documentPageRect)
            canvas.drawText(
                pageTitle,
                TITLE_LEFT_MM * DOCUMENT_UNITS_PER_MM,
                TITLE_BASELINE_MM * DOCUMENT_UNITS_PER_MM,
                titlePaint,
            )
            canvas.restore()
        }
    }

    private fun drawBoundless(canvas: Canvas) {
        if (layer == PageSurfaceLayer.BACKGROUND) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pagePaint)
            return
        }
        if (settings.pageTitleVisible && pageTitle.isNotBlank()) {
            canvas.save()
            canvas.concat(viewport.documentToViewMatrix())
            canvas.drawText(
                pageTitle,
                TITLE_LEFT_MM * DOCUMENT_UNITS_PER_MM,
                TITLE_BASELINE_MM * DOCUMENT_UNITS_PER_MM,
                titlePaint,
            )
            canvas.restore()
        }
    }

    private fun setDocumentPageRect(
        index: Int,
        output: RectF,
    ) {
        if (settings.paperSize == PaperSize.A4) {
            output.set(0f, A4PageLayout.pageTop(index), A4PageLayout.pageWidth, A4PageLayout.pageBottom(index))
        } else {
            output.set(
                0f,
                0f,
                settings.documentWidth?.coerceAtLeast(1f) ?: A4PageLayout.pageWidth,
                settings.documentHeight?.coerceAtLeast(1f) ?: A4PageLayout.pageHeight,
            )
        }
    }

    private fun mapPageRect(
        index: Int,
        documentToView: android.graphics.Matrix,
        output: RectF,
    ) {
        setDocumentPageRect(index, output)
        documentToView.mapRect(output)
    }

    companion object {
        private const val WORKSPACE_COLOR = 0xFF0A0A0A.toInt()
        private const val TITLE_LEFT_MM = 18f
        private const val TITLE_BASELINE_MM = 22f
        private const val TITLE_SIZE_MM = 5.2f
    }
}
