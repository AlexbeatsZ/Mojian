package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolShelfTest {
    @Test
    fun defaultsContainRequiredDrawingToolsAndPalette() {
        val shelf = ToolShelf()

        assertTrue(InkPalette.entries.all { color -> shelf.tools.any { it.kind == DrawingToolKind.PEN && it.color == color } })
        assertTrue(shelf.tools.any { it.kind == DrawingToolKind.HIGHLIGHTER })
        assertTrue(shelf.tools.any { it.kind == DrawingToolKind.ERASER })
        assertTrue(shelf.tools.any { it.kind == DrawingToolKind.LASSO })
    }

    @Test
    fun addedToolBecomesSelectedAndCanBeDeleted() {
        val shelf = ToolShelf()
        val originalSize = shelf.tools.size

        val added = shelf.add(DrawingToolKind.HIGHLIGHTER)
        assertEquals(added.id, shelf.selected.id)
        assertEquals(originalSize + 1, shelf.tools.size)

        assertTrue(shelf.deleteSelected())
        assertEquals(originalSize, shelf.tools.size)
    }

    @Test
    fun lastToolCannotBeDeleted() {
        val shelf = ToolShelf(listOf(ToolPreset("only", DrawingToolKind.LASSO)))

        assertFalse(shelf.deleteSelected())
        assertEquals("only", shelf.selected.id)
    }
}
