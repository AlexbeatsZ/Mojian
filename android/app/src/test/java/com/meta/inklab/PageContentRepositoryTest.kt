package com.meta.inklab

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContentRepositoryTest {
    @Test
    fun savesAtomicallyAndLoadsPageObjects() {
        val root = Files.createTempDirectory("mojian-page-store").toFile()
        try {
            val repository = PageContentRepository(root)
            val document = PageInsertDocument("book", "page")
            document.objects += InsertObject.NoteLink("link", 1f, 2f, 300f, 32f, "target-book", "target-section", "target-page", "目标")

            repository.save(document)
            val restored = repository.load("book", "page")

            assertEquals(1, restored.objects.size)
            assertEquals("目标", (restored.objects.single() as InsertObject.NoteLink).targetTitle)
            assertTrue(repository.pageFile("book", "page").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun copyingPageDataRebindsDocumentIdsAndCopiesAssets() {
        val root = Files.createTempDirectory("mojian-page-copy").toFile()
        try {
            val repository = PageContentRepository(root)
            val source = PageInsertDocument("book", "source")
            source.objects += InsertObject.Attachment("a", 0f, 0f, 100f, 40f, "file.bin", "file.bin", "application/octet-stream", 3)
            repository.save(source)
            repository.assetDirectory("book", "source").resolve("file.bin").writeBytes(byteArrayOf(1, 2, 3))

            repository.copyPageData("book", "source", "book", "copy")

            val copied = repository.load("book", "copy")
            assertEquals("book", copied.notebookId)
            assertEquals("copy", copied.pageId)
            assertEquals(1, copied.objects.size)
            assertEquals(listOf<Byte>(1, 2, 3), repository.assetDirectory("book", "copy").resolve("file.bin").readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
