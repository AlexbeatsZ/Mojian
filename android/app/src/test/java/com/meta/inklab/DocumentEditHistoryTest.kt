package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class DocumentEditHistoryTest {
    private data class Page(val ink: List<Int>, val text: String = "", val image: Int = 0)

    @Test fun eraseMoveTextAndImageEditsShareChronologicalUndoAndRedo() {
        var page = Page(listOf(10, 20))
        var viewport = 100
        val history = DocumentEditHistory({ page }, { page = it })
        history.reset()
        val original = page
        page = page.copy(ink = listOf(10)); history.changed()
        val erased = page
        page = page.copy(ink = listOf(30)); history.changed()
        val moved = page
        page = page.copy(text = "hello", image = 90); history.changed()
        val edited = page
        viewport = 240
        assertTrue(history.undo()); assertEquals(moved, page)
        assertTrue(history.undo()); assertEquals(erased, page)
        assertTrue(history.undo()); assertEquals(original, page)
        assertFalse(history.undo()) // Never erase existing saved ink as an undo fallback.
        assertEquals(240, viewport)
        repeat(3) { assertTrue(history.redo()) }
        assertEquals(edited, page)
        assertFalse(history.redo())
    }

    @Test fun oneGestureGroupsAllSamplesAndLayersAndCapturesOnlyAtRelease() {
        var page = Page(listOf(1, 2, 3))
        var captures = 0
        val history = DocumentEditHistory({ captures++; page }, { page = it })
        history.reset()
        val original = page
        history.transaction {
            repeat(100) { page = page.copy(ink = listOf(it)); history.changed() }
            history.transaction { page = page.copy(text = "moved", image = 12); history.changed() }
            assertEquals(1, captures)
            assertFalse(history.undo())
        }
        assertEquals(2, captures)
        assertTrue(history.undo()); assertEquals(original, page)
        assertFalse(history.undo())
        assertTrue(history.redo()); assertEquals(Page(listOf(99), "moved", 12), page)
    }

    @Test fun noOpGestureAndNavigationPreserveRedoButNewDocumentEditDropsIt() {
        var value = 0
        val history = DocumentEditHistory({ value }, { value = it })
        history.reset()
        value = 1; history.changed(); history.undo()
        history.transaction { history.changed() }
        assertTrue(history.redo()); assertEquals(1, value)
        history.undo(); value = 2; history.changed()
        assertFalse(history.redo())
    }

    @Test fun resetDropsPriorPageAndCapacityKeepsNewestEdits() {
        var value = 0
        val history = DocumentEditHistory({ value }, { value = it }, capacity = 3)
        history.reset()
        repeat(6) { value++; history.changed() }
        repeat(3) { assertTrue(history.undo()) }
        assertEquals(3, value); assertFalse(history.undo())
        history.reset(); assertFalse(history.undo()); assertFalse(history.redo())
        history.clear(); value = 42; history.changed(); assertFalse(history.undo())
    }

    @Test fun restoringCallbacksCannotRecordNewEditsAndFailureRetainsHistory() {
        var value = 0
        var fail = false
        lateinit var history: DocumentEditHistory<Int>
        history = DocumentEditHistory({ value }, {
            if (fail) error("restore failed")
            value = it; history.changed()
        })
        history.reset(); value = 1; history.changed()
        fail = true
        assertThrows(IllegalStateException::class.java) { history.undo() }
        fail = false
        assertTrue(history.undo()); assertTrue(history.redo()); assertEquals(1, value)
        assertFalse(history.redo())
    }
}
