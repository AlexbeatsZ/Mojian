package com.meta.inklab

import android.view.KeyEvent

enum class XiaomiStylusKey {
    PAGE_UP,
    PAGE_DOWN,
}

enum class StylusKeyPhase {
    DOWN,
    UP,
}

data class StylusShortcutSignal(
    val key: XiaomiStylusKey,
    val phase: StylusKeyPhase,
    val repeatCount: Int,
    val heldMillis: Long,
)

/** Return true when a registered feature handles the shortcut. */
fun interface StylusShortcutHandler {
    fun onStylusShortcut(signal: StylusShortcutSignal): Boolean
}

/**
 * Clean adapter boundary for this tablet's Bluetooth pen keyboard protocol. No actions are bound
 * yet; recognized pen events are consumed so they cannot accidentally scroll or adjust a focused
 * control. PageUp/PageDown from every other keyboard remain available to the Android hierarchy.
 */
class XiaomiStylusShortcutDispatcher(
    var handler: StylusShortcutHandler? = null,
) {
    fun dispatch(event: KeyEvent): Boolean {
        val signal =
            decode(
                deviceName = event.device?.name,
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                downTime = event.downTime,
                eventTime = event.eventTime,
            ) ?: return false
        return handler?.onStylusShortcut(signal) ?: true
    }

    companion object {
        internal fun decode(
            deviceName: String?,
            keyCode: Int,
            action: Int,
            repeatCount: Int,
            downTime: Long,
            eventTime: Long,
        ): StylusShortcutSignal? {
            if (deviceName != XIAOMI_SMART_PEN_KEYBOARD) return null
            val key =
                when (keyCode) {
                    KeyEvent.KEYCODE_PAGE_UP -> XiaomiStylusKey.PAGE_UP
                    KeyEvent.KEYCODE_PAGE_DOWN -> XiaomiStylusKey.PAGE_DOWN
                    else -> return null
                }
            val phase =
                when (action) {
                    KeyEvent.ACTION_DOWN -> StylusKeyPhase.DOWN
                    KeyEvent.ACTION_UP -> StylusKeyPhase.UP
                    else -> return null
                }
            return StylusShortcutSignal(
                key = key,
                phase = phase,
                repeatCount = repeatCount,
                heldMillis = (eventTime - downTime).coerceAtLeast(0L),
            )
        }

        private const val XIAOMI_SMART_PEN_KEYBOARD = "Xiaomi Smart Pen Keyboard"
    }
}
