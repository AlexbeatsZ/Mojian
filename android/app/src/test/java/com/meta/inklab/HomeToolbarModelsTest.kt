package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeToolbarModelsTest {
    @Test
    fun tagMenuContainsOnlyImportantAndQuestion() {
        assertEquals(listOf("重要", "问题"), CanvasTagKind.entries.map { it.label })
    }

    @Test
    fun homeRibbonDoesNotExposeOutlookTasks() {
        assertFalse(HomeCommand.entries.any { it.id.contains("outlook") || it.label.contains("Outlook") })
    }

    @Test
    fun fullPageToolbarSupportsAddingAndRemovingAnyCatalogId() {
        val config = FullPageToolbarConfig(emptyList())

        assertTrue(config.add("home:tag"))
        assertTrue(config.contains("home:tag"))
        assertFalse(config.add("home:tag"))
        assertTrue(config.remove("home:tag"))
    }
}
