package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class WeakIdentityCacheTest {
    @Test fun equalObjectsKeepIndependentSourceMetadataAndCanBeReplaced() {
        data class Key(val id: Int)
        val cache = WeakIdentityCache<Key, String>()
        val first = Key(1)
        val second = Key(1)
        cache[first] = "first outline"
        cache[second] = "second outline"
        assertEquals("first outline", cache[first])
        assertEquals("second outline", cache[second])
        cache[first] = "transformed outline"
        assertEquals("transformed outline", cache[first])
        assertEquals("second outline", cache[second])
        assertNull(cache[Key(1)])
    }
}
