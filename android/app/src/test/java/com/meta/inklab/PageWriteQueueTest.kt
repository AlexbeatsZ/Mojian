package com.meta.inklab

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageWriteQueueTest {
    @Test
    fun supersededSnapshotsOfSamePageAreSkippedAndPauseFlushIsBounded() {
        val root = Files.createTempDirectory("mojian-write-coalesce").toFile()
        val queue = PageWriteQueue()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val repository = PageContentRepository(root)
            val live = repository.load("book", "page")
            fun snapshot(vararg ids: String) = PageInsertDocument("book", "page",
                ids.mapTo(mutableListOf()) { InsertObject.NoteLink(it, 1f, 2f, 300f, 32f, "b", "s", "p", it) },
                org.json.JSONArray(), org.json.JSONObject(), null)
            val saved = mutableListOf<String>()
            queue.submit(repository, live, snapshot("a"), saved = {
                synchronized(saved) { saved += "a" }
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }) { }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            queue.submit(repository, live, snapshot("a", "b"), saved = { synchronized(saved) { saved += "b" } }) { }
            queue.submit(repository, live, snapshot("a", "b", "c"), saved = { synchronized(saved) { saved += "c" } }) { }

            assertFalse(queue.flush(50))
            release.countDown()
            assertTrue(queue.flush(10_000))
            assertTrue(queue.isIdle)
            assertEquals(listOf("a", "c"), synchronized(saved) { saved.toList() })
            assertEquals(listOf("a", "b", "c"), repository.load("book", "page").objects.map { it.id })
        } finally { release.countDown(); queue.close(); root.deleteRecursively() }
    }
}
