package com.meta.inklab

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Page snapshots are serialized on one worker; navigation/lifecycle boundaries flush it.
 * Every snapshot is a whole page, so an older queued snapshot of the same page is skipped once a
 * newer one was submitted: continuous writing on a dense page must not build a backlog that a
 * lifecycle flush then waits on (ANR). Submission order is an explicit ticket, not map order.
 */
class PageWriteQueue {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val tickets = AtomicLong()
    private val latest = java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String,Exception>()
    private val revisions = mutableMapOf<String,Long>() // worker-owned
    val isIdle get() = pending.get() == 0
    val hasFailures get() = failures.isNotEmpty()

    fun submit(repository: PageContentRepository, live: PageInsertDocument, snapshot: PageInsertDocument,
        saved: () -> Unit = {}, failed: (Exception)->Unit) {
        pending.incrementAndGet()
        val key = "${repository.root}/${live.notebookId}/${live.pageId}"
        val ticket = tickets.incrementAndGet()
        latest[key] = ticket
        executor.execute {
            try {
                if (latest[key] != ticket) return@execute
                synchronized(live) {
                    snapshot.diskStamp = live.diskStamp
                    snapshot.diskFingerprint = live.diskFingerprint
                    snapshot.sharedSource.put("revision",revisions[key] ?: live.sharedSource.optLong("revision"))
                }
                val originalSource = snapshot.sharedSource
                repository.save(snapshot)
                revisions[key] = snapshot.sharedSource.optLong("revision")
                synchronized(live) {
                    live.diskStamp = snapshot.diskStamp
                    live.diskFingerprint = snapshot.diskFingerprint
                    if(live.sharedSource === originalSource) live.sharedSource = snapshot.sharedSource
                }
                failures.remove(key)
                saved()
            } catch(error: Exception) {
                failures[key] = error
                failed(error)
            } finally { pending.decrementAndGet() }
        }
    }

    fun flush() {
        executor.submit {}.get()
        failures.values.firstOrNull()?.let { throw it }
    }

    /** Bounded flush for the UI thread; false means writes are still running on the worker. */
    fun flush(timeoutMillis: Long): Boolean {
        try { executor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { return false }
        failures.values.firstOrNull()?.let { throw it }
        return true
    }
    fun close() { executor.shutdown() }
}

/**
 * Idle-based page persistence (OneNote/Saber style): a dense page is rewritten whole, so save
 * after the pen rests instead of after every stroke, but never let continuous writing keep a
 * page unsaved longer than [MAX_DIRTY_MS]. Lifecycle and navigation boundaries still flush.
 */
object PageSavePolicy {
    const val IDLE_DELAY_MS = 2_000L
    const val MAX_DIRTY_MS = 20_000L
    const val ACTIVE_STROKE_RETRY_MS = 500L

    fun delayMillis(dirtySinceMs: Long, nowMs: Long): Long =
        (MAX_DIRTY_MS - (nowMs - dirtySinceMs)).coerceIn(0L, IDLE_DELAY_MS)

    /** A save that comes due mid-stroke waits for pen-up unless the page is already overdue. */
    fun deferForActiveStroke(dirtySinceMs: Long, nowMs: Long): Boolean = nowMs - dirtySinceMs < MAX_DIRTY_MS
}
