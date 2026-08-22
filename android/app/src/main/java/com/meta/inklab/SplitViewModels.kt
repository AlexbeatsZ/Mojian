package com.meta.inklab

enum class SplitPaneSide {
    LEFT,
    RIGHT,
}

class SplitViewState {
    var enabled: Boolean = false
        private set

    var focusedSide: SplitPaneSide = SplitPaneSide.LEFT
        private set

    private var leftLocation: WorkspaceLocation? = null
    private var rightLocation: WorkspaceLocation? = null

    fun enter(current: WorkspaceLocation): Boolean {
        if (enabled) return false
        enabled = true
        focusedSide = SplitPaneSide.LEFT
        leftLocation = current
        rightLocation = current
        return true
    }

    fun location(side: SplitPaneSide): WorkspaceLocation? =
        if (side == SplitPaneSide.LEFT) leftLocation else rightLocation

    fun focusedLocation(): WorkspaceLocation? = location(focusedSide)

    fun focus(side: SplitPaneSide): Boolean {
        if (!enabled || focusedSide == side || location(side) == null) return false
        focusedSide = side
        return true
    }

    fun updateFocused(location: WorkspaceLocation) {
        if (!enabled) return
        setLocation(focusedSide, location)
    }

    fun setLocation(side: SplitPaneSide, location: WorkspaceLocation) {
        if (side == SplitPaneSide.LEFT) leftLocation = location else rightLocation = location
    }

    fun exitKeepingRight(): WorkspaceLocation? {
        if (!enabled) return null
        val kept = rightLocation ?: leftLocation
        enabled = false
        focusedSide = SplitPaneSide.LEFT
        leftLocation = kept
        rightLocation = null
        return kept
    }
}
