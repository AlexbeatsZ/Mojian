package com.meta.inklab

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class XlsxPreviewParserTest {
    @Test
    fun readsSharedInlineAndNumericCellsFromFirstSheet() {
        val file = Files.createTempFile("preview", ".xlsx").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zip.write("<?xml version=\"1.0\"?><sst><si><t>标题</t></si><si><t>内容</t></si></sst>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(
                    "<?xml version=\"1.0\"?><worksheet><sheetData>".plus(
                        "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"inlineStr\"><is><t>说明</t></is></c></row>" +
                            "<row r=\"2\"><c r=\"A2\"><v>42</v></c><c r=\"B2\" t=\"s\"><v>1</v></c></row>" +
                            "</sheetData></worksheet>",
                    ).toByteArray(),
                )
                zip.closeEntry()
            }

            val preview = XlsxPreviewParser.preview(file)

            assertEquals(listOf("标题", "说明"), preview[0])
            assertEquals(listOf("42", "内容"), preview[1])
        } finally {
            file.delete()
        }
    }
}
