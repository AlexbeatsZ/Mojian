package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class BackgroundPatternTest {
    @Test fun everySavedFormatHasOneSharedDefinition() {
        assertEquals(CanvasBackgroundFormat.entries.map { it.name }.toSet(), BackgroundPatternCatalog.formats.keys)
        CanvasBackgroundFormat.entries.forEach { format ->
            assertTrue(format.label.isNotBlank())
            assertTrue(format.pattern.spacing > 0)
            val settings = PageViewSettings(backgroundFormat = format)
            val shared = SharedWorkspaceCodec.encodeAppearance(settings, null)
            assertEquals(format, SharedWorkspaceCodec.decodeAppearance(shared).backgroundFormat)
        }
    }

    @Test fun physicalSpacingIsIndependentOfAndroidDensityAndMatchesCanonicalStore() {
        val expected = mapOf(CanvasBackgroundFormat.RULE_STANDARD to 32f, CanvasBackgroundFormat.RULE_NARROW to 24f,
            CanvasBackgroundFormat.RULE_WIDE to 43.2f, CanvasBackgroundFormat.GRID_DENSE to 16f,
            CanvasBackgroundFormat.GRID_STANDARD to 32f, CanvasBackgroundFormat.GRID_WIDE to 48f,
            CanvasBackgroundFormat.DOTS_DENSE to 16f, CanvasBackgroundFormat.DOTS_WIDE to 48f)
        expected.forEach { (format, canonical) ->
            assertEquals(canonical, (BackgroundPatternCatalog.documentUnits(format.pattern.spacing) * SharedWorkspaceCodec.TO_SHARED).toFloat(), .0001f)
        }
        assertEquals(32.0, A4PageLayout.pageGap * SharedWorkspaceCodec.TO_SHARED, .0001)
        val height = (1122.5 / SharedWorkspaceCodec.TO_SHARED).toFloat()
        assertEquals(1154.5, A4PageLayout.pageTop(1, height) * SharedWorkspaceCodec.TO_SHARED, .0002)
        assertEquals(64.0, BackgroundPatternCatalog.documentUnits(BackgroundPatternCatalog.marginX) * SharedWorkspaceCodec.TO_SHARED, .0001)
        assertEquals(.8f, BackgroundPatternCatalog.lineWidth, 0f)
        assertEquals(1.25f, BackgroundPatternCatalog.dotRadius, 0f)
    }

    @Test fun ruledVariantsShareSpacingAndCleanRulesOmitOnlyTheMargin() {
        listOf(CanvasBackgroundFormat.RULE_STANDARD to CanvasBackgroundFormat.RULE_TWO_COLUMN_STANDARD,
            CanvasBackgroundFormat.RULE_NARROW to CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW,
            CanvasBackgroundFormat.RULE_WIDE to CanvasBackgroundFormat.RULE_TWO_COLUMN_WIDE).forEach { (one, two) ->
            assertEquals(one.pattern.spacing, two.pattern.spacing, 0f)
            assertTrue(one.showMarginLine && two.showMarginLine && two.showColumnDivider)
        }
        assertFalse(CanvasBackgroundFormat.RULE_CLEAN.showMarginLine)
        assertEquals(CanvasBackgroundFormat.RULE_STANDARD.pattern.spacing, CanvasBackgroundFormat.RULE_CLEAN.pattern.spacing, 0f)
        assertEquals(4, BackgroundPatternCatalog.majorEvery)
        assertEquals(0xFF1F303A.toInt(), BackgroundPatternCatalog.dark.line)
        assertEquals(0xFFDCE5EB.toInt(), BackgroundPatternCatalog.light.line)
    }
}
