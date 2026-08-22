package com.meta.inklab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

enum class PageSurfaceLayer {
    BACKGROUND,
    FOREGROUND,
}

/** Draws the page beneath the document layers and masks finite paper above them. */
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

        if (layer == PageSurfaceLayer.BACKGROUND) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), workspacePaint)
            canvas.save()
            canvas.concat(viewport.documentToViewMatrix())
            canvas.drawRect(documentPageRect(), pagePaint)
            canvas.restore()
            return
        }

        val mappedPage = documentPageRect()
        viewport.documentToViewMatrix().mapRect(mappedPage)
        maskOutsidePage(canvas, mappedPage)
        canvas.drawRect(mappedPage, borderPaint)

        if (settings.pageTitleVisible && pageTitle.isNotBlank()) {
            canvas.save()
            canvas.concat(viewport.documentToViewMatrix())
            canvas.clipRect(documentPageRect())
            canvas.drawText(
                pageTitle,
                TITLE_LEFT_MM * DOCUMENT_UNITS_PER_MM,
                TITLE_BASELINE_MM * DOCUMENT_UNITS_PER_MM,
                titlePaint,
            )
            canvas.restore()
        }
    }

    private fun documentPageRect(): RectF {
        val documentHeight = settings.paperSize.documentHeight
        if (documentHeight != null) {
            return RectF(0f, 0f, settings.paperSize.documentWidth, documentHeight)
        }
        val inverse = Matrix()
        val visibleBottom =
            if (viewport.documentToViewMatrix().invert(inverse)) {
                val visible = RectF(0f, 0f, width.toFloat(), height.toFloat()).also(inverse::mapRect)
                visible.bottom.coerceAtLeast(0f) + height / viewport.scale
            } else {
                height.toFloat()
            }
        return RectF(0f, 0f, settings.paperSize.documentWidth, visibleBottom)
    }

    private fun maskOutsidePage(
        canvas: Canvas,
        page: RectF,
    ) {
        canvas.drawRect(0f, 0f, page.left.coerceAtLeast(0f), height.toFloat(), workspacePaint)
        canvas.drawRect(page.right.coerceAtMost(width.toFloat()), 0f, width.toFloat(), height.toFloat(), workspacePaint)
        canvas.drawRect(page.left, 0f, page.right, page.top.coerceAtLeast(0f), workspacePaint)
        if (settings.paperSize.documentHeight != null) {
            canvas.drawRect(page.left, page.bottom.coerceAtMost(height.toFloat()), page.right, height.toFloat(), workspacePaint)
        }
    }

    companion object {
        private const val WORKSPACE_COLOR = 0xFF111111.toInt()
        private const val TITLE_LEFT_MM = 18f
        private const val TITLE_BASELINE_MM = 22f
        private const val TITLE_SIZE_MM = 5.2f
    }
}
