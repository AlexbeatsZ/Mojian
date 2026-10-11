package com.meta.inklab

enum class NavigationCapture { NONE, STARTED, CONTINUING, FINISHED }

/** Two fingers own input until the last touch lifts, including intervening button events. */
class CanvasNavigationGuard {
    var active = false
        private set

    fun observe(fingerCount: Int, endsTouchSequence: Boolean = false): NavigationCapture {
        if (!active) {
            if (fingerCount < 2) return NavigationCapture.NONE
            active = true
            return NavigationCapture.STARTED
        }
        if (endsTouchSequence) {
            active = false
            return NavigationCapture.FINISHED
        }
        return NavigationCapture.CONTINUING
    }

    fun cancel() { active = false }
}
