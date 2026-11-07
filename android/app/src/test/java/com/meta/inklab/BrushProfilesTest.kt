package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushProfilesTest {
    @Test
    fun zeroPercentCorrectionsOmitDegenerateBehaviorsInsteadOfCrashing() {
        // Tablet crash 2026-11-07: dragging the ink-flow slider to 0% produced {1, 1}.
        val zeroSpeed = BrushProfiles.fountainRanges(pressureCorrect = 2f, speedCorrect = 0f)
        assertFalse(BrushProfiles.isEffectiveModifierRange(zeroSpeed.speedLow, zeroSpeed.speedHigh))
        val zeroPressure = BrushProfiles.fountainRanges(pressureCorrect = 0f, speedCorrect = 1f)
        assertFalse(BrushProfiles.isEffectiveModifierRange(zeroPressure.pressureLow, zeroPressure.pressureHigh))

        val defaults = BrushProfiles.fountainRanges(pressureCorrect = 2f, speedCorrect = 1f)
        assertTrue(BrushProfiles.isEffectiveModifierRange(defaults.pressureLow, defaults.pressureHigh))
        assertTrue(BrushProfiles.isEffectiveModifierRange(defaults.speedLow, defaults.speedHigh))
        for (percent in 1..400) {
            val ranges = BrushProfiles.fountainRanges(percent / 100f, percent / 100f)
            assertTrue(BrushProfiles.isEffectiveModifierRange(ranges.pressureLow, ranges.pressureHigh))
            assertTrue(BrushProfiles.isEffectiveModifierRange(ranges.speedLow, ranges.speedHigh))
        }
    }

    @Test
    fun fountainV2FollowsCalibratedNoteinModel() {
        val spec = BrushProfiles.fountainV2Spec(pressureCorrect = 2f, speedCorrect = 1f)
        // 200% pressure is a squared response with no floor; full pressure is 1.4x the nominal width.
        val half = spec.pressureCurve.first { it.first == 0.5f }
        assertEquals(0.25f, half.second, 1e-6f)
        assertEquals(BrushProfiles.FULL_PRESSURE_WIDTH, spec.pressureMultiplierEnd, 0f)
        // 100% ink flow thins by 85% at the 64 cm/s cap; 16 cm/s maps to g = 0.46.
        assertEquals(0.15f, spec.speedMultiplierEnd!!, 1e-6f)
        val at16 = spec.speedCurve.first { it.first == 16f / BrushProfiles.SPEED_CAP_CM_PER_SECOND }
        assertEquals(0.46f, at16.second * BrushProfiles.SPEED_THINNING_CAP, 1e-6f)
        // 107% pressure is close to linear.
        val nearLinear = BrushProfiles.fountainV2Spec(1.07f, 1f).pressureCurve.first { it.first == 0.5f }
        assertEquals(0.476f, nearLinear.second, 0.01f)
    }

    @Test
    fun fountainV2RangesStayValidAcrossSliders() {
        assertNull(BrushProfiles.fountainV2Spec(2f, 0f).speedMultiplierEnd)
        assertEquals(listOf(0f to 1f), BrushProfiles.fountainV2Spec(0f, 1f).pressureCurve)
        for (percent in 0..400) {
            val spec = BrushProfiles.fountainV2Spec(percent / 100f, percent / 100f)
            for (curve in listOf(spec.pressureCurve, spec.speedCurve)) {
                assertTrue(curve.all { it.first in 0f..1f && it.second.isFinite() })
                assertTrue(curve.zipWithNext().all { (a, b) -> a.first <= b.first })
            }
            assertTrue(BrushProfiles.isEffectiveModifierRange(0f, spec.pressureMultiplierEnd))
            spec.speedMultiplierEnd?.let { assertTrue(it > 0f && BrushProfiles.isEffectiveModifierRange(1f, it)) }
        }
    }

    @Test
    fun storedProfileSeparatesCalibratedAndLegacyFamilies() {
        assertEquals(BrushProfiles.FOUNTAIN_V2, BrushProfiles.storedProfile("inklab-fountain-v4-p2.0-s1.0"))
        assertEquals(BrushProfiles.FOUNTAIN_V1, BrushProfiles.storedProfile("inklab-fountain-v3-p2.0-s1.0"))
        assertEquals(BrushProfiles.FOUNTAIN_V1, BrushProfiles.storedProfile(""))
    }
}
