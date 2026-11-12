package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PenStyleTest {
    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        is Number -> value.toDouble()
        else -> value
    }
    private fun config() = NamedPenStyle.defaults().first { it.id == "notein" }.configuration!!.toJson().put("id", "test").put("name", "测试笔触")
    @Test fun noteinAndOneNoteArePreinstalledAndOriginalControlsStillWork() {
        val old = ToolPreset("pen-black", DrawingToolKind.PEN, widthMm = .3f, pressurePercent = 78, inkFlowPercent = 20, stabilityPercent = 0)
        val shelf = ToolShelf.fromJson(JSONObject().put("tools", JSONArray().put(old.toJson())).toString())!!
        assertEquals(listOf("Notein风格", "Onenote风格"), shelf.penStyles.map { it.name }); assertEquals("notein", shelf.selected.penStyleId)
        assertEquals(.3f, shelf.selected.widthMm, 0f); assertEquals(78, shelf.selected.pressurePercent); assertEquals(20, shelf.selected.inkFlowPercent); assertEquals(0, shelf.selected.stabilityPercent)
        assertTrue(PenStyle.fromLegacy(78, 20, 0).sameParameters(shelf.selected.penStyle!!))
        shelf.setPenParameter(shelf.selected, "pressure", 180.0); shelf.setPenParameter(shelf.selected, "flow", 150.0); shelf.setPenParameter(shelf.selected, "stability", 75.0)
        assertTrue(PenStyle.fromLegacy(180, 150, 75).sameParameters(shelf.selected.penStyle!!))
        assertTrue(ToolShelf.fromJson(shelf.toJson())!!.selected.penStyle!!.sameParameters(shelf.selected.penStyle!!))
    }
    @Test fun noteinFunctionsMatchCalibratedV2AtAllControlExtremes() {
        val config = NamedPenStyle.defaults().first { it.id == "notein" }.configuration!!
        for (pressure in listOf(0, 78, 200, 400)) for (flow in listOf(0, 20, 100, 200)) {
            val style = config.resolve(mapOf("pressure" to pressure.toDouble(), "flow" to flow.toDouble(), "stability" to 50.0))
            assertTrue(PenStyle.fromLegacy(pressure, flow, 50).sameParameters(style))
        }
    }
    @Test fun oneNoteConfigurationMatchesMeasuredEllipseTipPressureModel() {
        // Desktop OneNote 16 synthetic pen probe: ellipse tip, raw points, width × (0.25 + 1.5p), no speed/taper.
        val config = NamedPenStyle.defaults().single { it.id == "onenote" }.configuration!!
        val example = JSONObject(requireNotNull(javaClass.getResourceAsStream("/onenote.json")).bufferedReader().use { it.readText() })
        assertEquals("Onenote风格", config.name); assertEquals(canonical(example), canonical(config.toJson()))
        val tool = ToolPreset("p", DrawingToolKind.PEN, widthMm = .35f, pressurePercent = 200, inkFlowPercent = 100, stabilityPercent = 50)
        val style = config.resolve(config.valuesFor(tool))
        assertTrue(style.pressureEnabled); assertEquals(0, style.stabilityPercent); assertEquals(0, style.pressureResponseMs)
        assertFalse(style.speedEnabled || style.directionEnabled || style.startEnabled || style.endEnabled || style.tiltEnabled || style.turnEnabled)
        for (p in listOf(0.0, .02, .1, .5, .73, 1.0)) assertEquals(.25 + 1.5 * p, PenStyle.evaluate(style.pressureCurve, p), 1e-9)
        val flat = config.resolve(config.valuesFor(tool) + ("pressure" to 0.0))
        for (p in listOf(0.0, .5, 1.0)) assertEquals(1.0, PenStyle.evaluate(flat.pressureCurve, p), 1e-9)
        val shelf = ToolShelf(listOf(tool)); shelf.applyPenStyle(tool, shelf.penStyles.first { it.id == "onenote" })
        assertEquals(listOf("pressure", "stability"), shelf.penControls(tool).map { it.parameter })
        assertEquals(200, tool.pressurePercent); assertEquals(50, tool.stabilityPercent)
    }
    @Test fun importUpdatesByIdKeepsKnobsAndHistoricalSnapshots() {
        val shelf = ToolShelf(); val json = config(); val imported = shelf.importPenConfiguration(json.toString()); shelf.applyPenStyle(shelf.selected, imported)
        shelf.setPenParameter(shelf.selected, "pressure", 78.0); shelf.setPenParameter(shelf.selected, "stability", 0.0)
        val historical = shelf.selected.penStyle!!.snapshot(); json.getJSONObject("style").put("endEnabled", true); shelf.importPenConfiguration(json.toString())
        assertTrue(shelf.selected.penStyle!!.endEnabled); assertFalse(historical.endEnabled); assertEquals(78, shelf.selected.pressurePercent); assertEquals(0, shelf.selected.stabilityPercent)
        shelf.applyPenStyle(shelf.selected, shelf.penStyles.first { it.id == "notein" }); shelf.applyPenStyle(shelf.selected, shelf.penStyles.first { it.id == "test" })
        assertEquals(78, shelf.selected.pressurePercent); assertTrue(ToolShelf.fromJson(shelf.toJson())!!.selected.penStyle!!.endEnabled)
        val before = shelf.toJson(); assertThrows(IllegalArgumentException::class.java) { shelf.importPenConfiguration(json.put("id", "duplicate").toString()) }; assertEquals(before, shelf.toJson())
    }
    @Test fun oldAndroidResponseUsesFunctionsAndDirectEngineControls() {
        val config = PenConfiguration.parse(requireNotNull(javaClass.getResourceAsStream("/legacy-android.json")).bufferedReader().use { it.readText() })
        val tool = ToolPreset("p", DrawingToolKind.PEN, widthMm = .3f, pressurePercent = 78, inkFlowPercent = 20)
        val resolved = config.resolve(config.valuesFor(tool))
        assertEquals(85.0, resolved.speedMaximumCmPerSecond, 0.0); assertEquals(20, resolved.inputWindowMs)
        assertEquals(.3 * 1.25, resolved.startLengthMm, 1e-6); assertEquals(.3 * 2.5, resolved.endLengthMm, 1e-6)
        assertEquals(12, resolved.startResponseMs); assertEquals(12, resolved.endResponseMs); assertTrue(resolved.turnEnabled)
        val ranges = BrushProfiles.fountainRanges(.78f, .2f)
        assertEquals(ranges.pressureLow.toDouble(), PenStyle.evaluate(resolved.pressureCurve, .18), 1e-6)
        assertEquals(ranges.pressureHigh.toDouble(), PenStyle.evaluate(resolved.pressureCurve, .88), 1e-6)
        assertEquals(ranges.speedLow.toDouble(), PenStyle.evaluate(resolved.speedCurve, 0.0), 1e-6)
        assertEquals(ranges.speedHigh.toDouble(), PenStyle.evaluate(resolved.speedCurve, 85.0), 1e-6)
        val shelf = ToolShelf(listOf(tool)); shelf.applyPenStyle(tool, shelf.importPenConfiguration(config.text)); shelf.setPenParameter(tool, "windowMs", 80.0)
        assertEquals(80, tool.penStyle!!.inputWindowMs); assertEquals(50, tool.stabilityPercent)
    }
    @Test fun smoothFunctionAndFrontPanelParameterCanControlDifferentEngineFields() {
        val json = config(); val style = json.getJSONObject("style")
        style.put("pressureCurve", JSONObject().put("samples", 256).put("function", JSONObject().put("op", "smoothstep").put("args", JSONArray(listOf("x", 0, 1, 0, 1.4)))))
        json.put("controls", JSONArray().put(JSONObject().put("parameter", "stability").put("label", "速度平滑").put("min", 0).put("max", 100).put("step", 1).put("unit", " ms")))
        style.put("speedResponseMs", "\$stability").put("stabilityPercent", 0)
        val shelf = ToolShelf(); shelf.applyPenStyle(shelf.selected, shelf.importPenConfiguration(json.toString())); shelf.setPenParameter(shelf.selected, "stability", 80.0)
        assertEquals(80, shelf.selected.penStyle!!.speedResponseMs); assertEquals(0, shelf.selected.penStyle!!.stabilityPercent)
        for (i in 0..1000) { val x = i / 1000.0; assertTrue(abs(PenStyle.evaluate(shelf.selected.penStyle!!.pressureCurve, x) - 1.4 * x * x * (3 - 2 * x)) < .00002) }
    }
    @Test fun invalidImportAndUnknownCapabilitiesNeverChangeTheShelf() {
        val shelf = ToolShelf(); val before = shelf.toJson(); val json = config(); json.getJSONObject("style").put("unsupportedEffect", 1)
        assertThrows(IllegalArgumentException::class.java) { shelf.importPenConfiguration(json.toString()) }; assertEquals(before, shelf.toJson())
        json.getJSONObject("style").remove("unsupportedEffect"); json.getJSONObject("style").put("pressureCurve", JSONObject().put("function", "\$unknown"))
        assertThrows(IllegalArgumentException::class.java) { shelf.importPenConfiguration(json.toString()) }
        assertThrows(IllegalArgumentException::class.java) { PenConfiguration.parse(" ".repeat(PenConfiguration.MAXIMUM_BYTES + 1)) }
    }
    @Test fun oldGeneratedDefaultsAreRemovedButUserEditedConfigurationsAreKept() {
        val old = JSONArray(requireNotNull(javaClass.getResourceAsStream("/pen-style-legacy-migration.json")).bufferedReader().use { it.readText() })
        val root = JSONObject(ToolShelf().toJson()).put("penStyles", old); root.getJSONArray("tools").getJSONObject(0).put("penStyleId", "classic")
        assertEquals(listOf("notein", "onenote"), ToolShelf.fromJson(root.toString())!!.penStyles.map { it.id })
        val changed = JSONObject(old.getJSONObject(0).toString()); changed.getJSONObject("style").put("endEnabled", true); old.put(changed)
        assertTrue(ToolShelf.fromJson(root.toString())!!.penStyles.any { it.id == "natural" && it.style.endEnabled })
    }
}
