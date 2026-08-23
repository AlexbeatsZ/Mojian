package com.meta.inklab

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusShortcutsTest {
    @Test
    fun decodesOnlyObservedXiaomiPenKeyboardKeys() {
        val signal =
            XiaomiStylusShortcutDispatcher.decode(
                deviceName = "Xiaomi Smart Pen Keyboard",
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                downTime = 1_000L,
                eventTime = 1_350L,
            )

        assertEquals(XiaomiStylusKey.PAGE_DOWN, signal?.key)
        assertEquals(StylusKeyPhase.DOWN, signal?.phase)
        assertEquals(2, signal?.repeatCount)
        assertEquals(350L, signal?.heldMillis)
    }

    @Test
    fun doesNotCapturePageKeysFromOrdinaryKeyboard() {
        val signal =
            XiaomiStylusShortcutDispatcher.decode(
                deviceName = "USB Keyboard",
                keyCode = KeyEvent.KEYCODE_PAGE_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTime = 0L,
                eventTime = 0L,
            )

        assertNull(signal)
    }

    @Test
    fun waitsForDoubleTapWindowBeforeEmittingSingleTap() {
        val interpreter = XiaomiStylusGestureInterpreter(doubleTapMillis = 260, holdMillis = 450)

        assertTrue(interpreter.acceptAt(signal(StylusKeyPhase.DOWN), 1_000).isEmpty())
        assertTrue(interpreter.acceptAt(signal(StylusKeyPhase.UP), 1_040).isEmpty())
        assertTrue(interpreter.advance(1_299).isEmpty())
        assertEquals(
            listOf(StylusGestureDecision(XiaomiStylusKey.PAGE_UP, StylusGestureKind.SINGLE_TAP)),
            interpreter.advance(1_300),
        )
    }

    @Test
    fun rapidSecondTapSuppressesSingleAndEmitsDouble() {
        val interpreter = XiaomiStylusGestureInterpreter(doubleTapMillis = 260, holdMillis = 450)
        interpreter.acceptAt(signal(StylusKeyPhase.DOWN), 1_000)
        interpreter.acceptAt(signal(StylusKeyPhase.UP), 1_040)
        interpreter.acceptAt(signal(StylusKeyPhase.DOWN), 1_180)

        assertEquals(
            listOf(StylusGestureDecision(XiaomiStylusKey.PAGE_UP, StylusGestureKind.DOUBLE_TAP)),
            interpreter.acceptAt(signal(StylusKeyPhase.UP), 1_215),
        )
        assertTrue(interpreter.advance(2_000).isEmpty())
    }

    @Test
    fun holdStartsAtConfiguredThresholdAndEndsOnRelease() {
        val interpreter = XiaomiStylusGestureInterpreter(doubleTapMillis = 260, holdMillis = 450)
        interpreter.acceptAt(signal(StylusKeyPhase.DOWN), 5_000)

        assertEquals(
            listOf(StylusGestureDecision(XiaomiStylusKey.PAGE_UP, StylusGestureKind.HOLD_START)),
            interpreter.advance(5_450),
        )
        assertEquals(
            listOf(StylusGestureDecision(XiaomiStylusKey.PAGE_UP, StylusGestureKind.HOLD_END)),
            interpreter.acceptAt(signal(StylusKeyPhase.UP, heldMillis = 700), 5_700),
        )
    }

    private fun signal(phase: StylusKeyPhase, heldMillis: Long = 0) =
        StylusShortcutSignal(
            key = XiaomiStylusKey.PAGE_UP,
            phase = phase,
            repeatCount = 0,
            heldMillis = heldMillis,
        )
}
