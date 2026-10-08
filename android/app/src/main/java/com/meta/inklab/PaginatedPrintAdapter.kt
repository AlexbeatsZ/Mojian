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

/** Native print preview and printing share exactly the vector export pagination. */
class PaginatedPrintAdapter(private val title: String, private val sources: List<VectorPdfSource>,
    private val initial: ExportOptions, private val executor: Executor) : PrintDocumentAdapter() {
    private var pages = VectorPdfExporter.pages(sources,initial)
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, signal: CancellationSignal,
        callback: LayoutResultCallback, extras: Bundle?) {
        if(signal.isCanceled) { callback.onLayoutCancelled(); return }
        runCatching {
            // Preserve the app layout on initial preview; reflow if the user changes native paper/orientation/margins.
            if(oldAttributes != null && oldAttributes != newAttributes) {
                val media=newAttributes.mediaSize
                val paper=when(media?.id) { PrintAttributes.MediaSize.ISO_A3.id -> ExportPaper.A3; PrintAttributes.MediaSize.NA_LETTER.id -> ExportPaper.LETTER; else -> ExportPaper.A4 }
                val margins=newAttributes.minMargins
                val margin=maxOf(margins?.leftMils ?: 0,margins?.topMils ?: 0,margins?.rightMils ?: 0,margins?.bottomMils ?: 0)*25.4f/1000
                val size=media?.let { PdfOutputPageSize(it.widthMils*72f/1000,it.heightMils*72f/1000) }
                pages=VectorPdfExporter.pages(sources,initial.copy(paper=paper,landscape=media?.isPortrait==false,marginMm=margin),size)
            }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages.size).build(),oldAttributes!=newAttributes)
        }.onFailure { callback.onLayoutFailed(it.message) }
    }
    override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
        if(signal.isCanceled) { callback.onWriteCancelled(); return }
        val selected=pages.indices.filter { index -> ranges.any { index in it.start..it.end } }
        val pdf=runCatching { VectorPdfExporter.capturePages(selected.map(pages::get)) }.getOrElse { callback.onWriteFailed(it.message); return }
        executor.execute {
            try {
                if(signal.isCanceled) callback.onWriteCancelled()
                else {
                    FileOutputStream(destination.fileDescriptor).use(pdf::writeTo)
                    if(signal.isCanceled) callback.onWriteCancelled()
                    else callback.onWriteFinished(selected.map { PageRange(it,it) }.toTypedArray())
                }
            } catch(error: Exception) { callback.onWriteFailed(error.message) }
            finally { pdf.close() }
        }
    }
}
