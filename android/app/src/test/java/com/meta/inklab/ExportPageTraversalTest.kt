package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class ExportPageTraversalTest {
    @Test fun releasesSourceBeforePreparingNextAndReusesOnlyAdjacentSheets() {
        var active = 0
        val events = mutableListOf<String>()
        val sources = listOf(0, 0, 1, 2)
        ExportPageTraversal.visit(sources.indices.toList(), sources::get, { source ->
            assertEquals(0, active)
            active++
            events += "open:$source"
            AutoCloseable { active--; events += "close:$source" }
        }, { false }) { index, _ -> events += "render:$index" }
        assertEquals(0, active)
        assertEquals(listOf("open:0", "render:0", "render:1", "close:0", "open:1",
            "render:2", "close:1", "open:2", "render:3", "close:2"), events)
    }

    @Test fun failureClosesLeaseAndDoesNotPrepareAnotherSource() {
        var opened = 0
        var closed = 0
        try {
            ExportPageTraversal.visit(listOf(0, 1), { it }, {
                opened++
                AutoCloseable { closed++ }
            }, { false }) { _, _ -> error("render failed") }
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test fun cancellationBetweenSheetsReleasesCurrentLease() {
        var cancelled = false
        var opened = 0
        var closed = 0
        try {
            ExportPageTraversal.visit(listOf(0, 1, 2), { it / 2 }, {
                opened++
                AutoCloseable { closed++ }
            }, { cancelled }) { _, _ -> cancelled = true }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(1, opened)
        assertEquals(1, closed)
    }
}
