package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageViewModelsTest {
    @Test
    fun paperSizesExposeDocumentDimensions() {
        assertEquals(210f * DOCUMENT_UNITS_PER_MM, PaperSize.A4_PORTRAIT.documentWidth, 0.001f)
        assertEquals(297f * DOCUMENT_UNITS_PER_MM, PaperSize.A4_PORTRAIT.documentHeight ?: 0f, 0.001f)
        assertEquals(null, PaperSize.AUTO.documentHeight)
    }

    @Test
    fun pageSettingsRoundTripThroughJson() {
        val original =
            PageViewSettings(
                paperSize = PaperSize.LETTER_LANDSCAPE,
                pageColor = PageColor.BLUE,
                pageTitleVisible = false,
                darkBackground = false,
            )

        val restored = PageViewSettings.fromJson(original.toJson())

        assertEquals(PaperSize.LETTER_LANDSCAPE, restored.paperSize)
        assertEquals(PageColor.BLUE, restored.pageColor)
        assertFalse(restored.pageTitleVisible)
        assertFalse(restored.darkBackground)
    }
}
