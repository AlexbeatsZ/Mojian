package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class PageViewportMemoryTest {
    @Test fun refreshingErasedPageContentRetainsTheLatestZoomAndPosition() {
        val memory = PageViewportMemory<String>()
        val viewport = ViewportState().apply {
            setScaleAround(4f, 300f, 180f)
            applyGesture(0f, 0f, -70f, 55f, 1f)
        }
        memory.remember("notebook/page", viewport.snapshot())
        // A sync reload creates new page content and a new binding; no content lives in this cache.
        val rebound = ViewportState()
        rebound.restore(requireNotNull(memory.recall("notebook/page")))
        assertEquals(viewport.snapshot(), rebound.snapshot())
        assertEquals(4f, rebound.scale, 0f)
    }

    @Test fun pagesRetainIndependentViewportsAndDeletionForgetsOnlyItsOwnState() {
        val memory = PageViewportMemory<String>()
        memory.remember("one/page", ViewportSnapshot(2f, 10f, 20f))
        memory.remember("two/page", ViewportSnapshot(8f, 30f, 40f))
        memory.remember("one/page", ViewportSnapshot(3f, 50f, 60f))
        assertEquals(ViewportSnapshot(3f, 50f, 60f), memory.recall("one/page"))
        memory.forget("one/page")
        assertNull(memory.recall("one/page"))
        assertEquals(ViewportSnapshot(8f, 30f, 40f), memory.recall("two/page"))
    }
}
