package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ImportedPagePresentationTest {
    @Test
    fun canonicalEightDigitPaperSurvivesAndroidWorkspaceRoundTrip() {
        val original = JSONObject().put("pageColor", "#FFFFEABD").put("theme", 1)
            .put("infiniteWidth", 10000).put("infiniteHeight", 10000)
        val decoded = SharedWorkspaceCodec.decodeAppearance(original)
        assertEquals(0xFFFFEABD.toInt(), decoded.sourcePageColor)
        assertEquals(decoded, PageViewSettings.fromJson(decoded.toJson()))
        assertEquals("#FFFFEABD", SharedWorkspaceCodec.encodeAppearance(decoded, original).getString("pageColor"))
        assertTrue(ImportedPagePresentation.display(decoded.copy(darkBackground = true), "page-notein-example").invertImportedImages)
    }

    @Test
    fun blueScreenshotBackgroundBecomesGrayAndBothBlackAndWhiteFormulaStayVisible() {
        val samples = IntArray(100) { if (it < 80) 0xFF0000B8.toInt() else if (it < 90) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val background = ImportedPagePresentation.imageBackground(samples)!!
        assertEquals(0xFF1B1B1B.toInt(), ImportedPagePresentation.imagePixel(0xFF0000B8.toInt(), background, true))
        assertEquals(0xFFFFFFFF.toInt(), ImportedPagePresentation.imagePixel(0xFF000000.toInt(), background, true))
        assertEquals(0xFFFFFFFF.toInt(), ImportedPagePresentation.imagePixel(0xFFFFFFFF.toInt(), background, true))
        val diverse = IntArray(256) { 0xFF000000.toInt() or (it shl 16) or ((255 - it) shl 8) }
        assertNull(ImportedPagePresentation.imageBackground(diverse))
        assertEquals(64, ImportedPagePresentation.imagePixel(0x40000000, background, true) ushr 24)
    }

    @Test
    fun importedPaperContainsNegativeAndOverflowContentWithoutChangingStoredSettings() {
        for (kind in listOf(PaperSize.BOUNDLESS, PaperSize.A4)) {
            val source = PageViewSettings(paperSize = kind, sourcePageColor = 0xFFFFEABD.toInt())
            val before = source.toJson().toString()
            val display = ImportedPagePresentation.display(source, "page-notein-example",
                sequenceOf(StrokeViewportBounds(-2400f, -74f, 5000f, 26000f)))
            assertEquals(PaperSize.BOUNDLESS, display.paperSize)
            assertTrue(display.paperOriginX < -2400f)
            assertTrue(display.paperOriginY < -74f)
            assertTrue(display.paperOriginX + display.documentWidth!! > 5000f)
            assertTrue(display.paperOriginY + display.documentHeight!! > 26000f)
            assertEquals(PageColor.WHITE, display.pageColor)
            assertEquals(before, source.toJson().toString())
            assertEquals(source, ImportedPagePresentation.display(source, "page-native"))
        }
        val pdf = PageViewSettings(paperSize = PaperSize.PDF)
        assertEquals(PaperSize.PDF, ImportedPagePresentation.display(pdf, "page-notein-example").paperSize)
    }

    @Test
    fun imagesAndInkChangeTogetherAndNativeInsertsAreNotDoubleInverted() {
        val dark = ImportedPagePresentation.display(PageViewSettings(sourcePageColor = 0xFFFFEABD.toInt()), "page-notein-example")
        assertTrue(ImportedPagePresentation.imageInverted(dark, "object-notein-image-example", false))
        assertFalse(ImportedPagePresentation.imageInverted(dark, "object-notein-image-example", true))
        assertFalse(ImportedPagePresentation.imageInverted(dark, "object-native", false))
        assertEquals(0xFFFFFFFF.toInt(), ImportedPagePresentation.ink(0xFF000000.toInt(), true, true))
        assertEquals(0xFF1B1B1B.toInt(), ImportedPagePresentation.ink(0xFFFFFFFF.toInt(), true, true))
        assertEquals(0xFF0091FF.toInt(), ImportedPagePresentation.ink(0xFF0091FF.toInt(), true, true))
        val light = ImportedPagePresentation.display(PageViewSettings(darkBackground = false, sourcePageColor = 0xFFFFEABD.toInt()), "page-notein-example")
        assertFalse(light.invertImportedImages)
        assertEquals(0xFF000000.toInt(), ImportedPagePresentation.ink(0xFF000000.toInt(), false, false))
    }
}
