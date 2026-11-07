package com.meta.inklab

import org.junit.Assert.assertFalse
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
}
