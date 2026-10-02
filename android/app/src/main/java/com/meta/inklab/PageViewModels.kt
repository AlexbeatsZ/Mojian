package com.meta.inklab

import org.json.JSONObject

const val DOCUMENT_UNITS_PER_MM = 12.16225f
const val PDF_POINTS_PER_INCH = 72f
const val MILLIMETERS_PER_INCH = 25.4f
const val DOCUMENT_UNITS_PER_PDF_POINT = DOCUMENT_UNITS_PER_MM * MILLIMETERS_PER_INCH / PDF_POINTS_PER_INCH

enum class PaperSize(
    val menuLabel: String,
    val widthMm: Float?,
    val heightMm: Float?,
) {
    BOUNDLESS("无界页面", null, null),
    A4("A4", 210f, 297f),
    PDF("PDF", null, null),
    ;

    val documentWidth: Float?
        get() = widthMm?.times(DOCUMENT_UNITS_PER_MM)

    val documentHeight: Float?
        get() = heightMm?.times(DOCUMENT_UNITS_PER_MM)

    companion object {
        val creationChoices: List<PaperSize> = listOf(BOUNDLESS, A4)
    }
}

object A4PageLayout {
    const val PAGE_GAP_MM = 12f
    const val AUTO_GROW_MARGIN_MM = 24f

    val pageWidth: Float = 210f * DOCUMENT_UNITS_PER_MM
    val pageHeight: Float = 297f * DOCUMENT_UNITS_PER_MM
    val pageGap: Float = PAGE_GAP_MM * DOCUMENT_UNITS_PER_MM
    val autoGrowMargin: Float = AUTO_GROW_MARGIN_MM * DOCUMENT_UNITS_PER_MM

    fun pageTop(index: Int): Float = index.coerceAtLeast(0) * (pageHeight + pageGap)

    fun pageBottom(index: Int): Float = pageTop(index) + pageHeight

    fun requiredPageCount(
        contentBottom: Float,
        currentPageCount: Int,
    ): Int = requiredPageCount(contentBottom, 0f, currentPageCount)

    fun requiredPageCount(
        contentBottom: Float,
        visibleDocumentBottom: Float,
        currentPageCount: Int,
    ): Int {
        var count = currentPageCount.coerceAtLeast(1)
        val observedBottom = maxOf(contentBottom, visibleDocumentBottom)
        while (observedBottom >= pageBottom(count - 1) - autoGrowMargin) count++
        return count
    }
}

enum class PageColor(
    val description: String,
    val lightArgb: Int,
    val darkArgb: Int,
) {
    WHITE("白色", 0xFFFAFAFA.toInt(), 0xFF1B1B1B.toInt()),
    SAND("米色", 0xFFF3E9D2.toInt(), 0xFF302A21.toInt()),
    BLUE("蓝色", 0xFFE2EDF8.toInt(), 0xFF1D2B38.toInt()),
    MINT("绿色", 0xFFE0F1E7.toInt(), 0xFF1E3028.toInt()),
    ROSE("粉色", 0xFFF4E2E8.toInt(), 0xFF352329.toInt()),
    ;

    fun displayArgb(darkBackground: Boolean): Int = if (darkBackground) darkArgb else lightArgb
}

object PageAppearanceCatalog {
    val paperTemplates: List<PaperSize> = PaperSize.creationChoices
    val pageColors: List<PageColor> = PageColor.entries.toList()
    val backgroundFormats: List<CanvasBackgroundFormat> = CanvasBackgroundFormat.entries.toList()
}

data class PageViewSettings(
    var paperSize: PaperSize = PaperSize.BOUNDLESS,
    var pageColor: PageColor = PageColor.WHITE,
    var backgroundFormat: CanvasBackgroundFormat = CanvasBackgroundFormat.GRID_STANDARD,
    var pageTitleVisible: Boolean = true,
    var darkBackground: Boolean = true,
    var a4PageCount: Int = 1,
    var customDocumentWidth: Float? = null,
    var customDocumentHeight: Float? = null,
    var boundlessDocumentWidth: Float = BoundlessPageLayout.initialWidth,
    var boundlessDocumentHeight: Float = BoundlessPageLayout.initialHeight,
) {
    val documentWidth: Float?
        get() = if (paperSize == PaperSize.BOUNDLESS) boundlessDocumentWidth else customDocumentWidth ?: paperSize.documentWidth

    val documentHeight: Float?
        get() = if (paperSize == PaperSize.BOUNDLESS) boundlessDocumentHeight else customDocumentHeight ?: paperSize.documentHeight

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("paperSize", paperSize.name)
            put("pageColor", pageColor.name)
            put("backgroundFormat", backgroundFormat.name)
            put("pageTitleVisible", pageTitleVisible)
            put("darkBackground", darkBackground)
            put("a4PageCount", a4PageCount.coerceAtLeast(1))
            put("boundlessDocumentWidth", boundlessDocumentWidth.toDouble())
            put("boundlessDocumentHeight", boundlessDocumentHeight.toDouble())
            customDocumentWidth?.let { put("customDocumentWidth", it.toDouble()) }
            customDocumentHeight?.let { put("customDocumentHeight", it.toDouble()) }
        }

    companion object {
        fun fromJson(json: JSONObject): PageViewSettings =
            PageViewSettings(
                paperSize = restorePaperSize(json.optString("paperSize", PaperSize.BOUNDLESS.name)),
                pageColor =
                    runCatching { PageColor.valueOf(json.optString("pageColor", PageColor.WHITE.name)) }
                        .getOrDefault(PageColor.WHITE),
                backgroundFormat =
                    runCatching {
                        CanvasBackgroundFormat.valueOf(
                            json.optString("backgroundFormat", CanvasBackgroundFormat.GRID_STANDARD.name),
                        )
                    }.getOrDefault(CanvasBackgroundFormat.GRID_STANDARD),
                pageTitleVisible = json.optBoolean("pageTitleVisible", true),
                darkBackground = json.optBoolean("darkBackground", true),
                a4PageCount = json.optInt("a4PageCount", 1).coerceAtLeast(1),
                customDocumentWidth = json.optDouble("customDocumentWidth", Double.NaN).takeIf(Double::isFinite)?.toFloat(),
                customDocumentHeight = json.optDouble("customDocumentHeight", Double.NaN).takeIf(Double::isFinite)?.toFloat(),
                boundlessDocumentWidth = json.optDouble("boundlessDocumentWidth", BoundlessPageLayout.initialWidth.toDouble())
                    .takeIf { it.isFinite() && it >= BoundlessPageLayout.initialWidth }?.toFloat() ?: BoundlessPageLayout.initialWidth,
                boundlessDocumentHeight = json.optDouble("boundlessDocumentHeight", BoundlessPageLayout.initialHeight.toDouble())
                    .takeIf { it.isFinite() && it >= BoundlessPageLayout.initialHeight }?.toFloat() ?: BoundlessPageLayout.initialHeight,
            )

        private fun restorePaperSize(saved: String): PaperSize =
            when (saved) {
                "A4",
                "A4_PORTRAIT",
                "A4_LANDSCAPE",
                "LETTER_PORTRAIT",
                "LETTER_LANDSCAPE" -> PaperSize.A4
                "PDF" -> PaperSize.PDF
                else -> PaperSize.BOUNDLESS
            }

    }
}
