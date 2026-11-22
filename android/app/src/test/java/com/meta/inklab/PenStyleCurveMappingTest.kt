package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pen-style curves must reach AndroidX Ink unchanged. [inkLinear] mirrors google/ink
 * `EasingImplementation` for `EasingFunction.Linear` (implicit (0,0)/(1,1), x == 1 returns the
 * implicit end), and sources are CLAMPed into [0, 1] before the response curve.
 */
class PenStyleCurveMappingTest {
    private fun inkLinear(explicit: List<Pair<Float, Float>>, x: Float): Float {
        val points = mutableListOf<Pair<Float, Float>>()
        explicit.forEachIndexed { i, p ->
            when {
                i > 0 && p.first == 0f -> points[0] = p
                p.first == 1f && points.isNotEmpty() && points.last().first == 1f -> return@forEachIndexed
                points.size >= 2 && points[points.size - 1].first == p.first && points[points.size - 2].first == p.first -> points[points.size - 1] = p
                else -> points.add(p)
            }
        }
        val index = points.indexOfFirst { x < it.first }
        var prev = 0f to 0f; var next = 1f to 1f
        if (index < 0) { if (points.isNotEmpty()) prev = points.last() } else { next = points[index]; if (index > 0) prev = points[index - 1] }
        if (prev.second == next.second) return next.second
        if (prev.first == next.first) return if (x < next.first) prev.second else next.second
        return prev.second + (next.second - prev.second) * (x - prev.first) / (next.first - prev.first)
    }

    private fun inkWidth(points: List<CurvePoint>, source: Double): Double {
        val mapping = BrushProfiles.curveMapping(points) ?: return 1.0
        val x = (source / points.last().x).coerceIn(0.0, 1.0).toFloat()
        val r = inkLinear(mapping.response, x)
        return (mapping.targetStart + (mapping.targetEnd - mapping.targetStart) * r).toDouble()
    }

    private fun assertCurveReachesInk(points: List<CurvePoint>, label: String) {
        val end = points.last().x
        for (i in 0..400) {
            val source = end * 1.5 * i / 400
            assertEquals("$label @ $source", PenStyle.evaluate(points, source.coerceAtMost(end)), inkWidth(points, source), 2e-4)
        }
    }

    @Test
    fun noteinSpeedThinningHoldsAtAndBeyondTheCap() {
        val notein = NamedPenStyle.defaults().single { it.id == "notein" }
        for (flow in listOf(0, 25, 50, 100, 150, 200)) {
            for (pressure in listOf(0, 107, 200, 400)) {
                val style = notein.configuration!!.resolve(notein.configuration.parameters + mapOf("flow" to flow.toDouble(), "pressure" to pressure.toDouble()))
                assertCurveReachesInk(style.speedCurve, "speed flow=$flow")
                assertCurveReachesInk(style.pressureCurve, "pressure p=$pressure")
            }
        }
        // 0.7.26-0.7.38 regression: at >= 64 cm/s the 100% flow brush returned to full width.
        val style = notein.style
        assertEquals(0.15, inkWidth(style.speedCurve, 64.0), 1e-4)
        assertEquals(0.15, inkWidth(style.speedCurve, 120.0), 1e-4)
    }

    @Test
    fun noteinStyleMatchesCalibratedFountainV2() {
        val style = NamedPenStyle.defaults().single { it.id == "notein" }.style
        val spec = BrushProfiles.fountainV2Spec(2f, 1f)
        val end = spec.speedMultiplierEnd!!.toDouble()
        val response = spec.speedCurve
        for (i in 0..200) {
            val v = 80.0 * i / 200
            val x = (v / BrushProfiles.SPEED_CAP_CM_PER_SECOND).coerceIn(0.0, 1.0).toFloat()
            val calibrated = 1 + (end - 1) * inkLinear(response, x)
            assertEquals("v=$v", calibrated, inkWidth(style.speedCurve, v), 2e-4)
            val p = i / 200.0
            assertEquals("p=$p", (BrushProfiles.FULL_PRESSURE_WIDTH * inkLinear(spec.pressureCurve, p.toFloat())).toDouble(), inkWidth(style.pressureCurve, p), 2e-4)
        }
    }

    @Test
    fun everyBuiltInCurveReachesInkUnchanged() {
        NamedPenStyle.defaults().forEach { preset ->
            assertCurveReachesInk(preset.style.pressureCurve, "${preset.id} pressure")
            assertCurveReachesInk(preset.style.speedCurve, "${preset.id} speed")
        }
        // Rising, falling, interior extremes and constants.
        val shapes = listOf(
            listOf(CurvePoint(0.0, .2), CurvePoint(.5, 1.6), CurvePoint(1.0, .9)),
            listOf(CurvePoint(0.0, 1.2), CurvePoint(.3, .4), CurvePoint(1.0, 1.0)),
            listOf(CurvePoint(0.0, .7), CurvePoint(1.0, .7)),
        )
        shapes.forEachIndexed { i, curve -> assertCurveReachesInk(curve, "shape $i") }
        assertNull(BrushProfiles.curveMapping(listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 1.0))))
    }
}
