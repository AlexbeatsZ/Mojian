package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class ZoomStepTest {
    @Test fun stepIsFineReversibleAndIndependentOfPresets() {
        val viewport = ViewportState()
        assertEquals(1.05f, viewport.steppedScale(1), .00001f)
        viewport.restore(ViewportSnapshot(1.37f, 30f, 50f))
        val start = viewport.scale
        viewport.restore(ViewportSnapshot(viewport.steppedScale(1), 30f, 50f))
        assertEquals(start, viewport.steppedScale(-1), .00001f)
        assertEquals(viewport.scale, viewport.steppedScale(0), 0f)
    }

    @Test fun stepHonorsBothLimits() {
        val viewport = ViewportState()
        viewport.restore(ViewportSnapshot(64f, 0f, 0f))
        assertEquals(64f, viewport.steppedScale(1), 0f)
        assertTrue(viewport.steppedScale(-1) < 64f)
        viewport.restore(ViewportSnapshot(.5f, 0f, 0f))
        assertEquals(.5f, viewport.steppedScale(-1), 0f)
        assertTrue(viewport.steppedScale(1) > .5f)
    }
}
