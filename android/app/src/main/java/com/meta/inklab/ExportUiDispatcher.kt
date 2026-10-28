package com.meta.inklab

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

class ExportUiDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    fun <Value> call(action: () -> Value): Value {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        check(handler.post(task)) { "无法调度导出界面任务" }
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
