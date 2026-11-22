package com.meta.inklab

import com.meta.inklab.PageLoadingFeedback.Visual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoadingFeedbackTest {
    private class FakeClock {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        fun schedule(delay: Long, task: Runnable) { tasks += now + delay to task }
        fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due)
            due.forEach { it.second.run() }
        }
    }

    private val clock = FakeClock()
    private val rendered = mutableListOf<Visual>()
    private val feedback = PageLoadingFeedback(clock::schedule, clock::cancel) { rendered += it }

    @Test
    fun fastLoadBlocksInputButNeverShowsIndicator() {
        feedback.loading()
        assertTrue(feedback.active)
        clock.advance(PageLoadingFeedback.REVEAL_DELAY_MS - 1)
        feedback.finished()
        clock.advance(1_000)
        assertFalse(feedback.active)
        assertEquals(emptyList<Visual>(), rendered)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test
    fun slowLoadRevealsProgressAfterDelayThenHides() {
        feedback.loading()
        clock.advance(PageLoadingFeedback.REVEAL_DELAY_MS)
        assertEquals(listOf(Visual.PROGRESS), rendered)
        feedback.finished()
        assertEquals(listOf(Visual.PROGRESS, Visual.HIDDEN), rendered)
    }

    @Test
    fun supersedingRequestKeepsOriginalDelayAndVisibleIndicator() {
        feedback.loading()
        clock.advance(200)
        feedback.loading()
        clock.advance(PageLoadingFeedback.REVEAL_DELAY_MS - 200)
        assertEquals(listOf(Visual.PROGRESS), rendered)
        feedback.loading()
        assertEquals(listOf(Visual.PROGRESS), rendered)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test
    fun failureShowsImmediatelyAndNextSelectionClearsIt() {
        feedback.loading()
        feedback.failed()
        assertTrue(feedback.active)
        assertEquals(listOf(Visual.FAILED), rendered)
        clock.advance(1_000)
        assertEquals(listOf(Visual.FAILED), rendered)
        feedback.loading()
        assertEquals(listOf(Visual.FAILED, Visual.HIDDEN), rendered)
        clock.advance(PageLoadingFeedback.REVEAL_DELAY_MS)
        assertEquals(listOf(Visual.FAILED, Visual.HIDDEN, Visual.PROGRESS), rendered)
    }
}
