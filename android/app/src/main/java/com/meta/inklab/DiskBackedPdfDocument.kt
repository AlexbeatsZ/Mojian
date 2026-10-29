package com.meta.inklab

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class DiskBackedPdfDocument(cacheDirectory: File, private val cancelled: () -> Boolean = { false }) : AutoCloseable {
    private val directory = Files.createTempDirectory(cacheDirectory.toPath(), "mojian-pdf-").toFile()
    private val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)
    private val document = try { PDDocument(memory) } catch (error: Throwable) {
        directory.deleteRecursively()
        throw error
    }
    private var closed = false

    internal val pageFile get() = File(directory, "page.pdf")

    internal fun appendPage() {
        checkActive()
        PDDocument.load(pageFile, memory).use { source ->
            require(source.numberOfPages == 1) { "导出中间文件必须只包含一页" }
            val original = source.getPage(0)
            val cloner = PDFCloneUtility(document)
            val page = PDPage(cloner.cloneForNewDocument(original.cosObject) as COSDictionary)
            page.mediaBox = original.mediaBox
            page.cropBox = original.cropBox
            page.rotation = original.rotation
            page.resources = original.resources?.let { PDResources(cloner.cloneForNewDocument(it.cosObject) as COSDictionary) }
                ?: PDResources()
            document.addPage(page)
        }
        check(pageFile.delete()) { "无法清理导出中间文件" }
        checkActive()
    }

    fun writeTo(output: OutputStream) {
        checkActive()
        require(document.numberOfPages > 0) { "当前范围没有页面" }
        document.save(object : OutputStream() {
            override fun write(value: Int) { checkActive(); output.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                checkActive()
                output.write(buffer, offset, length)
            }
            override fun flush() { checkActive(); output.flush() }
        })
        checkActive()
    }

    private fun checkActive() {
        check(!closed) { "导出文档已关闭" }
        if (cancelled()) throw CancellationException()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { document.close() } finally { directory.deleteRecursively() }
    }
}
