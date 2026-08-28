package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeViewportBoundsTest {
    @Test
    fun `intersection and temporary vertical translation use document bounds`() {
        val viewport = StrokeViewportBounds(0f, 0f, 100f, 100f)
        val stroke = StrokeViewportBounds(20f, 140f, 80f, 160f)

        assertFalse(stroke.intersects(viewport))
        assertTrue(stroke.translated(-80f).intersects(viewport))
    }
}
