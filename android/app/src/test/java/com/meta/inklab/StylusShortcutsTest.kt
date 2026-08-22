package com.meta.inklab

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
