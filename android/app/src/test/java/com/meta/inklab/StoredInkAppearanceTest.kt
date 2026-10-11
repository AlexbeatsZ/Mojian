package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoredInkAppearanceTest {
    @Test fun legacyDesktopFountainIsOpaqueAndSourceRecordStaysUnchanged() {
        val stroke = JSONObject("""{"kind":0,"brushProfile":"fountain-v1","opacityPercent":32,"color":2,"points":[{"x":1,"y":2}]}""")
        val before = stroke.toString()
        assertEquals(100, StoredInkAppearance.opacityPercent(stroke))
        assertEquals(before, stroke.toString())
    }

    @Test fun authoredAndOlderMissingProfilePensShareDesktopOpacity() {
        assertEquals(100, StoredInkAppearance.opacityPercent(JSONObject("""{"kind":0,"opacityPercent":100}""")))
        assertEquals(100, StoredInkAppearance.opacityPercent(JSONObject("""{"kind":0,"opacityPercent":32}""")))
    }

    @Test fun highlighterAndShapeOpacityRemainIntentional() {
        assertEquals(32, StoredInkAppearance.opacityPercent(JSONObject("""{"kind":1,"opacityPercent":32}""")))
        assertEquals(48, StoredInkAppearance.opacityPercent(JSONObject("""{"kind":0,"brushProfile":"shape-v1","opacityPercent":48}""")))
    }

    @Test fun importedOutlinesKeepFullOpacityRangeIncludingInvisibleInk() {
        for (opacity in listOf(0, 3, 32, 85, 100)) {
            for (kind in listOf(0, 1)) {
                val stroke = JSONObject().put("kind", kind).put("vectorPath", "M1,2L3,4Z").put("opacityPercent", opacity)
                assertEquals(opacity, StoredInkAppearance.opacityPercent(stroke))
            }
        }
    }
}
