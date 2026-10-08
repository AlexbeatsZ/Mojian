package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TwoColumnBackgroundTest {
    @Test
    fun bothPageAndTemplateSettingsKeepAllTwoColumnFormats() {
        val formats = listOf(
            CanvasBackgroundFormat.RULE_TWO_COLUMN_STANDARD,
            CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW,
            CanvasBackgroundFormat.RULE_TWO_COLUMN_WIDE,
        )
        formats.forEach { format ->
            assertTrue(PageAppearanceCatalog.backgroundFormats.contains(format))
            val settings = PageViewSettings(backgroundFormat = format)
            assertEquals(format, PageViewSettings.fromJson(settings.toJson()).backgroundFormat)
            // These exact names are emitted by Windows .mjn manifests.
            assertEquals(format, PageViewSettings.fromJson(JSONObject().put("backgroundFormat", format.name)).backgroundFormat)
            assertTrue(format.showColumnDivider)
        }
        assertFalse(CanvasBackgroundFormat.RULE_STANDARD.showColumnDivider)
        assertEquals(CanvasBackgroundFormat.RULE_STANDARD.stepMultiplier, formats[0].stepMultiplier, 0f)
        assertEquals(CanvasBackgroundFormat.RULE_NARROW.stepMultiplier, formats[1].stepMultiplier, 0f)
        assertEquals(CanvasBackgroundFormat.RULE_WIDE.stepMultiplier, formats[2].stepMultiplier, 0f)
    }
}
