package com.meta.inklab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewFilterTest {
    @Test
    fun whiteMapsExactlyToCanvasGray() {
        assertArrayEquals(intArrayOf(27, 27, 27), ImageViewFilter.transform(255, 255, 255))
        assertEquals(89.4118f, ImageViewFilter.INVERT_PERCENT, 0.0001f)
    }

    @Test
    fun blackMapsToNearWhiteWhileHueRotationKeepsNeutralsNeutral() {
        assertArrayEquals(intArrayOf(228, 228, 228), ImageViewFilter.transform(0, 0, 0))
    }

    @Test
    fun exportAlwaysBypassesTheViewFilter() {
        assertEquals(true, ImageViewFilter.shouldApply(viewInverted = true, forExport = false))
        assertEquals(false, ImageViewFilter.shouldApply(viewInverted = true, forExport = true))
        assertEquals(false, ImageViewFilter.shouldApply(viewInverted = false, forExport = false))
    }
}
