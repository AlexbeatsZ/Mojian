package com.meta.inklab

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContentRepositoryTest {
    @Test
    fun copyingRefusesMissingReferencedAttachmentsBeforePublishingTarget() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-copy-missing-attachment").toFile()
        try {
            val repository = PageContentRepository(root)
            val source = PageInsertDocument("book", "source")
            source.objects += InsertObject.Attachment("file", 0f, 0f, 100f, 40f,
                "missing.bin", "missing.bin", "application/octet-stream", 3)
            repository.save(source)

            assertThrows(IllegalStateException::class.java) {
                repository.copyPageData("book", "source", "book", "copy")
            }

            assertFalse(repository.pageFile("book", "copy").exists())
            assertTrue(repository.pageFile("book", "source").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun copiedPdfImageRequiresOriginalSourceAssetAsWellAsPreview() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-copy-missing-original").toFile()
        try {
            val repository = PageContentRepository(root)
            val source = PageInsertDocument("book", "source")
            source.objects += InsertObject.Image("image", 0f, 0f, 100f, 100f,
                "preview.png", "PDF", "image/png", ImageSourceKind.PDF_PAGE, sourceAssetName = "original.pdf")
            repository.save(source)
            val assets = repository.assetDirectory("book", "source")
            assets.resolve("preview.png").writeText("preview")

            assertThrows(IllegalStateException::class.java) {
                repository.copyPageData("book", "source", "book", "copy")
            }
            assertFalse(repository.pageFile("book", "copy").exists())

            assets.resolve("original.pdf").writeText("pdf")
            repository.copyPageData("book", "source", "book", "copy")
            assertEquals("pdf", repository.assetDirectory("book", "copy").resolve("original.pdf").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun emptySelectionCannotReleaseOutgoingContentUntilFailedQueuedSaveIsRetried() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-empty-selection-barrier").toFile()
        val queue = PageWriteQueue()
        try {
            val repository = PageContentRepository(root)
            val live = repository.load("book", "page")
            val snapshot = PageInsertDocument("book", "page", sharedSource = org.json.JSONObject().put("extension", "unsaved"))
            root.resolve("notebooks").mkdirs()
            val blocker = root.resolve("notebooks/book").apply { writeText("blocks page directory") }
            queue.submit(repository, live, snapshot) { }
            val tasks = ArrayDeque<Runnable>()
            val loader = LatestPageLoader<String?, String>(java.util.concurrent.Executor { tasks.addLast(it) }) { it() }
            var displayed = "unsaved content"
            var failure: Throwable? = null
            fun selectEmpty() = loader.load(null, { queue.flush(); "empty selection" }) { result ->
                result.onSuccess { displayed = it }.onFailure { failure = it }
            }

            selectEmpty()
            assertTrue(loader.isLoading)
            assertEquals("unsaved content", displayed)
            tasks.removeFirst().run()
            assertTrue(failure is IllegalStateException)
            assertTrue(queue.hasFailures)
            assertEquals("unsaved content", displayed)

            assertTrue(blocker.delete())
            queue.submit(repository, live, snapshot) { throw AssertionError(it) }
            selectEmpty()
            tasks.removeFirst().run()
            assertFalse(queue.hasFailures)
            assertEquals("empty selection", displayed)
            assertEquals("unsaved", repository.load("book", "page").sharedSource.getString("extension"))
        } finally { queue.close(); root.deleteRecursively() }
    }

    @Test
    fun requiredPdfOwnedBySourceIsCopiedOnce() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-owned-pdf-copy").toFile()
        try {
            val repository = PageContentRepository(root)
            repository.save(PageInsertDocument("book", "source"))
            val pdf = repository.assetDirectory("book", "source").resolve("original.pdf").apply { writeText("pdf bytes") }

            repository.copyPageData("book", "source", "book", "copy", listOf(pdf))

            assertTrue(repository.pageFile("book", "copy").isFile)
            assertEquals("pdf bytes", repository.assetDirectory("book", "copy").resolve(pdf.name).readText())
            assertEquals("pdf bytes", pdf.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missingPdfOwnedBySourceDoesNotPublishCopyOrStageUnrelatedAssets() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-missing-owned-pdf").toFile()
        try {
            val repository = PageContentRepository(root)
            repository.save(PageInsertDocument("book", "source"))
            val assets = repository.assetDirectory("book", "source")
            assets.resolve("attachment.bin").writeText("retained")

            assertThrows(IllegalStateException::class.java) {
                repository.copyPageData("book", "source", "book", "copy", listOf(assets.resolve("missing.pdf")))
            }

            assertFalse(repository.pageFile("book", "copy").exists())
            assertFalse(repository.assetDirectory("book", "copy").resolve("attachment.bin").exists())
            assertTrue(repository.pageFile("book", "source").isFile)
            assertEquals("retained", assets.resolve("attachment.bin").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun successfulSaveAdvancesEpochAndFailedAtomicWriteReleasesMutationToken() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-repository-epoch").toFile()
        try {
            val repository = PageContentRepository(root)
            val epoch = LocalMutationEpoch.forWorkspace(root)
            val beforeSave = requireNotNull(epoch.capture())
            repository.save(PageInsertDocument("book", "saved"))
            assertTrue(requireNotNull(epoch.capture()) > beforeSave)
            root.resolve("notebooks/blocked").writeText("blocks directory creation")
            val beforeFailure = requireNotNull(epoch.capture())
            assertThrows(IllegalStateException::class.java) { repository.save(PageInsertDocument("blocked", "page")) }
            assertTrue(requireNotNull(epoch.capture()) > beforeFailure)
            assertTrue(epoch.publishIfUnchanged(requireNotNull(epoch.capture())) { })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun copyingAfterSaveBarrierIncludesNewestQueuedTextAndUnknownMetadata() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-copy-barrier").toFile()
        val queue = PageWriteQueue()
        try {
            val repository = PageContentRepository(root)
            val live = repository.load("book", "source")
            val snapshot = PageInsertDocument("book", "source", sharedSource = org.json.JSONObject()
                .put("customExtension", "retained")
                .put("textItems", org.json.JSONArray().put(org.json.JSONObject().put("text", "latest edit"))))
            queue.submit(repository, live, snapshot) { throw AssertionError(it) }
            queue.flush()
            repository.copyPageData("book", "source", "book", "copy")
            val copied = repository.load("book", "copy")
            assertEquals("latest edit", copied.sharedSource.getJSONArray("textItems").getJSONObject(0).getString("text"))
            assertEquals("retained", copied.sharedSource.getString("customExtension"))
        } finally { queue.close(); root.deleteRecursively() }
    }

    @Test
    fun missingExternalPdfAssetDoesNotPublishDestinationPageOrDeleteSource() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-copy-failure").toFile()
        try {
            val repository = PageContentRepository(root)
            repository.save(PageInsertDocument("book", "source"))
            assertThrows(IllegalStateException::class.java) {
                repository.copyPageData("book", "source", "book", "copy", listOf(root.resolve("missing.pdf")))
            }
            assertFalse(repository.pageFile("book", "copy").exists())
            assertTrue(repository.pageFile("book", "source").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun deletingNotebookRemovesAllNestedAssetsAndPagesButPreservesOtherNotebooks() {
        val temporaryRoot = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(temporaryRoot.toPath(), "mojian-notebook-delete").toFile()
        try {
            val repository = PageContentRepository(root)
            for (pageId in listOf("page-one", "page-two")) {
                repository.save(PageInsertDocument("delete", pageId))
                repository.assetDirectory("delete", pageId).resolve("original.pdf").writeBytes(byteArrayOf(1, 2))
            }
            repository.save(PageInsertDocument("keep", "page"))

            repository.deleteNotebookData("delete")

            assertFalse(repository.notebookDirectory("delete").exists())
            assertTrue(repository.pageFile("keep", "page").isFile)
            assertThrows(IllegalArgumentException::class.java) { repository.deleteNotebookData("..") }
            assertThrows(IllegalArgumentException::class.java) { repository.deleteNotebookData(".") }
            assertTrue(repository.pageFile("keep", "page").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

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
