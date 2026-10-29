package com.meta.inklab

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PaginatedPrintAdapter(private val title: String, private val provider: ExportPageProvider,
    private val initial: ExportOptions, private val executor: Executor,
    private val finished: () -> Unit = {}) : PrintDocumentAdapter() {
    private val ui = ExportUiDispatcher()
    private var plan: ExportPlan? = null
    private var layoutOptions = initial
    private var layoutSize: PdfOutputPageSize? = null
    @Volatile private var generation = 0

    private fun cancelled(signal: CancellationSignal, request: Int): Boolean =
        signal.isCanceled || request != generation || (provider is ExportProductSession && provider.isCancelled)

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, signal: CancellationSignal,
        callback: LayoutResultCallback, extras: Bundle?) {
        val request = ++generation
        plan = null
        if (cancelled(signal, request)) { callback.onLayoutCancelled(); return }
        var options = layoutOptions
        var size = layoutSize
        if (oldAttributes != null && oldAttributes != newAttributes) {
            val media = newAttributes.mediaSize
            val margins = newAttributes.minMargins
            val margin = maxOf(margins?.leftMils ?: 0, margins?.topMils ?: 0,
                margins?.rightMils ?: 0, margins?.bottomMils ?: 0) * 25.4f / 1000
            options = initial.copy(landscape = media?.isPortrait == false, marginMm = margin)
            if (media != null) options = options.copy(paper = when (media.id) {
                PrintAttributes.MediaSize.ISO_A3.id -> ExportPaper.A3
                PrintAttributes.MediaSize.NA_LETTER.id -> ExportPaper.LETTER
                else -> ExportPaper.A4
            })
            size = media?.let { PdfOutputPageSize(it.widthMils * 72f / 1000, it.heightMils * 72f / 1000) }
        }
        val selectedOptions = options
        val outputSize = size
        layoutOptions = selectedOptions
        layoutSize = outputSize
        try { executor.execute {
            val result = runCatching {
                if (cancelled(signal, request)) throw java.util.concurrent.CancellationException()
                provider.plan(selectedOptions, outputSize)
            }
            ui.call {
                if (cancelled(signal, request)) callback.onLayoutCancelled()
                else result.onSuccess {
                    plan = it
                    callback.onLayoutFinished(PrintDocumentInfo.Builder(title)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(it.pages.size).build(),
                        oldAttributes != newAttributes)
                }.onFailure { callback.onLayoutFailed(it.message) }
            }
        } } catch (error: RejectedExecutionException) { callback.onLayoutFailed(error.message) }
    }

    override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor,
        signal: CancellationSignal, callback: WriteResultCallback) {
        val request = generation
        if (cancelled(signal, request)) { callback.onWriteCancelled(); return }
        val current = plan ?: run { callback.onWriteFailed("请先完成分页"); return }
        val options = layoutOptions
        val selected = current.pages.indices.filter { index -> ranges.any { index in it.start..it.end } }
        try { executor.execute {
            val result = runCatching {
                VectorPdfExporter.capturePages(current, provider, options, selected, { cancelled(signal, request) }).use { pdf ->
                    if (cancelled(signal, request)) throw java.util.concurrent.CancellationException()
                    FileOutputStream(destination.fileDescriptor).use(pdf::writeTo)
                }
            }
            ui.call {
                if (cancelled(signal, request)) callback.onWriteCancelled()
                else result.onSuccess { callback.onWriteFinished(selected.map { PageRange(it, it) }.toTypedArray()) }
                    .onFailure { callback.onWriteFailed(it.message) }
            }
        } } catch (error: RejectedExecutionException) { callback.onWriteFailed(error.message) }
    }

    override fun onFinish() {
        generation++
        plan = null
        (provider as? ExportProductSession)?.cancel()
        finished()
        super.onFinish()
    }
}
