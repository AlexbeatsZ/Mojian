package com.meta.inklab

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LocalMutationEpoch private constructor() {
    private var epoch = 0L
    private var active = 0

    @Synchronized fun begin(): AutoCloseable {
        epoch++
        active++
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    active--
                    epoch++
                }
            }
        }
    }

    @Synchronized fun capture(): Long? = if (active == 0) epoch else null

    @Synchronized fun publishIfUnchanged(expected: Long, publish: () -> Unit): Boolean {
        if (active != 0 || epoch != expected) return false
        publish()
        return true
    }

    companion object {
        private val workspaces = ConcurrentHashMap<String, LocalMutationEpoch>()
        fun forWorkspace(root: File): LocalMutationEpoch = workspaces.computeIfAbsent(root.canonicalPath) { LocalMutationEpoch() }
    }
}
