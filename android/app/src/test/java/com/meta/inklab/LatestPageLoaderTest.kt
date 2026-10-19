package com.meta.inklab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LatestPageLoaderTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() { tasks.removeFirst().run() }
    }

    @Test fun diskPreparationAndUiBindingAreSeparateAndNewestRequestWins() {
        val worker = Queue()
        val ui = Queue()
        val loader = LatestPageLoader<String, String>(worker) { ui.execute(it) }
        val bound = mutableListOf<String>()
        loader.load("old", { "old content" }) { bound += it.getOrThrow() }
        assertTrue(loader.isLoading)
        assertTrue(bound.isEmpty())
        worker.next()
        assertTrue(bound.isEmpty())
        loader.load("new", { "new content" }) { bound += it.getOrThrow() }
        ui.next() // An old completion was already posted when the user selected another page.
        assertTrue(bound.isEmpty())
        assertEquals("new", loader.loadingKey)
        worker.next()
        ui.next()
        assertEquals(listOf("new content"), bound)
        assertFalse(loader.isLoading)
    }

    @Test fun skippedAndCancelledRequestsCannotLoadOrBindAnObsoletePage() {
        val worker = Queue()
        val loader = LatestPageLoader<String, String>(worker) { it() }
        var prepared = 0
        var bound = 0
        loader.load("old", { prepared++; "old" }) { bound++ }
        loader.load("new", { prepared++; "new" }) { bound++ }
        worker.next()
        assertEquals(0, prepared)
        loader.cancel()
        worker.next()
        assertEquals(0, prepared)
        assertEquals(0, bound)
        assertFalse(loader.isLoading)
    }

    @Test fun refusedPageReadsReturnAnErrorInsteadOfAnEmptyEditableDocument() {
        val worker = Queue()
        val loader = LatestPageLoader<String, String>(worker) { it() }
        var failure: Throwable? = null
        loader.load("page", { error("not received") }) { failure = it.exceptionOrNull() }
        worker.next()
        assertEquals("not received", failure?.message)
        assertFalse(loader.isLoading)
    }

    @Test fun slowSaveBarrierDoesNotBlockNavigationAndActiveDecodingCanSeeCancellation() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val bound = java.util.Collections.synchronizedList(mutableListOf<String>())
        val loader = LatestPageLoader<String, String>(executor) { it() }
        try {
            loader.load("slow", { current ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertFalse(current())
                "obsolete"
            }) { bound += it.getOrThrow() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            loader.load("latest", { "latest" }) { bound += it.getOrThrow(); finished.countDown() }
            assertEquals("latest", loader.loadingKey)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("latest"), bound)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
