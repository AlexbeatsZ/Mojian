package com.meta.inklab

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Page snapshots are serialized on one worker; navigation/lifecycle boundaries flush it. */
class PageWriteQueue {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val failures = java.util.concurrent.ConcurrentHashMap<String,Exception>()
    private val revisions = mutableMapOf<String,Long>() // worker-owned
    val isIdle get() = pending.get() == 0
    val hasFailures get() = failures.isNotEmpty()

    fun submit(repository: PageContentRepository, live: PageInsertDocument, snapshot: PageInsertDocument,
        saved: () -> Unit = {}, failed: (Exception)->Unit) {
        pending.incrementAndGet()
        val key = "${repository.root}/${live.notebookId}/${live.pageId}"
        executor.execute {
            try {
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
    fun close() { executor.shutdown() }
}
