package com.meta.inklab

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.ContextThemeWrapper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal class ExportRenderWorker(context: Context) {
    private val lock = Any()
    private val baseContext = context.applicationContext.createConfigurationContext(context.resources.configuration)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var closed = false
    private var cleanup: (() -> Unit)? = null
    private var renderContext: Context? = null

    fun context(): Context {
        check(Looper.myLooper() == handler?.looper)
        return renderContext ?: object : ContextThemeWrapper(baseContext, android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getMainLooper(): Looper = checkNotNull(handler).looper
        }.also { renderContext = it }
    }

    fun setCleanup(action: (() -> Unit)?) {
        check(Looper.myLooper() == handler?.looper)
        cleanup = action
    }

    fun <Value> call(action: () -> Value): Value {
        var inline = false
        val task: FutureTask<Value>
        synchronized(lock) {
            if (closed) throw CancellationException("Export render worker closed")
            val target = handler ?: Handler(HandlerThread("Mojian-export-render").also {
                it.start()
                thread = it
            }.looper).also { handler = it }
            task = FutureTask(action)
            inline = Looper.myLooper() == target.looper
            if (!inline) check(target.post(task)) { "无法调度导出渲染任务" }
        }
        if (inline) task.run()
        var interrupted = false
        try {
            while (true) {
                try { return task.get() }
                catch (_: InterruptedException) { interrupted = true }
                catch (error: ExecutionException) { throw error.cause ?: error }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val target = handler ?: return
            check(target.post {
                try { cleanup?.invoke() }
                catch (error: Throwable) { android.util.Log.e("ExportRenderWorker", "Export cleanup failed", error) }
                finally {
                    cleanup = null
                    renderContext = null
                    target.removeCallbacksAndMessages(null)
                    thread?.quitSafely()
                }
            })
        }
    }

    internal fun awaitTermination(timeoutMillis: Long): Boolean {
        val owner = synchronized(lock) { thread } ?: return true
        check(Thread.currentThread() !== owner)
        owner.join(timeoutMillis)
        return !owner.isAlive
    }
}
