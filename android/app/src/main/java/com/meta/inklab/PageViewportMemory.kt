package com.meta.inklab

/** View state is local and survives content invalidation after a Syncthing refresh. */
class PageViewportMemory<K> {
    private val pages = mutableMapOf<K, ViewportSnapshot>()
    fun remember(key: K, viewport: ViewportSnapshot) { pages[key] = viewport }
    fun recall(key: K): ViewportSnapshot? = pages[key]
    fun forget(key: K) { pages.remove(key) }
}
