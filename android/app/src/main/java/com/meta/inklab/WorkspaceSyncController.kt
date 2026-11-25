package com.meta.inklab

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class WorkspaceSyncController(
    private val context: Context,
    private val applyIncoming: (() -> Unit) -> Boolean,
    private val stateChanged: (String, Boolean) -> Unit,
    private val conflictsChanged: (List<NativeSyncConflictItem>) -> Unit = {},
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val statusGeneration = AtomicLong()
    private val queued = AtomicBoolean()
    private var root: File? = null
    private var client: NativeSyncClient? = null
    private var activeConfiguration: NativeSyncConfiguration? = null
    @Volatile private var foreground = false
    @Volatile private var closed = false
    @Volatile private var enabled = false
    /** conflict.json text of the last published review; the review is recomputed only when it changes. */
    private var conflictKey: String? = UNKNOWN
    private val tick = object : Runnable {
        override fun run() {
            request()
            if (foreground && !closed) handler.postDelayed(this, 10000)
        }
    }

    fun resume(workspace: File) {
        if (closed) return
        foreground = true
        if (root != workspace.canonicalFile) {
            generation.incrementAndGet()
            root = workspace.canonicalFile
            client = null
            activeConfiguration = null
            worker.execute { conflictKey = UNKNOWN }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun pause() {
        foreground = false
        generation.incrementAndGet()
        handler.removeCallbacks(tick)
    }

    fun localSaved() {
        if (!enabled || closed) return
        handler.post { if (foreground && !closed) stateChanged("本地已保存，待同步", true) }
        request()
    }

    private fun request() {
        val workspace = root ?: return
        if (!foreground || closed || !queued.compareAndSet(false, true)) return
        val request = generation.get()
        worker.execute {
            try {
                val cancelled = { closed || !foreground || generation.get() != request }
                if (cancelled()) return@execute
                if (!File(workspace, ".mojian-native-workspace").isFile) {
                    enabled = false
                    publish("原生同步未启用", false, request)
                    return@execute
                }
                val configuration = NativeSyncConfiguration.load(context, workspace)
                enabled = configuration.enabled
                if (!enabled) { publish("原生同步已暂停", false, request); return@execute }
                statusGeneration.set(request)
                val sync = clientFor(workspace, configuration)
                sync.sync(onIdle(cancelled), cancelled)
                publish(sync.status, true, request)
                publishConflicts(workspace, sync)
            } catch (error: Exception) {
                publish("同步未完成，本地修改已保留：${error.cause?.message ?: error.message}", true, request)
            } finally {
                queued.set(false)
            }
        }
    }

    /**
     * Resolves reviewed conflict files on the sync worker (never concurrently with a pass); taking the
     * server's version replays through [applyIncoming], which reloads the library and the open page.
     */
    fun resolve(choices: Map<String, NativeSyncChoice>, done: (Boolean, String) -> Unit) {
        val workspace = root
        if (workspace == null || closed) { done(false, "同步未启用"); return }
        val request = generation.get()
        worker.execute {
            var result = false
            var status = "冲突处理未完成"
            try {
                val configuration = NativeSyncConfiguration.load(context, workspace)
                val sync = clientFor(workspace, configuration)
                val cancelled = { closed || !foreground }
                result = sync.resolveConflicts(choices, onIdle(cancelled), cancelled)
                status = sync.status
                publish(status, true, request)
                conflictKey = UNKNOWN
                publishConflicts(workspace, sync)
            } catch (error: Exception) {
                status = error.message ?: status
            }
            handler.post { if (!closed) done(result, status) }
            if (result) request()
        }
    }

    private fun clientFor(workspace: File, configuration: NativeSyncConfiguration): NativeSyncClient =
        client?.takeIf { activeConfiguration == configuration } ?: NativeSyncClient(workspace, configuration.endpoint, configuration.token,
            { value -> publish(value, true, statusGeneration.get()) }, NativeSyncAddressTransport(configuration,
                { value -> publish(value, true, statusGeneration.get()) })).also { client = it; activeConfiguration = configuration }

    private fun onIdle(cancelled: () -> Boolean): (() -> Unit) -> Boolean = { action ->
        if (cancelled()) false else {
            val task = FutureTask { if (cancelled()) false else applyIncoming(action) }
            check(handler.post(task)) { "无法调度同步接收" }
            task.get()
        }
    }

    /** Worker thread only. */
    private fun publishConflicts(workspace: File, sync: NativeSyncClient) {
        val marker = File(workspace, ".mojian-sync/conflict.json")
        val key = if (marker.isFile) runCatching { marker.readText(Charsets.UTF_8) }.getOrDefault(UNKNOWN) else null
        if (key == conflictKey && key != UNKNOWN) return
        val items = if (key == null) emptyList() else runCatching { sync.conflictReview() }.getOrElse { error ->
            android.util.Log.e("MojianSync", "Conflict review unavailable", error); emptyList()
        }
        conflictKey = key
        handler.post { if (!closed) conflictsChanged(items) }
    }

    @Volatile private var lastLoggedStatus: String? = null

    private fun publish(value: String, visible: Boolean, request: Long) {
        // Status was screen-only; keep transitions in logcat so sync failures can be diagnosed later.
        if (value != lastLoggedStatus) {
            lastLoggedStatus = value
            android.util.Log.i("MojianSync", "status: $value")
        }
        handler.post { if (!closed && foreground && generation.get() == request) stateChanged(value, visible) }
    }

    private companion object { const val UNKNOWN = "\u0000unknown" }

    override fun close() {
        closed = true
        pause()
        worker.shutdown()
    }
}
