package com.meta.inklab

class NavigationMutation {
    private val cleanup = mutableListOf<() -> Unit>()

    fun afterCommit(action: () -> Unit) { cleanup += action }

    fun commit(publish: () -> Unit) {
        publish()
        cleanup.forEach { it() }
        cleanup.clear()
    }
}
