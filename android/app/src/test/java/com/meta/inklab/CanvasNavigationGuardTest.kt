package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class CanvasNavigationGuardTest {
    @Test fun secondFingerPreemptsSecondaryAndRemainingFingerKeepsOwnership() {
        val guard = CanvasNavigationGuard()
        assertEquals(NavigationCapture.NONE, guard.observe(1))
        assertEquals(NavigationCapture.STARTED, guard.observe(2))
        assertTrue(guard.active)
        assertEquals(NavigationCapture.CONTINUING, guard.observe(2))
        assertEquals(NavigationCapture.CONTINUING, guard.observe(1))
        assertTrue(guard.active)
        assertEquals(NavigationCapture.FINISHED, guard.observe(1, endsTouchSequence = true))
        assertFalse(guard.active)
        assertEquals(NavigationCapture.NONE, guard.observe(0))
    }

    @Test fun mouseReleaseAndPenHoverCannotEndTheTouchSequence() {
        val guard = CanvasNavigationGuard()
        guard.observe(2)
        repeat(3) { assertEquals(NavigationCapture.CONTINUING, guard.observe(0)) }
        assertTrue(guard.active)
        assertEquals(NavigationCapture.CONTINUING, guard.observe(1))
        assertEquals(NavigationCapture.CONTINUING, guard.observe(2))
        assertEquals(NavigationCapture.FINISHED, guard.observe(2, endsTouchSequence = true))
        assertEquals(NavigationCapture.NONE, guard.observe(0))
    }

    @Test fun pauseCancelsCaptureAndNextGestureCanStartNormally() {
        val guard = CanvasNavigationGuard()
        guard.observe(2)
        guard.cancel()
        assertFalse(guard.active)
        assertEquals(NavigationCapture.NONE, guard.observe(1))
        assertEquals(NavigationCapture.STARTED, guard.observe(2))
    }
}
