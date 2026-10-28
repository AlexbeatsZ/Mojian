package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneOrderCacheTest {
    @Test fun unchangedSceneReusesOrderButContentStrokeAndSourceChangesRebuild() {
        val cache = SceneOrderCache<String>()
        val source = Any()
        var preparations = 0
        fun prepare() = listOf("scene-${++preparations}")
        val original = cache.get(source, 1, 1, ::prepare)
        assertSame(original, cache.get(source, 1, 1, ::prepare))
        assertEquals(listOf("scene-2"), cache.get(source, 2, 1, ::prepare))
        assertEquals(listOf("scene-3"), cache.get(source, 2, 2, ::prepare))
        assertEquals(listOf("scene-4"), cache.get(Any(), 2, 2, ::prepare))
        assertEquals(4, preparations)
    }

    @Test fun failedPreparationDoesNotMarkTheNewRevisionAsCached() {
        val cache = SceneOrderCache<String>()
        val source = Any()
        cache.get(source, 1, 1) { listOf("old") }
        assertThrows(IllegalStateException::class.java) { cache.get(source, 2, 1) { error("failed") } }
        assertEquals(listOf("new"), cache.get(source, 2, 1) { listOf("new") })
    }
}
