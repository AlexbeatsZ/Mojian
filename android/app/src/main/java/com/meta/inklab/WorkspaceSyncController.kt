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
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val statusGeneration = AtomicLong()
    private val queued = AtomicBoolean()
    private var root: File? = null
    private var client: NativeSyncClient? = null
    @Volatile private var foreground = false
    @Volatile private var closed = false
    @Volatile private var enabled = false
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
                val sync = client ?: NativeSyncClient(workspace, configuration.endpoint, configuration.token,
                    { value -> publish(value, true, statusGeneration.get()) }).also { client = it }
                sync.sync({ action ->
                    if (cancelled()) false else {
                        val task = FutureTask { if (cancelled()) false else applyIncoming(action) }
                        check(handler.post(task)) { "无法调度同步接收" }
                        task.get()
                    }
                }, cancelled)
                publish(sync.status, true, request)
            } catch (error: Exception) {
                publish("同步未完成，本地修改已保留：${error.cause?.message ?: error.message}", true, request)
            } finally {
                queued.set(false)
            }
        }
    }

    private fun publish(value: String, visible: Boolean, request: Long) {
        handler.post { if (!closed && foreground && generation.get() == request) stateChanged(value, visible) }
    }

    override fun close() {
        closed = true
        pause()
        worker.shutdown()
    }
}
