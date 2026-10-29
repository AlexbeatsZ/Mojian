package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class PreferenceMemoryTest {
    @Test fun globalThemeOverridesEveryLegacyPageWithoutMutatingItAndRetainsTints() {
        val pages = listOf(PageViewSettings(darkBackground=false, pageColor=PageColor.MINT),
            PageViewSettings(darkBackground=true, pageColor=PageColor.SAND))
        val preferences = AppearancePreferences(true, CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW)
        assertTrue(pages.all { preferences.display(it).darkBackground })
        assertFalse(pages[0].darkBackground)
        val restored = AppearancePreferences.restore(preferences.toJson(), pages[0], pages[0])
        assertEquals(preferences, restored)
        val light = restored.copy(darkBackground=false)
        assertTrue(pages.none { light.display(it).darkBackground })
        assertEquals(pages.map { it.pageColor }, pages.map { light.display(it).pageColor })
    }

    @Test fun oneOffBackgroundAndTemplateChangesDoNotChangeTheNewPageDefault() {
        val preferences = AppearancePreferences(false, CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW)
        val template = PageViewSettings(paperSize=PaperSize.A4, pageColor=PageColor.BLUE,
            backgroundFormat=CanvasBackgroundFormat.DOTS_WIDE, a4PageCount=9)
        val draft = preferences.newPage(template)
        draft.backgroundFormat = CanvasBackgroundFormat.BLANK
        val restored = AppearancePreferences.restore(preferences.toJson(), draft, draft)
        val next = restored.newPage(draft)
        assertEquals(CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW, next.backgroundFormat)
        assertEquals(CanvasBackgroundFormat.DOTS_WIDE, template.backgroundFormat)
        assertEquals(PaperSize.A4, next.paperSize)
        assertEquals(PageColor.BLUE, next.pageColor)
        assertFalse(next.darkBackground)
        assertEquals(1, next.a4PageCount)
    }

    @Test fun oldPreferencesMigrateFromCurrentThemeAndSuccessfulCreationTemplate() {
        val selected = PageViewSettings(darkBackground=false, backgroundFormat=CanvasBackgroundFormat.BLANK)
        val template = PageViewSettings(backgroundFormat=CanvasBackgroundFormat.RULE_WIDE)
        val expected = AppearancePreferences(false, CanvasBackgroundFormat.RULE_WIDE)
        assertEquals(expected, AppearancePreferences.restore(null, selected, template))
        assertEquals(expected, AppearancePreferences.restore("damaged", selected, template))
        assertEquals(expected, AppearancePreferences.restore("{\"defaultBackground\":\"FUTURE\"}", selected, template))
    }

    @Test fun printingRestoresAllReusableChoicesWithoutExportOrImageState() {
        val options = ExportOptions(scope=ExportScope.NOTEBOOK, format=ExportFormat.PNG_ZIP,
            paper=ExportPaper.A3, landscape=true, marginMm=12f, trimTrailingBlank=false,
            pageRange="2-3", printing=true, invertedImageIds=setOf("old-image"), hideBackgroundFormat=true)
        val restored = PrintPreferences.restore(PrintPreferences.save(options))
        assertEquals(options.copy(format=ExportFormat.PDF, invertedImageIds=null), restored)
        assertEquals(ExportOptions(printing=true), PrintPreferences.restore(null))
        assertEquals(ExportOptions(printing=true), PrintPreferences.restore("damaged"))
        assertEquals(ExportOptions(printing=true), PrintPreferences.restore("{\"paper\":\"FUTURE\",\"scope\":\"FUTURE\",\"marginMm\":-8}"))
        assertFalse(ExportOptions().hideBackgroundFormat)
    }
}
