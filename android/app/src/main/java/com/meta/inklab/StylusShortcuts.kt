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
    val eventTime: Long = 0L,
)

enum class StylusGestureKind {
    SINGLE_TAP,
    DOUBLE_TAP,
    HOLD_START,
    HOLD_END,
}

data class StylusGestureDecision(
    val key: XiaomiStylusKey,
    val kind: StylusGestureKind,
)

/**
 * Pure timing state machine. The caller advances it from both hardware events and its next
 * deadline, which keeps double-tap suppression and hold restoration deterministic.
 */
class XiaomiStylusGestureInterpreter(
    var doubleTapMillis: Long = 260L,
    var holdMillis: Long = 450L,
    /** Keys without a double-tap action report SINGLE_TAP on release instead of waiting. */
    var waitsForDoubleTap: (XiaomiStylusKey) -> Boolean = { true },
) {
    private data class State(
        var isDown: Boolean = false,
        var downAt: Long = 0L,
        var secondTap: Boolean = false,
        var holdStarted: Boolean = false,
        var pendingSingleAt: Long? = null,
    )

    private val states = XiaomiStylusKey.entries.associateWith { State() }

    fun accept(signal: StylusShortcutSignal): List<StylusGestureDecision> {
        if (signal.phase == StylusKeyPhase.DOWN && signal.repeatCount > 0) return emptyList()
        val now = signal.heldMillis.takeIf { signal.phase == StylusKeyPhase.UP }?.let { _ ->
            // KeyEvent event times are supplied through acceptAt in production; this fallback keeps
            // direct signal tests deterministic.
            states.getValue(signal.key).downAt + signal.heldMillis
        } ?: states.getValue(signal.key).downAt
        return acceptAt(signal, now)
    }

    fun acceptAt(signal: StylusShortcutSignal, eventTime: Long): List<StylusGestureDecision> {
        val result = advance(eventTime).toMutableList()
        val state = states.getValue(signal.key)
        when (signal.phase) {
            StylusKeyPhase.DOWN -> {
                if (signal.repeatCount > 0 || state.isDown) return result
                state.secondTap = state.pendingSingleAt?.let { eventTime - it <= doubleTapMillis } == true
                if (state.secondTap) state.pendingSingleAt = null
                state.isDown = true
                state.downAt = eventTime
                state.holdStarted = false
            }
            StylusKeyPhase.UP -> {
                if (!state.isDown) return result
                if (state.holdStarted) {
                    result += StylusGestureDecision(signal.key, StylusGestureKind.HOLD_END)
                } else if (state.secondTap) {
                    result += StylusGestureDecision(signal.key, StylusGestureKind.DOUBLE_TAP)
                } else if (!waitsForDoubleTap(signal.key)) {
                    result += StylusGestureDecision(signal.key, StylusGestureKind.SINGLE_TAP)
                } else {
                    state.pendingSingleAt = eventTime
                }
                state.isDown = false
                state.secondTap = false
                state.holdStarted = false
            }
        }
        return result
    }

    fun advance(now: Long): List<StylusGestureDecision> =
        buildList {
            states.forEach { (key, state) ->
                if (state.isDown && !state.holdStarted && now - state.downAt >= holdMillis) {
                    state.holdStarted = true
                    state.secondTap = false
                    state.pendingSingleAt = null
                    add(StylusGestureDecision(key, StylusGestureKind.HOLD_START))
                }
                val pendingAt = state.pendingSingleAt
                if (pendingAt != null && now - pendingAt >= doubleTapMillis) {
                    state.pendingSingleAt = null
                    add(StylusGestureDecision(key, StylusGestureKind.SINGLE_TAP))
                }
            }
        }

    fun nextDeadline(): Long? =
        states.values
            .flatMap { state ->
                buildList {
                    if (state.isDown && !state.holdStarted) add(state.downAt + holdMillis)
                    state.pendingSingleAt?.let { add(it + doubleTapMillis) }
                }
            }.minOrNull()

    fun reset(): List<StylusGestureDecision> =
        buildList {
            states.forEach { (key, state) ->
                if (state.holdStarted) add(StylusGestureDecision(key, StylusGestureKind.HOLD_END))
                state.isDown = false
                state.secondTap = false
                state.holdStarted = false
                state.pendingSingleAt = null
            }
        }
}

/** Return true when a registered feature handles the shortcut. */
fun interface StylusShortcutHandler {
    fun onStylusShortcut(signal: StylusShortcutSignal): Boolean
}

/**
 * Clean adapter boundary for this tablet's Bluetooth pen keyboard protocol. Recognized pen events
 * are consumed and forwarded to the app-owned configurable gesture state machine. PageUp/PageDown
 * from every other keyboard remain available to the Android hierarchy.
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
                eventTime = eventTime,
            )
        }

        private const val XIAOMI_SMART_PEN_KEYBOARD = "Xiaomi Smart Pen Keyboard"
    }
}
