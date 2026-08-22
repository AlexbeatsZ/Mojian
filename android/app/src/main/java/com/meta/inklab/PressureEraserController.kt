package com.meta.inklab

/**
 * State machine for Notein-style temporary pressure erasing. A completed pen lift arms the next
 * high-pressure dwell; activation lasts only until the stylus is lifted.
 */
class PressureEraserController(
    var enabled: Boolean = true,
    var pressureThreshold: Float = 0.78f,
    val dwellMillis: Long = 140L,
    var stationarySlop: Float = 6f,
) {
    var isErasing: Boolean = false
        private set
    var isArmed: Boolean = false
        private set
    private var candidateSinceMillis: Long? = null
    private var candidateAnchorX = 0f
    private var candidateAnchorY = 0f

    fun updateSample(
        pressure: Float,
        viewX: Float,
        viewY: Float,
        eventTimeMillis: Long,
    ): Long? {
        if (!enabled || !isArmed || isErasing || pressure < pressureThreshold) {
            candidateSinceMillis = null
            return null
        }
        var since = candidateSinceMillis
        if (since == null) {
            since = eventTimeMillis
            candidateSinceMillis = since
            candidateAnchorX = viewX
            candidateAnchorY = viewY
        } else {
            val deltaX = viewX - candidateAnchorX
            val deltaY = viewY - candidateAnchorY
            if (deltaX * deltaX + deltaY * deltaY > stationarySlop * stationarySlop) {
                // Heavy writing is still writing. Only a new stationary dwell may erase.
                since = eventTimeMillis
                candidateSinceMillis = since
                candidateAnchorX = viewX
                candidateAnchorY = viewY
            }
        }
        return (dwellMillis - (eventTimeMillis - since)).coerceAtLeast(0L)
    }

    fun activate(nowMillis: Long): Boolean {
        val since = candidateSinceMillis ?: return false
        if (!enabled || !isArmed || isErasing || nowMillis - since < dwellMillis) return false
        isErasing = true
        candidateSinceMillis = null
        return true
    }

    fun onStylusLift(completedInk: Boolean) {
        if (completedInk) isArmed = true
        isErasing = false
        candidateSinceMillis = null
    }

    fun cancelCandidate() {
        candidateSinceMillis = null
    }
}
