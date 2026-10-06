package com.meta.inklab

import kotlin.math.hypot

/** Real pen travel in document millimeters. Pressure/dwell changes do not reset the latch. */
class PressureEraseTravelGuard {
    private var lastX = 0f
    private var lastY = 0f
    private var limit = 1f
    private var active = false
    var distanceMm = 0f; private set
    var exceeded = false; private set

    fun begin(xMm: Float, yMm: Float, limitMm: Float) {
        reset()
        active = true
        lastX = xMm; lastY = yMm; limit = limitMm
    }

    fun observe(xMm: Float, yMm: Float) {
        if (!active || exceeded) return
        distanceMm += hypot(xMm - lastX, yMm - lastY)
        lastX = xMm; lastY = yMm
        if (distanceMm > limit) exceeded = true
    }

    fun reset() { active = false; distanceMm = 0f; exceeded = false }
}
