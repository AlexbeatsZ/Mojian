package com.meta.inklab

/**
 * State machine for Notein-style temporary pressure erasing. A completed pen lift arms the next
 * high-pressure dwell; activation lasts only until the stylus is lifted.
 */
class PressureEraserController(
    var enabled: Boolean = true,
    var pressureThreshold: Float = 0.78f,
    var dwellMillis: Long = 140L,
    var stationarySlop: Float = 6f,
    var travelLimitMm: Float = 1f,
) {
    private val travel = PressureEraseTravelGuard()
    val isTravelLimitExceeded get() = travel.exceeded
    var isErasing: Boolean = false
        private set
    var isArmed: Boolean = false
        private set
    private var candidateSinceMillis: Long? = null
    private var candidateAnchorX = 0f
    private var candidateAnchorY = 0f

    fun beginContact(documentXmm: Float, documentYmm: Float) {
        travel.begin(documentXmm, documentYmm, travelLimitMm)
        cancelCandidate()
    }

    fun observeContactTravel(documentXmm: Float, documentYmm: Float) {
        if (isErasing) return // Once activated, the eraser may travel freely until lift.
        travel.observe(documentXmm, documentYmm)
        if (travel.exceeded) cancelCandidate()
    }

    fun updateSample(
        pressure: Float,
        viewX: Float,
        viewY: Float,
        eventTimeMillis: Long,
    ): Long? {
        if (!enabled || !isArmed || isErasing || travel.exceeded || pressure < pressureThreshold) {
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
        if (!enabled || !isArmed || isErasing || travel.exceeded || nowMillis - since < dwellMillis) return false
        isErasing = true
        candidateSinceMillis = null
        return true
    }

    fun onStylusLift(completedInk: Boolean) {
        travel.reset()
        if (completedInk) isArmed = true
        isErasing = false
        candidateSinceMillis = null
    }

    fun cancelCandidate() {
        candidateSinceMillis = null
    }

    fun cancelGesture() {
        travel.reset()
        isErasing = false
        candidateSinceMillis = null
    }
}

object PressureEraserPolicy {
    fun supportsPressureErase(kind: DrawingToolKind): Boolean =
        kind == DrawingToolKind.PEN || kind == DrawingToolKind.HIGHLIGHTER

    fun canTrigger(kind: DrawingToolKind, eraserEnabled: Boolean): Boolean =
        eraserEnabled && supportsPressureErase(kind)
}
