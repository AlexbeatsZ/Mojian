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
            // Content of a page that has not joined the library is staged in `.mojian/pending`.
            root.resolve(".mojian").mkdirs()
            val blocker = root.resolve(".mojian/pending").apply { writeText("blocks page directory") }
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
            // The atomic write stages under `.mojian/tmp`; a file there makes the write itself fail.
            root.resolve(".mojian/tmp").deleteRecursively()
            root.resolve(".mojian/tmp").writeText("blocks temporary files")
            val beforeFailure = requireNotNull(epoch.capture())
            assertThrows(Exception::class.java) { repository.save(PageInsertDocument("book", "blocked")) }
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
    fun removingASectionKeepsPagesMovedOutOfItInTheSameSave() {
        val temporaryRoot = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(temporaryRoot.toPath(), "mojian-merge").toFile()
        try {
            val appearance = """"appearance":{"paperKind":0,"theme":0,"background":5,"pageColor":"#1B1B1B","showTitle":true,"sheetCount":1,"width":793.7,"height":1122.5,"infiniteWidth":793.7,"infiniteHeight":1122.5}"""
            FolderLibrary(root).apply(org.json.JSONObject("""{"version":2,"notebooks":[{"id":"book","title":"本","sections":[
                {"id":"source","title":"来源","children":[],"pages":[{"id":"page","title":"页",$appearance}]},
                {"id":"target","title":"目标","children":[],"pages":[]}]}]}"""))
            val repository = PageContentRepository(root)
            val library = repository.loadLibrary()
            repository.save(repository.load("book", "page").also { it.sharedSource.put("kept", true) })
            val notebook = library.activeNotebook()
            val page = notebook.sections.first { it.id == "source" }.pages.single()
            notebook.sections.first { it.id == "target" }.pages += page
            notebook.sections.removeAll { it.id == "source" }
            assertTrue(repository.saveLibrary(library))
            assertEquals(java.io.File(root, "本/目标/页.mojian"), repository.pageFile("book", "page"))
            assertTrue(repository.load("book", "page").sharedSource.getBoolean("kept"))
            assertEquals(listOf("目标"), java.io.File(root, "本").list()!!.filter { !it.startsWith(".") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun deletingNotebookRemovesAllNestedAssetsAndPagesButPreservesOtherNotebooks() {
        val temporaryRoot = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(temporaryRoot.toPath(), "mojian-notebook-delete").toFile()
        try {
            FolderLibrary(root).apply(org.json.JSONObject("""{"version":2,"activeNotebookId":"keep","notebooks":[
                {"id":"delete","title":"删除","sections":[{"id":"s1","title":"分区","children":[{"id":"s3","title":"子分区","children":[],
                    "pages":[{"id":"page-two","title":"二","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","appearance":{"paperKind":0,"theme":0,"background":5,"pageColor":"#1B1B1B","showTitle":true,"sheetCount":1,"width":793.7,"height":1122.5,"infiniteWidth":793.7,"infiniteHeight":1122.5}}]}],"pages":[{"id":"page-one","title":"一","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","appearance":{"paperKind":0,"theme":0,"background":5,"pageColor":"#1B1B1B","showTitle":true,"sheetCount":1,"width":793.7,"height":1122.5,"infiniteWidth":793.7,"infiniteHeight":1122.5}}]}]},
                {"id":"keep","title":"保留","sections":[{"id":"s2","title":"分区","children":[],"pages":[{"id":"page","title":"页","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","appearance":{"paperKind":0,"theme":0,"background":5,"pageColor":"#1B1B1B","showTitle":true,"sheetCount":1,"width":793.7,"height":1122.5,"infiniteWidth":793.7,"infiniteHeight":1122.5}}]}]}]}"""))
            val repository = PageContentRepository(root)
            val library = repository.loadLibrary()
            for (pageId in listOf("page-one", "page-two", "page")) {
                repository.save(repository.load("x", pageId).also { it.sharedSource.put("edited", pageId) })
                repository.assetDirectory("x", pageId).resolve("original.pdf").writeBytes(byteArrayOf(1, 2))
            }
            val notebook = repository.notebookDirectory("delete")
            assertTrue(java.io.File(notebook, "分区/子分区/二.mojian").isFile)

            library.selectNotebook("keep")
            assertTrue(library.deleteNotebook("delete"))
            assertTrue(repository.saveLibrary(library))
            repository.deleteNotebookData("delete")

            assertFalse(notebook.exists())
            assertFalse(root.resolve(".mojian/assets/page-one").exists())
            assertFalse(root.resolve(".mojian/assets/page-two").exists())
            assertTrue(repository.pageFile("keep", "page").isFile)
            assertTrue(root.resolve(".mojian/assets/page/original.pdf").isFile)
            assertThrows(IllegalArgumentException::class.java) { repository.assetDirectory("keep", "..") }
            assertThrows(IllegalArgumentException::class.java) { repository.assetDirectory("keep", ".") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataOnlyMtimeRewriteAfterOwnSaveDoesNotLookLikeAnotherDevice() {
        val root = Files.createTempDirectory("mojian-mtime-drift").toFile()
        val queue = PageWriteQueue()
        try {
            val repository = PageContentRepository(root)
            val live = repository.load("book", "page")
            fun write(id: String) {
                live.objects += InsertObject.NoteLink(id, 1f, 2f, 300f, 32f, "b", "s", "p", id)
                val snapshot = PageInsertDocument("book", "page", live.objects.mapTo(mutableListOf()) { InsertObject.fromJson(it.toJson()) },
                    live.strokes, org.json.JSONObject(live.sharedSource.toString()), live.diskStamp)
                queue.submit(repository, live, snapshot) { }
                queue.flush()
            }
            val file = repository.pageFile("book", "page")
            write("first")
            // HyperOS FUSE later reports the replaced file's old mtime for the renamed page.
            assertTrue(file.setLastModified(1_700_000_000_000L))
            assertFalse(repository.pageChanged("book", "page"))
            write("second")
            assertTrue(file.setLastModified(1_600_000_000_000L))
            write("third")
            assertEquals(3, repository.load("book", "page").objects.size)
            val stamp = repository.pageStamp("book", "page")
            assertTrue(file.setLastModified(1_500_000_000_000L))
            assertTrue(repository.pageUnchangedSince("book", "page", stamp))
        } finally { queue.close(); root.deleteRecursively() }
    }

    @Test
    fun loadingRemovesOnlyAbandonedTemporariesOfThatPage() {
        val root = Files.createTempDirectory("mojian-stale-temporaries").toFile()
        try {
            val repository = PageContentRepository(root)
            repository.save(PageInsertDocument("book", "page"))
            // Page writes stage their temporaries under `.mojian/tmp`, never beside a page.
            val pages = root.resolve(".mojian/tmp").apply { mkdirs() }
            val old = pages.resolve("page.json.dead.tmp").apply { writeText("partial") }
            assertTrue(old.setLastModified(System.currentTimeMillis() - PageContentRepository.STALE_TEMPORARY_MS - 60_000))
            val recent = pages.resolve("page.json.inflight.tmp").apply { writeText("partial") }
            val other = pages.resolve("other.json.dead.tmp").apply { writeText("partial") }
            assertTrue(other.setLastModified(old.lastModified()))

            PageContentRepository(root).load("book", "page")

            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(other.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun mtimeRewrittenWhileVerifyingContentDoesNotRefuseSave() {
        val root = Files.createTempDirectory("mojian-mtime-during-hash").toFile()
        try {
            var rewriteDuringHash = false
            val repository = PageContentRepository(root) { file ->
                // HyperOS rewrites the mtime at an arbitrary moment, here while the bytes are hashed.
                if (rewriteDuringHash) { rewriteDuringHash = false; file.setLastModified(file.lastModified() - 86_400_000L) }
                NativeSyncFiles.hash(file)
            }
            val document = PageInsertDocument("book", "page")
            document.objects += InsertObject.NoteLink("first", 1f, 2f, 300f, 32f, "b", "s", "p", "first")
            repository.save(document)
            // A renamed-in copy of our own bytes forces the content hash (new inode).
            val file = repository.pageFile("book", "page")
            replaceByRename(file, file.readText())
            rewriteDuringHash = true
            document.objects += InsertObject.NoteLink("second", 1f, 2f, 300f, 32f, "b", "s", "p", "second")
            repository.save(document)
            assertFalse(rewriteDuringHash)
            assertEquals(listOf("first", "second"), repository.load("book", "page").objects.map { it.id })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun externalReplacementReportingOurExactStampIsStillRefusedBySave() {
        val root = Files.createTempDirectory("mojian-same-stamp-external").toFile()
        try {
            val repository = PageContentRepository(root)
            val document = PageInsertDocument("book", "page")
            document.objects += InsertObject.NoteLink("mine", 1f, 2f, 300f, 32f, "b", "s", "p", "mine")
            repository.save(document)
            val file = repository.pageFile("book", "page")
            val modified = file.lastModified()
            val external = file.readText().replace("\"mine\"", "\"else\"")
            replaceByRename(file, external)
            // HyperOS can report the replaced file's mtime: same size + same mtime as our own write.
            assertTrue(file.setLastModified(modified))
            assertEquals(document.diskStamp, repository.pageStamp("book", "page"))

            document.objects += InsertObject.NoteLink("more", 1f, 2f, 300f, 32f, "b", "s", "p", "more")
            assertThrows(IllegalStateException::class.java) { repository.save(document) }
            assertEquals(external, file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun realExternalContentChangeIsStillRefusedEvenWhenMtimeLooksOld() {
        val root = Files.createTempDirectory("mojian-mtime-external").toFile()
        try {
            val repository = PageContentRepository(root)
            val document = PageInsertDocument("book", "page")
            document.objects += InsertObject.NoteLink("mine", 1f, 2f, 300f, 32f, "b", "s", "p", "mine")
            repository.save(document)
            val file = repository.pageFile("book", "page")
            val original = file.readText()
            val stamp = repository.pageStamp("book", "page")
            val external = original.replace("\"mine\"", "\"else\"")
            assertEquals(original.length, external.length)
            replaceByRename(file, external)
            assertTrue(file.setLastModified(1_700_000_000_000L))

            assertTrue(repository.pageChanged("book", "page"))
            assertFalse(repository.pageUnchangedSince("book", "page", stamp))
            document.objects += InsertObject.NoteLink("more", 1f, 2f, 300f, 32f, "b", "s", "p", "more")
            assertThrows(IllegalStateException::class.java) { repository.save(document) }
            assertEquals(external, file.readText())
        } finally { root.deleteRecursively() }
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

    /** Every real writer of a canonical page (Mojian, native sync, Syncthing) publishes by rename. */
    private fun replaceByRename(file: java.io.File, text: String) {
        val temporary = java.io.File(file.parentFile, "${file.name}.external.tmp").apply { writeText(text) }
        Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun unchangedInodeSkipsFullReReadButReplacementIsStillHashed() {
        val root = Files.createTempDirectory("mojian-physical-identity").toFile()
        try {
            var hashes = 0
            val repository = PageContentRepository(root) { hashes++; NativeSyncFiles.hash(it) }
            val document = PageInsertDocument("book", "page")
            document.objects += InsertObject.NoteLink("first", 1f, 2f, 300f, 32f, "b", "s", "p", "first")
            repository.save(document)
            val file = repository.pageFile("book", "page")
            // Windows NTFS exposes no inode key; the repository then always hashes (covered elsewhere).
            org.junit.Assume.assumeTrue(Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() != null)
            // HyperOS mtime rewrite only: same inode and size, no 6.5 MB re-read needed.
            assertTrue(file.setLastModified(file.lastModified() - 86_400_000L))
            document.objects += InsertObject.NoteLink("second", 1f, 2f, 300f, 32f, "b", "s", "p", "second")
            repository.save(document)
            assertEquals(0, hashes)

            // A renamed-in replacement with our own bytes has a new inode: content is hashed and accepted.
            replaceByRename(file, file.readText())
            document.objects += InsertObject.NoteLink("third", 1f, 2f, 300f, 32f, "b", "s", "p", "third")
            repository.save(document)
            assertEquals(1, hashes)
            assertEquals(listOf("first", "second", "third"), PageContentRepository(root).load("book", "page").objects.map { it.id })
        } finally { root.deleteRecursively() }
    }
}
