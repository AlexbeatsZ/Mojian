package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransformGestureArbiterTest {
    @Test
    fun jitterBelowSlopDoesNothing() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)

        assertNull(arbiter.update(105f, 103f, 211f, allowScale = true))
        assertEquals(TransformGestureMode.UNDECIDED, arbiter.mode)
    }

    @Test
    fun panIgnoresLaterSpanNoiseBelowScaleSlop() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)

        assertNull(arbiter.update(110f, 100f, 204f, allowScale = true))
        assertEquals(TransformGestureMode.PAN, arbiter.mode)
        val update = requireNotNull(arbiter.update(120f, 105f, 212f, allowScale = true))
        assertEquals(TransformGestureMode.PAN, update.mode)
        assertEquals(1f, update.scaleFactor, 0.0001f)
    }

    @Test
    fun committedPanPromotesToScaleOncePinchBecomesClear() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)

        assertNull(arbiter.update(110f, 100f, 204f, allowScale = true))
        assertEquals(TransformGestureMode.PAN, arbiter.mode)

        val promotion = requireNotNull(arbiter.update(120f, 105f, 220f, allowScale = true))
        assertEquals(TransformGestureMode.SCALE, promotion.mode)
        assertEquals(1f, promotion.scaleFactor, 0.0001f)

        val scale = requireNotNull(arbiter.update(123f, 107f, 242f, allowScale = true))
        assertEquals(TransformGestureMode.SCALE, scale.mode)
        assertEquals(1.1f, scale.scaleFactor, 0.0001f)
    }

    @Test
    fun pinchCommitLocksToScale() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)

        assertNull(arbiter.update(102f, 100f, 220f, allowScale = true))
        assertEquals(TransformGestureMode.SCALE, arbiter.mode)
        val update = requireNotNull(arbiter.update(103f, 101f, 242f, allowScale = true))
        assertEquals(TransformGestureMode.SCALE, update.mode)
        assertEquals(1.1f, update.scaleFactor, 0.0001f)
    }

    @Test
    fun zoomLockPreventsPurePinchFromCommitting() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)

        assertNull(arbiter.update(100f, 100f, 240f, allowScale = false))
        assertEquals(TransformGestureMode.UNDECIDED, arbiter.mode)
    }

    @Test
    fun cancelResetsCommittedGestureAndAllowsCleanRestart() {
        val arbiter = TransformGestureArbiter(panSlop = 8f, scaleSlop = 16f)
        arbiter.begin(100f, 100f, 200f)
        arbiter.update(110f, 100f, 200f, allowScale = true)
        assertEquals(TransformGestureMode.PAN, arbiter.mode)

        arbiter.cancel()
        assertEquals(TransformGestureMode.UNDECIDED, arbiter.mode)
        assertNull(arbiter.update(300f, 300f, 400f, allowScale = true))
        assertEquals(TransformGestureMode.UNDECIDED, arbiter.mode)
    }
}
