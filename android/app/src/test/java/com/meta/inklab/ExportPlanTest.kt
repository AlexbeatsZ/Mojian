package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPlanTest {
    @Test(expected = IllegalArgumentException::class)
    fun rangeBeyondCombinedPagesIsRejected() {
        ExportPlan.create(1, ExportOptions(pageRange="2")) {
            listOf(ExportSheet(StrokeViewportBounds(0f, 0f, 100f, 200f), 300f, 400f, 1f, 0f) to 0)
        }
    }

    @Test fun emptyScopeHasNoPhantomPage() {
        assertEquals(emptyList<ExportPlannedPage>(), ExportPlan.create(0, ExportOptions()) {
            error("Empty scope must not acquire a source")
        }.pages)
    }

    @Test fun pageRangeUsesCombinedPhysicalOrderAndPreservesSourceIndex() {
        val sheet = ExportSheet(StrokeViewportBounds(0f, 0f, 100f, 200f), 300f, 400f, 1f, 0f)
        val visited = mutableListOf<Int>()
        val plan = ExportPlan.create(3, ExportOptions(pageRange="2-4")) { index ->
            visited += index
            List(index + 1) { sheet to index }
        }
        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(listOf(1, 1, 2), plan.pages.map { it.sourceIndex })
        assertEquals(listOf(1, 1, 2), plan.pages.map { it.backgroundArgb })
    }
}
