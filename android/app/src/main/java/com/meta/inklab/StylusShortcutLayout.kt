package com.meta.inklab

import android.content.Context
import android.view.KeyEvent
import android.widget.LinearLayout

/** Keeps the app's dedicated pen keys ahead of an IME's physical-keyboard processing. */
internal class StylusShortcutLayout(
    context: Context,
    private val onStylusKey: (KeyEvent) -> Boolean,
) : LinearLayout(context) {
    init {
        // ViewGroup forwards pre-IME events only along its focus chain. Keep a canvas-only
        // screen in that chain too; an editor can still request focus normally.
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        onStylusKey(event) || super.dispatchKeyEventPreIme(event)
}
