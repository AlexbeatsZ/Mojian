package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PenStyleTest {
    @Test fun sharedBuiltinsMatchTheCalibratedCurvesAndValidate() {
        val styles = NamedPenStyle.defaults()
        assertEquals("natural", ToolShelf().selected.penStyleId)
        assertEquals(3, ToolShelf().penStyles.size)
        assertEquals(listOf("natural", "classic", "uniform"), styles.map { it.id })
        styles.forEach { assertEquals(it.style, PenStyle.fromJson(it.style.toJson())) }
        val natural = styles.first().style
        assertEquals(.35, PenStyle.evaluate(natural.pressureCurve, .5), 1e-8)
        assertEquals(.54, PenStyle.evaluate(natural.speedCurve, 16.0), 1e-8)
        assertFalse(natural.startEnabled); assertFalse(natural.endEnabled)
        assertTrue(styles[1].style.turnEnabled)
        assertEquals(1.25, PenStyle.evaluate(listOf(CurvePoint(0.0, 2.0), CurvePoint(1.0, .5)), .5), 1e-8)
    }

    @Test fun namedCreateEditRoundTripAndSnapshotsAreIndependent() {
        val shelf = ToolShelf()
        val black = shelf.tools.first { it.id == "pen-black" }; val blue = shelf.tools.first { it.id == "pen-blue" }
        val original = PenStyle.fromLegacy(78, 20, 0)
        val preset = shelf.savePenStyle(null, "硬笔练字", original)
        shelf.applyPenStyle(black, preset); shelf.applyPenStyle(blue, preset)
        val historical = black.penStyle!!.snapshot()
        original.endEnabled = true
        assertFalse(preset.style.endEnabled)
        val modified = preset.style.snapshot().apply { endEnabled = true; stabilityPercent = 75 }
        shelf.savePenStyle(preset.id, "课堂笔", modified)
        assertTrue(black.penStyle!!.endEnabled); assertTrue(blue.penStyle!!.endEnabled)
        assertFalse(historical.endEnabled); assertEquals(75, black.stabilityPercent)
        assertEquals(InkPalette.BLUE, blue.color); assertEquals(.4f, black.widthMm, 0f)
        val restored = ToolShelf.fromJson(shelf.toJson())!!
        assertEquals("课堂笔", restored.penStyles.first { it.id == preset.id }.name)
        assertEquals(preset.id, restored.tools.first { it.id == "pen-blue" }.penStyleId)
        assertTrue(restored.tools.first { it.id == "pen-black" }.penStyle!!.endEnabled)
        assertThrows(IllegalArgumentException::class.java) { shelf.savePenStyle(null, "课堂笔", modified) }
        assertThrows(IllegalArgumentException::class.java) { shelf.savePenStyle(null, " ", modified) }
        val natural = shelf.penStyles.first { it.id == "natural" }.style.snapshot().apply { pressureEnabled = false }
        shelf.savePenStyle("natural", "自然钢笔", natural)
        assertFalse(shelf.add(DrawingToolKind.PEN).penStyle!!.pressureEnabled)
    }

    @Test fun oldToolSettingsAndMissingOrInvalidStylesDoNotReplaceInkBehavior() {
        val old = ToolPreset("pen-black", DrawingToolKind.PEN, pressurePercent = 78, inkFlowPercent = 20, stabilityPercent = 0)
        val json = JSONObject().put("tools", JSONArray().put(old.toJson())).put("selectedId", "pen-black")
        val restored = ToolShelf.fromJson(json.toString())!!
        assertNull(restored.selected.penStyle)
        assertEquals(78, restored.selected.pressurePercent); assertEquals(20, restored.selected.inkFlowPercent)
        assertTrue(restored.penStyles.any { it.id == restored.selected.penStyleId })
        assertEquals(PenStyle.fromLegacy(78, 20, 0), restored.effectivePenStyle(restored.selected))
        val invalid = PenStyle.fromLegacy().toJson()
        invalid.getJSONArray("pressureCurve").getJSONObject(1).put("x", 0)
        assertNull(PenStyle.fromJson(invalid))
        assertNull(PenStyle.fromJson(invalid.put("version", 2)))
    }
}
