package com.meta.inklab

import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.junit.Assert.*
import org.junit.Test

/** Isolated native fixtures only; compilation is not a claim of tablet acceptance. */
class PenStyleIntegrationTest {
    @Test fun presetsAndFlatOrNonMonotonicCurvesCompileAndKeepTheirStrokeSnapshot() {
        val styles = NamedPenStyle.defaults().map { it.style } + listOf(
            PenStyle.fromLegacy().apply { pressureCurve = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 0.0)) },
            PenStyle.fromLegacy().apply { pressureCurve = listOf(CurvePoint(0.0, 1.0), CurvePoint(.5, 2.0), CurvePoint(1.0, 1.0)) },
        )
        for (style in styles) {
            val brush = BrushProfiles.createStyle(.4f * DOCUMENT_UNITS_PER_MM, style, Color.BLACK)
            assertEquals(PenStyle.PROFILE, BrushProfiles.storedProfile(brush.family.clientBrushFamilyId))
            assertEquals(style, BrushProfiles.styleFromFamilyId(brush.family.clientBrushFamilyId))
            val inputs = MutableStrokeInputBatch().apply {
                for (i in 0..30) add(InputToolType.STYLUS, (20 + i * 2).toFloat(), 40f, i * 4L, pressure = .5f)
            }
            val json = SharedStrokeCodec.encode(Stroke(brush, inputs))
            assertEquals(PenStyle.PROFILE, json.getString("brushProfile"))
            assertEquals(style, PenStyle.fromJson(json.getJSONObject("penStyle")))
            assertEquals(style.stabilityPercent, json.getInt("stabilityPercent"))
            val points = json.getJSONArray("points")
            assertFalse(points.getJSONObject(0).has("tiltRadians"))
            val rawOnly = org.json.JSONObject(json.toString()).apply { remove("vectorPath"); remove("vectorBounds"); remove("vectorTransform") }
            val restored = SharedStrokeCodec.decode(rawOnly, { it.argb })
            assertEquals(0f, restored.inputs.get(0).strokeUnitLengthCm, 0f)
            assertEquals(-1f, restored.inputs.get(0).tiltRadians, 0f)
        }
    }
}
