package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class OutputAppearanceTest {
    @Test fun lightImportNeverAsksOrInverts() {
        ImageImportDisplayMode.entries.forEach { assertEquals(false, ImageImportPolicy.resolve(it, false)) }
        assertEquals(true, ImageImportPolicy.resolve(ImageImportDisplayMode.INVERT, true))
        assertNull(ImageImportPolicy.resolve(ImageImportDisplayMode.ASK, true))
    }

    @Test fun outputPreservesImagesAndPrintTogglesOnlyChosenDarkImages() {
        assertTrue(OutputAppearance().imageInverted("a", true, true))
        assertFalse(OutputAppearance().imageInverted("b", false, true))
        val print = OutputAppearance(printing = true, invertedImageIds = setOf("a", "b"))
        assertFalse(print.imageInverted("a", true, true))
        assertTrue(print.imageInverted("b", false, true))
        assertTrue(print.imageInverted("c", true, true))
        assertFalse(print.imageInverted("b", false, false))
        assertTrue(print.imageInverted("a", true, false))
        assertFalse(OutputAppearance(printing = true).imageInverted("a", true, true))
    }

    @Test fun printMapsWhiteAndLogicalWhiteToBlackWithoutTouchingOtherColorsOrAlpha() {
        for (color in listOf(0xFFFFFFFF.toInt(), 0xFFEFEFEF.toInt(), 0xFFF4F4F4.toInt()))
            assertEquals(0xFF000000.toInt(), OutputAppearance.printInk(color))
        assertEquals(0x80000000.toInt(), OutputAppearance.printInk(0x80FFFFFF.toInt()))
        for (color in listOf(0xFF185ABD.toInt(), 0xFFE83139.toInt(), 0xFFE7E7E7.toInt(), 0xFF000000.toInt()))
            assertEquals(color, OutputAppearance.printInk(color))
        assertEquals(0xFFFFFFFF.toInt(), OutputAppearance.printPaper(0xFF1B1B1B.toInt()))
        assertEquals(0xFF1D2B38.toInt(), OutputAppearance.printPaper(0xFF1D2B38.toInt()))
    }
}
