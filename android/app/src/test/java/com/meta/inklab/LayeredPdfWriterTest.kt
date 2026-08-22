package com.meta.inklab

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredPdfWriterTest {
    @Test
    fun writesOptionalContentGroupsAndSoftMask() {
        val opaque = PdfRasterLayer("页面背景", 2, 1, byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()))
        val transparent = PdfRasterLayer("插入内容", 2, 1, byteArrayOf(1, 2, 3, 4, 5, 6), byteArrayOf(0, 127))
        val ink = PdfRasterLayer("墨迹与文字", 2, 1, byteArrayOf(0, 0, 0, 10, 20, 30), byteArrayOf(255.toByte(), 255.toByte()))
        val output = ByteArrayOutputStream()

        LayeredPdfWriter.write(output, listOf(opaque, transparent, ink))

        val pdf = output.toString(StandardCharsets.ISO_8859_1.name())
        assertTrue(pdf.startsWith("%PDF-1.7"))
        assertTrue(pdf.contains("/OCProperties"))
        assertTrue(pdf.contains("/SMask"))
        assertTrue(pdf.contains("/OC /L1 BDC"))
        assertTrue(pdf.contains("<FEFF9875"))
        assertTrue(pdf.endsWith("%%EOF\n"))
        File("build/verification/layered-pdf-writer-test.pdf").also {
            it.parentFile?.mkdirs()
            it.writeBytes(output.toByteArray())
        }
    }
}
