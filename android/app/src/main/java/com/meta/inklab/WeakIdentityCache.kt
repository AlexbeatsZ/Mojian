package com.meta.inklab

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Reclaims values as soon as their keys die, even while no caller is reading the cache. */
internal class WeakIdentityCache<K : Any, V : Any> {
    private class Key<K : Any>(value: K, queue: ReferenceQueue<K>? = null) : WeakReference<K>(value, queue) {
        private val identity = System.identityHashCode(value)
        override fun hashCode() = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Key<*> && get()?.let { it === other.get() } == true)
    }

    private val collected = ReferenceQueue<K>()
    private val entries = ConcurrentHashMap<Key<K>, V>()

    init {
        Thread({
            try {
                while (true) entries.remove(collected.remove())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "Mojian-stroke-cache-reaper").apply { isDaemon = true; start() }
    }

    operator fun get(key: K): V? = entries[Key(key)]
    operator fun set(key: K, value: V) { entries[Key(key, collected)] = value }
}
