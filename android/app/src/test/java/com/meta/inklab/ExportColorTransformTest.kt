package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportColorTransformTest {
    @Test
    fun darkThemeLogicalBlackReturnsToBlack() {
        assertEquals(0xFF141414.toInt(), ExportColorTransform.lightThemePixel(0xFFEFEFEF.toInt()))
        assertEquals(0x80141414.toInt(), ExportColorTransform.lightThemePixel(0x80E7E7E7.toInt()))
    }

    @Test
    fun coloredPensAreNotChanged() {
        assertEquals(0xFF185ABD.toInt(), ExportColorTransform.lightThemePixel(0xFF185ABD.toInt()))
        assertEquals(0xFFE83139.toInt(), ExportColorTransform.lightThemePixel(0xFFE83139.toInt()))
    }
}

