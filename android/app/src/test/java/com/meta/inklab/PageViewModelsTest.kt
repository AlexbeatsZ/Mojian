package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageViewModelsTest {
    @Test
    fun paperSizesExposeDocumentDimensions() {
        assertEquals(listOf(PaperSize.BOUNDLESS, PaperSize.A4), PaperSize.entries.toList())
        assertEquals(210f * DOCUMENT_UNITS_PER_MM, PaperSize.A4.documentWidth ?: 0f, 0.001f)
        assertEquals(297f * DOCUMENT_UNITS_PER_MM, PaperSize.A4.documentHeight ?: 0f, 0.001f)
        assertEquals(null, PaperSize.BOUNDLESS.documentWidth)
        assertEquals(null, PaperSize.BOUNDLESS.documentHeight)
    }

    @Test
    fun a4DocumentGrowsWhenContentApproachesTheLastSheetBottom() {
        val beforeMargin = A4PageLayout.pageBottom(0) - A4PageLayout.autoGrowMargin - 1f
        val atMargin = A4PageLayout.pageBottom(0) - A4PageLayout.autoGrowMargin

        assertEquals(1, A4PageLayout.requiredPageCount(beforeMargin, 1))
        assertEquals(2, A4PageLayout.requiredPageCount(atMargin, 1))
        assertEquals(3, A4PageLayout.requiredPageCount(A4PageLayout.pageBottom(1), 1))
        assertEquals(3, A4PageLayout.requiredPageCount(0f, 3))
        assertEquals(A4PageLayout.pageHeight + A4PageLayout.pageGap, A4PageLayout.pageTop(1), 0.001f)
    }

    @Test
    fun pageSettingsRoundTripThroughJson() {
        val original =
            PageViewSettings(
                paperSize = PaperSize.A4,
                pageColor = PageColor.BLUE,
                backgroundFormat = CanvasBackgroundFormat.DOTS_DENSE,
                pageTitleVisible = false,
                darkBackground = false,
                a4PageCount = 4,
            )

        val restored = PageViewSettings.fromJson(original.toJson())

        assertEquals(PaperSize.A4, restored.paperSize)
        assertEquals(PageColor.BLUE, restored.pageColor)
        assertEquals(CanvasBackgroundFormat.DOTS_DENSE, restored.backgroundFormat)
        assertFalse(restored.pageTitleVisible)
        assertFalse(restored.darkBackground)
        assertEquals(4, restored.a4PageCount)
    }

    @Test
    fun legacyFinitePaperSettingsMigrateToPaginatedA4() {
        val restored = PageViewSettings.fromJson(org.json.JSONObject().put("paperSize", "LETTER_PORTRAIT"))

        assertEquals(PaperSize.A4, restored.paperSize)
    }
}
