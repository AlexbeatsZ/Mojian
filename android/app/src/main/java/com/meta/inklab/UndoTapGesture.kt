package com.meta.inklab

/**
 * Undo-button taps: when enabled, a second tap within [DOUBLE_TAP_MILLIS] redoes instead of
 * undoing, so a single tap's undo waits for that interval. Any other input must [flush] a pending
 * undo first so it can never apply after a later edit. Keyboard and pen-key undo are unchanged.
 */
class UndoTapGesture(
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val undo: () -> Unit,
    private val redo: () -> Unit,
    private val enabled: () -> Boolean,
) {
    private val expire = Runnable { flush() }

    var isPending: Boolean = false
        private set

    fun tap() {
        if (!enabled()) {
            flush()
            undo()
            return
        }
        if (isPending) {
            isPending = false
            cancel(expire)
            redo()
            return
        }
        isPending = true
        schedule(DOUBLE_TAP_MILLIS, expire)
    }

    /** Applies a pending single-tap undo now. */
    fun flush() {
        if (!isPending) return
        isPending = false
        cancel(expire)
        undo()
    }

    companion object {
        const val DOUBLE_TAP_MILLIS = 300L
    }
}
