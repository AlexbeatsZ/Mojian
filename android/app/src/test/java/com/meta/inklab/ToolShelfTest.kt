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
    fun addedToolBecomesSelected() {
        val shelf = ToolShelf()
        val originalSize = shelf.tools.size

        val added = shelf.add(DrawingToolKind.HIGHLIGHTER)
        assertEquals(added.id, shelf.selected.id)
        assertEquals(originalSize + 1, shelf.tools.size)

    }

    @Test
    fun missingRequiredToolsAreRestored() {
        val shelf = ToolShelf(listOf(ToolPreset("only", DrawingToolKind.LASSO)))

        assertTrue(ToolShelf.defaults().all { required -> shelf.tools.any { it.id == required.id } })
        assertEquals("only", shelf.selected.id)
    }

    @Test
    fun jsonRoundTripPreservesCustomizedToolsAndParameters() {
        val shelf = ToolShelf()
        val added = shelf.add(DrawingToolKind.PEN)
        added.color = InkPalette.RED
        added.widthMm = 1.2f
        added.pressurePercent = 250
        added.inkFlowPercent = 150
        added.pressureEraseEnabled = false

        val highlighter = shelf.tools.first { it.kind == DrawingToolKind.HIGHLIGHTER }
        highlighter.widthMm = 8.0f
        highlighter.opacityPercent = 50

        val eraser = shelf.tools.first { it.kind == DrawingToolKind.ERASER }
        eraser.eraserRadiusMm = 6.5f

        val lasso = shelf.tools.first { it.kind == DrawingToolKind.LASSO }
        lasso.lassoShape = LassoShape.RECTANGLE

        shelf.select(added.id)

        val json = shelf.toJson()
        val restored = ToolShelf.fromJson(json)

        org.junit.Assert.assertNotNull(restored)
        assertEquals(shelf.tools.size, restored!!.tools.size)
        assertEquals(added.id, restored.selectedId)

        val restoredAdded = restored.tools.first { it.id == added.id }
        assertEquals(InkPalette.RED, restoredAdded.color)
        assertEquals(1.2f, restoredAdded.widthMm, 0.001f)
        assertEquals(250, restoredAdded.pressurePercent)
        assertEquals(150, restoredAdded.inkFlowPercent)
        assertFalse(restoredAdded.pressureEraseEnabled)

        val restoredHighlighter = restored.tools.first { it.kind == DrawingToolKind.HIGHLIGHTER }
        assertEquals(8.0f, restoredHighlighter.widthMm, 0.001f)
        assertEquals(50, restoredHighlighter.opacityPercent)

        val restoredEraser = restored.tools.first { it.kind == DrawingToolKind.ERASER }
        assertEquals(6.5f, restoredEraser.eraserRadiusMm, 0.001f)

        val restoredLasso = restored.tools.first { it.kind == DrawingToolKind.LASSO }
        assertEquals(LassoShape.RECTANGLE, restoredLasso.lassoShape)
    }

    @Test
    fun restoreFromUpdatesInPlace() {
        val target = ToolShelf()
        val source = ToolShelf()
        val pen = source.add(DrawingToolKind.PEN)
        pen.widthMm = 0.8f

        target.restoreFrom(source)

        assertEquals(source.tools.size, target.tools.size)
        assertEquals(pen.id, target.selectedId)
        assertEquals(0.8f, target.tools.first { it.id == pen.id }.widthMm, 0.001f)
    }
}
