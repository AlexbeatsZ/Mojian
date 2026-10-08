package com.meta.inklab

import kotlin.math.ceil

enum class ExportScope(val label: String) { PAGE("页面"), SECTION("分区"), NOTEBOOK("笔记本") }
enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    PDF("PDF (*.pdf) · 矢量笔迹", "pdf", "application/pdf"),
    PACKAGE("墨笺可编辑包 (*.mjn)", "mjn", "application/zip"),
    PNG_ZIP("分页图片 (*.zip) · 200 DPI PNG", "zip", "application/zip"),
}
enum class ExportPaper(val label: String, val widthMm: Float, val heightMm: Float) {
    AUTO("自动（A4 / 原 PDF 尺寸）", 210f, 297f), A4("A4", 210f, 297f), A3("A3", 297f, 420f), LETTER("Letter", 215.9f, 279.4f),
}
data class ExportOptions(val scope: ExportScope = ExportScope.PAGE, val format: ExportFormat = ExportFormat.PDF,
    val paper: ExportPaper = ExportPaper.AUTO, val landscape: Boolean = false, val marginMm: Float = 6f,
    val trimTrailingBlank: Boolean = true, val pageRange: String = "", val printing: Boolean = false,
    val invertedImageIds: Set<String>? = null, val hideBackgroundFormat: Boolean = false)
data class ExportImageChoice(val id: String, val label: String, val displayInverted: Boolean)
data class ExportSheet(val source: StrokeViewportBounds, val widthPoints: Float, val heightPoints: Float, val scale: Float, val marginPoints: Float)

object ExportLayout {
    fun plan(settings: PageViewSettings, content: List<StrokeViewportBounds>, options: ExportOptions,
        original: PdfOutputPageSize? = null, outputSize: PdfOutputPageSize? = null): List<ExportSheet> {
        val bounds = content.reduceOrNull { a, b -> StrokeViewportBounds(minOf(a.left,b.left), minOf(a.top,b.top), maxOf(a.right,b.right), maxOf(a.bottom,b.bottom)) }
        val sourceWidth = settings.documentWidth ?: A4PageLayout.pageWidth
        val sourceHeight = settings.documentHeight ?: A4PageLayout.pageHeight
        if (settings.paperSize == PaperSize.PDF && options.paper == ExportPaper.AUTO && original != null && outputSize == null)
            return listOf(ExportSheet(StrokeViewportBounds(0f,0f,sourceWidth,sourceHeight), original.widthPoints, original.heightPoints, original.widthPoints/sourceWidth, 0f))
        val unit = PDF_POINTS_PER_INCH / MILLIMETERS_PER_INCH
        val width = outputSize?.widthPoints ?: (if (options.landscape) options.paper.heightMm else options.paper.widthMm) * unit
        val height = outputSize?.heightPoints ?: (if (options.landscape) options.paper.widthMm else options.paper.heightMm) * unit
        val margin = options.marginMm.coerceIn(0f,40f) * unit
        require(width > 2*margin && height > 2*margin) { "边距超过可打印纸张大小" }
        if (settings.paperSize == PaperSize.PDF) return listOf(ExportSheet(StrokeViewportBounds(0f,0f,sourceWidth,sourceHeight),width,height,
            minOf((width-2*margin)/sourceWidth,(height-2*margin)/sourceHeight),margin))
        if (settings.paperSize == PaperSize.A4) {
            val stride = sourceHeight + A4PageLayout.pageGap
            val occupied = maxOf(1, ceil((bounds?.bottom ?: 0f) / stride).toInt())
            val count = if (options.trimTrailingBlank) occupied else maxOf(occupied, settings.a4PageCount)
            require(count <= 10000) { "分页超过 10000 页，请缩小导出范围" }
            return (0 until count).map { i ->
                val top = if (i == 0) minOf(0f,bounds?.top ?: 0f) else i * stride
                var bottom = i * stride + sourceHeight
                if (content.any { it.bottom > bottom && it.top < (i+1)*stride }) bottom = (i+1)*stride
                val left = minOf(0f,bounds?.left ?: 0f)
                val right = maxOf(sourceWidth,bounds?.right ?: 0f)
                val scale = minOf((width-2*margin)/(right-left), (height-2*margin)/(bottom-top))
                ExportSheet(StrokeViewportBounds(left,top,right,bottom),width,height,scale,margin)
            }
        }
        val source = if (settings.paperSize == PaperSize.PDF) StrokeViewportBounds(0f,0f,sourceWidth,sourceHeight)
            else bounds ?: StrokeViewportBounds(0f,0f,(width-2*margin)*DOCUMENT_UNITS_PER_PDF_POINT,(height-2*margin)*DOCUMENT_UNITS_PER_PDF_POINT)
        val scale = minOf(1f / DOCUMENT_UNITS_PER_PDF_POINT, (width-2*margin)/(source.right-source.left))
        val sliceHeight = (height-2*margin)/scale
        val count = maxOf(1,ceil(((source.bottom-source.top)/sliceHeight - 0.000001f).toDouble()).toInt())
        require(count <= 10000) { "分页超过 10000 页，请缩小导出范围" }
        return (0 until count).map { i -> ExportSheet(StrokeViewportBounds(source.left,source.top+i*sliceHeight,source.right,
            minOf(source.bottom,source.top+(i+1)*sliceHeight)),width,height,scale,margin) }
    }

    fun selectPages(range: String, count: Int): List<Int> {
        if (range.isBlank()) return (0 until count).toList()
        val indices = sortedSetOf<Int>()
        for (part in range.replace('，',',').split(',')) {
            val ends = part.trim().split('-')
            val start = ends.first().toIntOrNull()
            val end = ends.last().toIntOrNull()
            require(ends.size <= 2 && start != null && end != null && start >= 1 && end >= start && end <= count) { "页码范围无效，请输入 1-$count 内的页码，例如 1-3,5" }
            for (i in start..end) indices.add(i-1)
        }
        return indices.toList()
    }
}
