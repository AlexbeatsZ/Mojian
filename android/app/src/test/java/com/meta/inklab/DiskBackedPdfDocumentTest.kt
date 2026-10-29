package com.meta.inklab

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException

class DiskBackedPdfDocumentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun diskBackedMergePreservesPageOrderDimensionsAndVectorCommands() {
        val cache = temporary.newFolder("cache")
        val output = temporary.newFile("output.pdf")
        DiskBackedPdfDocument(cache).use { merged ->
            repeat(48) { index ->
                sourcePage(merged.pageFile, index)
                merged.appendPage()
                assertFalse(merged.pageFile.exists())
            }
            output.outputStream().use(merged::writeTo)
        }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        PDDocument.load(output, MemoryUsageSetting.setupTempFileOnly().setTempDir(cache)).use { result ->
            assertEquals(48, result.numberOfPages)
            repeat(result.numberOfPages) { index ->
                val page = result.getPage(index)
                assertEquals(595f + index, page.mediaBox.width, 0f)
                assertEquals(842f, page.mediaBox.height, 0f)
                assertEquals("$index 0 m 200 300 l S\n", page.contents.bufferedReader().use { it.readText() })
            }
        }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun pageContentUsesScratchFilesAndCloseRemovesAllIntermediates() {
        val cache = temporary.newFolder("cache")
        val merged = DiskBackedPdfDocument(cache)
        repeat(48) { index ->
            sourcePage(merged.pageFile, index, ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }, false)
            merged.appendPage()
        }
        assertTrue(cache.walkTopDown().filter { it.isFile }.sumOf { it.length() } >= 96L * 1024 * 1024)
        temporary.newFile("dense.pdf").outputStream().use(merged::writeTo)
        merged.close()
        merged.close()
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        try { merged.writeTo(ByteArrayOutputStream()); fail("Closed document accepted a write") }
        catch (_: IllegalStateException) {}
    }

    @Test fun cancellationDuringWriteStopsOutputAndCleansScratchFiles() {
        val cache = temporary.newFolder("cache")
        var cancelled = false
        try {
            DiskBackedPdfDocument(cache, { cancelled }).use { merged ->
                sourcePage(merged.pageFile, 0)
                merged.appendPage()
                merged.writeTo(object : OutputStream() {
                    override fun write(value: Int) { cancelled = true }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) { cancelled = true }
                })
            }
            fail("Cancelled write completed")
        } catch (_: CancellationException) {}
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun damagedInputDoesNotLeaveTemporaryFilesAfterFailure() {
        val cache = temporary.newFolder("cache")
        try {
            DiskBackedPdfDocument(cache).use { merged ->
                merged.pageFile.writeText("not a PDF")
                merged.appendPage()
            }
            fail("Damaged input accepted")
        } catch (_: java.io.IOException) {}
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    private fun sourcePage(file: File, index: Int,
        bytes: ByteArray = "$index 0 m 200 300 l S\n".toByteArray(), compressed: Boolean = true) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)).use { source ->
            val page = PDPage(PDRectangle(595f + index, 842f))
            page.setContents(ByteArrayInputStream(bytes).use { input ->
                if (compressed) PDStream(source, input, COSName.FLATE_DECODE) else PDStream(source, input)
            })
            source.addPage(page)
            source.save(file)
        }
    }
}
