package com.meta.inklab

data class ExportSourceSummary(
    val settings: PageViewSettings,
    val content: List<StrokeViewportBounds>,
    val originalSize: PdfOutputPageSize?,
    val images: List<ExportImageChoice> = emptyList(),
) {
    fun sheets(options: ExportOptions, outputSize: PdfOutputPageSize? = null): List<Pair<ExportSheet, Int>> {
        val paper = settings.pageColor.displayArgb(settings.darkBackground)
        val color = if (options.printing && settings.darkBackground) OutputAppearance.printPaper(paper) else paper
        return ExportLayout.plan(settings, content, options, originalSize, outputSize).map { it to color }
    }
}
