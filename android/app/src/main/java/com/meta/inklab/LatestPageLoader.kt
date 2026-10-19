package com.meta.inklab

import java.util.concurrent.Executor

/** Worker preparation never blocks navigation; only the newest request may bind on the UI. */
class LatestPageLoader<K, V>(private val worker: Executor, private val deliver: (() -> Unit) -> Unit) {
    @Volatile private var generation = 0L
    var loadingKey: K? = null
        private set
    var isLoading = false
        private set

    fun load(key: K, prepare: (isCurrent: () -> Boolean) -> V, completed: (Result<V>) -> Unit) {
        val request = ++generation
        loadingKey = key
        isLoading = true
        worker.execute {
            if (request != generation) return@execute
            val result = runCatching { prepare { request == generation } }
            deliver {
                if (request == generation) {
                    isLoading = false
                    loadingKey = null
                    completed(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        loadingKey = null
        isLoading = false
    }
}
