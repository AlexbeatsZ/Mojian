package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSyncClientTest {
    private class Server : NativeSyncTransport {
        val blobs = mutableMapOf<String, ByteArray>()
        var remote = NativeSyncManifest("test-workspace", 0, emptyMap())
        var loseNextReceipt = false
        var commits = 0
        var afterDownload: (() -> Unit)? = null
        var offline = false
        var afterManifest: (() -> Unit)? = null
        private val receipts = mutableMapOf<String, NativeSyncManifest>()
        override fun manifest(cancelled: () -> Boolean): NativeSyncManifest {
            check(!offline) { "offline" }
            afterManifest?.invoke()
            return remote
        }
        override fun upload(hash: String, source: File, cancelled: () -> Boolean) { blobs[hash] = source.readBytes() }
        override fun download(hash: String, destination: File, cancelled: () -> Boolean) {
            destination.parentFile!!.mkdirs(); destination.writeBytes(blobs[hash] ?: throw NativeSyncBlobMissing()); afterDownload?.invoke()
        }
        override fun commit(request: JSONObject, cancelled: () -> Boolean): NativeSyncManifest {
            commits++
            check(request.getString("workspaceId") == remote.workspaceId)
            val operation = request.getString("deviceId") + ":" + request.getString("operationId")
            receipts[operation]?.let { return it }
            val changes = NativeSyncFiles.changes(request.getJSONArray("changes"))
            if (changes.any { (remote.files[it.path]?.version ?: 0) != it.expectedVersion }) throw NativeSyncVersionConflict()
            if (changes.isNotEmpty()) {
                val revision = remote.revision + 1
                val files = remote.files.toMutableMap()
                changes.forEach { files[it.path] = NativeSyncEntry(it.hash, revision) }
                remote = remote.copy(revision = revision, files = files)
            }
            val receipt = remote.copy(files = changes.associate { it.path to remote.files.getValue(it.path) }, operationId = request.getString("operationId"))
            receipts[operation] = receipt
            if (loseNextReceipt) { loseNextReceipt = false; throw java.io.IOException("receipt lost") }
            return receipt
        }
        fun directory(path: String) {
            val revision = remote.revision + 1
            remote = remote.copy(revision = revision, files = remote.files + (path to NativeSyncEntry(NativeSyncFiles.DIRECTORY, revision)))
        }
        fun updateHash(changes: Map<String, String?>) {
            val revision = remote.revision + 1
            remote = remote.copy(revision = revision, files = remote.files + changes.mapValues { NativeSyncEntry(it.value, revision) })
        }
        fun update(path: String, value: String?) {
            val hash = value?.let {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }.also { key -> blobs[key] = it.toByteArray() }
            }
            val revision = remote.revision + 1
            remote = remote.copy(revision = revision, files = remote.files + (path to NativeSyncEntry(hash, revision)))
        }
    }

    private fun isolated(action: (File, File, Server) -> Unit) {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "mojian-native-sync").toFile()
        try {
            val root = File(directory, "workspace").apply { mkdirs() }
            File(root, ".mojian-native-workspace").writeText("test")
            File(root, "测试本").mkdirs()
            action(directory, root, Server())
        } finally { directory.deleteRecursively() }
    }

    /** The notebook descriptor stands in for any synchronized library file whose bytes the engine never parses. */
    private val INDEX = "测试本/.mojian-folder.json"
    private fun pageFile(id: String, content: String, page: String = "{}") = """{"version":3,"id":"$id","sortKey":"a0","page":$page,"content":$content}"""
    private fun text(id: String, value: String) = pageFile(id, """{"text":"$value"}""")
    private fun File.put(path: String, value: String) = NativeSyncFiles.safeFile(this, path).apply { parentFile!!.mkdirs(); writeText(value) }

    private fun client(root: File, server: Server) = NativeSyncClient(root, "https://example.test/mojian-sync/v1", "test-token", transport = server)
    private val idle: (() -> Unit) -> Boolean = { action -> action(); true }

    @Test fun accidentallyMissingLocalLibraryNeverUploadsDeletion() = isolated { _, root, server ->
        root.put(INDEX, "base")
        root.put("测试本/page.mojian", text("page", "base"))
        val engine = client(root, server)
        engine.sync(idle)
        val revision = server.remote.revision
        val expectedHash = server.remote.files.getValue(INDEX).hash
        File(root, "测试本").deleteRecursively()
        engine.sync(idle)
        assertEquals(revision, server.remote.revision)
        assertEquals(expectedHash, server.remote.files.getValue(INDEX).hash)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        assertTrue(engine.status, engine.status.contains("笔记库缺失"))
    }

    @Test fun remotelyDeletedFolderWithLocalContentIsKeptAndUploadedAgain() = isolated { _, root, server ->
        root.put(INDEX, "base")
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals(NativeSyncFiles.DIRECTORY, server.remote.files.getValue("测试本").hash)
        val page = root.put("测试本/新页.mojian", text("new", "local"))
        server.update(INDEX, null)
        server.update("测试本", null)
        engine.sync(idle)
        assertTrue(page.isFile && File(root, "测试本").isDirectory)
        assertFalse(File(root, INDEX).exists())
        engine.sync(idle)
        assertEquals(NativeSyncFiles.DIRECTORY, server.remote.files.getValue("测试本").hash)
        assertEquals(NativeSyncFiles.hash(page), server.remote.files.getValue("测试本/新页.mojian").hash)
        assertNull(server.remote.files.getValue(INDEX).hash)
    }

    @Test fun retiredV2LayoutIsKeptAsideAndFolderLibraryArrivesWithoutTouchingLegacyEntries() = isolated { _, root, server ->
        val engine = client(root, server)
        engine.sync(idle)
        root.put("library.json", "v2 index")
        root.put("notebooks/book/pages/p.json", "v2 edit never uploaded")
        val stateFile = File(root, ".mojian-sync/state.json")
        val state = NativeSyncFiles.read(stateFile)
        state.getJSONObject("files").put("library.json", NativeSyncEntry(NativeSyncFiles.hash(File(root, "library.json")), state.getLong("revision")).json())
        NativeSyncFiles.atomicJson(stateFile, state)
        server.update("library.json", "v2 index")
        server.update("library.json", null)
        server.update("测试本/页.mojian", text("page", "v3"))

        val restarted = client(root, server)
        assertEquals(listOf("notebooks/book/pages/p.json"), restarted.retireLegacyLayout())
        assertFalse(File(root, "library.json").exists() || File(root, "notebooks").exists())
        val backup = File(root, ".mojian/legacy-v2").listFiles()!!.single()
        assertEquals("v2 edit never uploaded", File(backup, "notebooks/book/pages/p.json").readText())
        assertEquals("notebooks/book/pages/p.json", File(backup, "unsynced.txt").readText())
        assertTrue(restarted.retireLegacyLayout().isEmpty())

        val commits = server.commits
        restarted.sync(idle)
        assertEquals(text("page", "v3"), File(root, "测试本/页.mojian").readText())
        assertNull(server.remote.files.getValue("library.json").hash)
        assertFalse(server.remote.files.keys.any { it.startsWith("notebooks/") })
        assertTrue(server.commits - commits <= 1) // at most the hidden .mojian folder item
    }

    @Test fun remoteFoldersAreCreatedAndEmptyDeletedFoldersRemoved() = isolated { _, root, server ->
        root.put(INDEX, "base")
        val engine = client(root, server)
        engine.sync(idle)
        server.directory("测试本/空分区")
        server.directory("测试本/空分区/子分区")
        engine.sync(idle)
        assertTrue(File(root, "测试本/空分区/子分区").isDirectory)
        server.update("测试本/空分区/子分区", null)
        server.update("测试本/空分区", null)
        engine.sync(idle)
        assertFalse(File(root, "测试本/空分区").exists())
        assertTrue(File(root, "测试本").isDirectory)
    }

    @Test fun caseOnlyRenameMovesTheOnlyCopy() = isolated { _, root, server ->
        val old = root.put("测试本/note.mojian", text("n", "body"))
        val engine = client(root, server)
        engine.sync(idle)
        val hash = server.remote.files.getValue("测试本/note.mojian").hash
        server.updateHash(mapOf("测试本/note.mojian" to null, "测试本/Note.mojian" to hash))
        engine.sync(idle)
        assertEquals(listOf("Note.mojian"), File(root, "测试本").list()!!.filter { it.endsWith(".mojian") })
        assertEquals(text("n", "body"), File(root, "测试本/Note.mojian").readText())
        assertFalse(old.path.endsWith("Note.mojian"))
    }

    @Test fun skippedItemIsReportedWhileOtherFilesSynchronize() = isolated { _, root, server ->
        root.put(INDEX, "base")
        root.put("测试本/readme.txt", "not a page")
        val engine = client(root, server)
        engine.sync(idle)
        assertTrue(engine.status, server.remote.files.containsKey(INDEX))
        assertFalse(server.remote.files.containsKey("测试本/readme.txt"))
        assertTrue(engine.lastFailures.containsKey("测试本/readme.txt"))
        assertTrue(engine.status, engine.status.contains("测试本/readme.txt"))
    }

    @Test fun uploadsAreSplitIntoServerSizedBatches() = isolated { _, root, server ->
        for (index in 0 until 5) root.put("测试本/p$index.mojian", text("p$index", "v"))
        val engine = client(root, server).apply { batchLimit = 2 }
        repeat(4) { engine.sync(idle) }
        assertEquals((0 until 5).map { "测试本/p$it.mojian" }.toSet() + "测试本", server.remote.files.filterValues { it.hash != null }.keys)
        assertTrue(server.commits >= 3)
    }

    @Test fun localMutationBeforeConflictCaptureDoesNotPermanentlyBlockSync() = isolated { _, root, server ->
        val page = File(root, INDEX).apply { writeText("base") }
        val engine = client(root, server)
        engine.sync(idle)
        page.writeText("local edit")
        server.update(INDEX, "remote edit")
        server.afterManifest = { LocalMutationEpoch.forWorkspace(root).begin().use { } }
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/conflict.json").exists())
        assertFalse(File(root, ".mojian-sync/conflicts").exists())
        server.afterManifest = null
        engine.sync(idle)
        assertTrue(File(root, ".mojian-sync/conflict.json").isFile)
    }

    @Test fun localMutationDuringConflictDownloadDoesNotPublishConflictMarker() = isolated { _, root, server ->
        val page = File(root, INDEX).apply { writeText("base") }
        val engine = client(root, server)
        engine.sync(idle)
        page.writeText("local edit")
        server.update(INDEX, "remote edit")
        server.afterDownload = { LocalMutationEpoch.forWorkspace(root).begin().use { } }
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/conflict.json").exists())
        assertEquals("local edit", page.readText())
        server.afterDownload = null
        engine.sync(idle)
        assertTrue(File(root, ".mojian-sync/conflict.json").isFile)
    }

    @Test fun localMutationDuringScanAndStageCannotPublishOutboxEvenWhenBytesReturnToSameValue() = isolated { _, root, server ->
        File(root, INDEX).writeText("local")
        val epoch = LocalMutationEpoch.forWorkspace(root)
        server.afterManifest = { epoch.begin().use { File(root, INDEX).writeText("local") } }
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals(0, server.commits)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        assertTrue(server.remote.files.isEmpty())
        server.afterManifest = null
        engine.sync(idle)
        assertEquals(1, server.commits)
    }

    @Test fun activeNavigationTransactionPreventsAnySnapshotPublication() = isolated { _, root, server ->
        File(root, INDEX).writeText("old-library")
        val engine = client(root, server)
        LocalMutationEpoch.forWorkspace(root).begin().use {
            engine.sync(idle)
            assertEquals(0, server.commits)
            assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        }
        engine.sync(idle)
        assertEquals(1, server.commits)
    }

    @Test fun lostReceiptReplaysDurableOutboxIdempotentlyAfterRestart() = isolated { _, root, server ->
        File(root, INDEX).writeText("first")
        server.loseNextReceipt = true
        client(root, server).sync(idle)
        assertTrue(File(root, ".mojian-sync/outbox.json").isFile)
        File(root, INDEX).writeText("second")
        client(root, server).sync(idle)
        assertEquals(2, server.remote.revision)
        assertEquals(NativeSyncFiles.hash(File(root, INDEX)), server.remote.files.getValue(INDEX).hash)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
    }

    @Test fun oldPartialReceiptIsAcceptedAfterOtherDevicesCommitUnrelatedFiles() = isolated { _, root, server ->
        File(root, INDEX).writeText("local")
        server.loseNextReceipt = true
        client(root, server).sync(idle)
        val operation = NativeSyncFiles.read(File(root, ".mojian-sync/outbox.json")).getString("operationId")
        server.update("测试本/other.mojian", "other device")
        val engine = client(root, server)
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        assertEquals("other device", File(root, "测试本/other.mojian").readText())
        assertEquals(2L, server.remote.revision)
        assertTrue(NativeSyncFiles.validId(operation))
    }

    @Test fun incomingWaitsForIdleAndReplaysAfterRestart() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        server.update(INDEX, "remote")
        client(root, server).sync({ false })
        assertEquals("base", File(root, INDEX).readText())
        assertTrue(File(root, ".mojian-sync/incoming.json").isFile)
        client(root, server).sync(idle)
        assertEquals("remote", File(root, INDEX).readText())
        assertFalse(File(root, ".mojian-sync/incoming.json").exists())
    }

    @Test fun startupRecoveryDoesNotContactServerAndPublishesCheckpointOnlyAfterAllFiles() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        val path = "测试本/page.mojian"
        server.update(path, "page")
        server.update(INDEX, "index")
        client(root, server).sync({ false })
        val before = NativeSyncFiles.read(File(root, ".mojian-sync/state.json")).getJSONObject("files").toString()
        server.offline = true
        val engine = client(root, server)
        assertTrue(engine.hasPendingIncoming)
        assertFalse(engine.recoverIncoming({ false }))
        assertEquals(before, NativeSyncFiles.read(File(root, ".mojian-sync/state.json")).getJSONObject("files").toString())
        assertTrue(engine.recoverIncoming(idle))
        assertFalse(engine.hasPendingIncoming)
        assertEquals("index", File(root, INDEX).readText())
        assertEquals("page", NativeSyncFiles.safeFile(root, path).readText())
    }

    @Test fun incomingGateRejectsGenerationChangeAndCanRetryWithoutNetwork() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        val engine = client(root, server)
        engine.sync(idle)
        server.update(INDEX, "remote")
        engine.sync({ false })
        server.offline = true
        assertFalse(engine.recoverIncoming({ action ->
            File(root, INDEX).writeText("changed after preparation")
            action()
            true
        }))
        assertEquals("changed after preparation", File(root, INDEX).readText())
        assertTrue(engine.hasPendingIncoming)
    }

    @Test fun incomingGateIgnoresMetadataOnlyMtimeRewrite() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        val engine = client(root, server)
        engine.sync(idle)
        server.update(INDEX, "remote")
        engine.sync({ false })
        server.offline = true
        assertTrue(engine.recoverIncoming({ action ->
            assertTrue(File(root, INDEX).setLastModified(1_600_000_000_000L))
            action()
            true
        }))
        assertEquals("remote", File(root, INDEX).readText())
        assertFalse(engine.hasPendingIncoming)
    }

    @Test fun cancellationAfterIncomingCommitBeginsDoesNotExposePartialLibrary() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        server.update("测试本/page.mojian", "page")
        server.update(INDEX, "index")
        client(root, server).sync({ false })
        var cancelled = false
        val engine = client(root, server)
        assertTrue(engine.recoverIncoming({ action -> cancelled = true; action(); true }, { cancelled }))
        assertFalse(engine.hasPendingIncoming)
        assertEquals("index", File(root, INDEX).readText())
    }

    @Test fun pendingIncomingDoesNotOverwriteLocallyEditedFile() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        server.update(INDEX, "remote")
        client(root, server).sync({ false })
        File(root, INDEX).writeText("local after staging")
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals("local after staging", File(root, INDEX).readText())
        // The staged version is not written over the edit: both sides go to conflict review.
        assertEquals(listOf(INDEX), engine.conflictReview().map { it.path })
    }

    @Test fun tombstoneSurvivesRestartAndDoesNotResurrectFile() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        val path = "测试本/page.mojian"
        val page = NativeSyncFiles.safeFile(root, path).apply { parentFile!!.mkdirs(); writeText("page") }
        client(root, server).sync(idle)
        server.update(path, null)
        client(root, server).sync(idle)
        assertFalse(page.exists())
        client(root, server).sync(idle)
        // The deleted page stays a tombstone; the only later upload may be `.mojian`, created by the local backup.
        assertNull(server.remote.files.getValue(path).hash)
        assertEquals(2L, server.remote.files.getValue(path).version)
        assertTrue(server.remote.files.keys.all { it == ".mojian" || server.remote.files.getValue(it).version <= 2 })
    }

    @Test fun concurrentEditPreservesBothCopiesAndLocalOriginal() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        File(root, INDEX).writeText("local")
        server.update(INDEX, "remote")
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals("local", File(root, INDEX).readText())
        val folder = File(root, ".mojian-sync/conflicts").listFiles()!!.single()
        assertEquals("local", File(folder, "local/$INDEX").readText())
        assertEquals("remote", File(folder, "remote/$INDEX").readText())
        assertTrue(engine.status.contains("冲突"))
        engine.sync(idle)
        assertEquals(1, File(root, ".mojian-sync/conflicts").listFiles()!!.size)
    }

    @Test fun conflictedPageAndSharedPdfStayBlockedWhileIndependentPageContinuesAcrossRestart() = isolated { _, root, server ->
        File(root, INDEX).writeText("{}")
        val pdf = """{"pdfPage":{"sourceOwnerPageId":"owner","sourceAssetName":"original.pdf"}}"""
        fun p(value: String) = pageFile("p", """{"text":"$value"}""", pdf)
        val pagePath = "测试本/分区/p.mojian"
        val independentPath = "测试本/分区/q.mojian"
        val sharedPath = ".mojian/assets/owner/original.pdf"
        fun write(path: String, content: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }
        val page = write(pagePath, p("base-p"))
        val independent = write(independentPath, text("q", "base-q"))
        val shared = write(sharedPath, "base-pdf")
        client(root, server).sync(idle)
        page.writeText(p("local-p"))
        server.update(pagePath, p("remote-p"))
        server.update(sharedPath, "remote-pdf")
        server.update(independentPath, text("q", "remote-q"))
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals(listOf(pagePath), engine.conflictReview().map { it.path })
        assertEquals(p("local-p"), page.readText())
        assertEquals("base-pdf", shared.readText())
        assertEquals(text("q", "remote-q"), independent.readText())
        server.update(independentPath, text("q", "remote-q2"))
        val restarted = client(root, server)
        restarted.sync(idle)
        assertEquals(listOf(pagePath), restarted.conflictReview().map { it.path })
        assertEquals("base-pdf", shared.readText())
        assertEquals(text("q", "remote-q2"), independent.readText())
    }

    @Test fun expiredMergeAncestorPreservesConflictAndStillReceivesIndependentPages() = isolated { _, root, server ->
        File(root, INDEX).writeText("{}")
        val page = File(root, "测试本/分区/p.mojian").apply { parentFile!!.mkdirs(); writeText(pageFile("p", """{"revision":1,"strokes":[{"id":"a"}]}""")) }
        File(root, "其他/q.mojian").apply { parentFile!!.mkdirs(); writeText("base q") }
        client(root, server).sync(idle)
        val base = server.remote.files.getValue("测试本/分区/p.mojian").hash!!
        page.writeText(pageFile("p", """{"revision":2,"strokes":[{"id":"a"},{"id":"local"}]}"""))
        server.update("测试本/分区/p.mojian", pageFile("p", """{"revision":2,"strokes":[{"id":"a"},{"id":"remote"}]}"""))
        server.update("其他/q.mojian", "independent page")
        server.blobs.remove(base)
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals(listOf("测试本/分区/p.mojian"), engine.conflictReview().map { it.path })
        assertTrue(page.readText().contains("local"))
        assertEquals("independent page", File(root, "其他/q.mojian").readText())
    }

    @Test fun strokesWrittenOnBothDevicesMergeAndUploadWithoutConflict() = isolated { _, root, server ->
        File(root, INDEX).writeText("library")
        val page = File(root, "测试本/分区/p.mojian").apply { parentFile!!.mkdirs(); writeText(pageFile("p", """{"revision":1,"strokes":[{"id":"a"}]}""")) }
        client(root, server).sync(idle)
        page.writeText(pageFile("p", """{"revision":2,"strokes":[{"id":"a"},{"id":"local"}]}"""))
        server.update("测试本/分区/p.mojian", pageFile("p", """{"revision":2,"strokes":[{"id":"a"},{"id":"remote"}]}"""))
        val engine = client(root, server)
        engine.sync(idle)
        assertFalse(engine.status, File(root, ".mojian-sync/conflict.json").exists())
        val merged = JSONObject(page.readText()).getJSONObject("content")
        assertEquals(listOf("a", "local", "remote"), (0 until 3).map { merged.getJSONArray("strokes").getJSONObject(it).getString("id") })
        assertEquals(3L, merged.getLong("revision"))
        engine.sync(idle)
        val uploaded = server.remote.files.getValue("测试本/分区/p.mojian").hash!!
        assertEquals(page.readText(), String(server.blobs.getValue(uploaded)))
    }

    @Test fun pageResavedWithoutRealEditsTakesTheServerVersion() = isolated { _, root, server ->
        File(root, INDEX).writeText("library")
        val page = File(root, "测试本/分区/p.mojian").apply { parentFile!!.mkdirs(); writeText(pageFile("p", """{"revision":1,"updatedAt":"1","strokes":[{"id":"a"}]}""")) }
        client(root, server).sync(idle)
        page.writeText(pageFile("p", """{"revision":2,"updatedAt":"2","strokes":[{"id":"a"}]}"""))
        val remote = pageFile("p", """{"revision":2,"updatedAt":"3","strokes":[{"id":"a"},{"id":"b"}]}""")
        server.update("测试本/分区/p.mojian", remote)
        val uploaded = server.remote.files.getValue("测试本/分区/p.mojian")
        client(root, server).sync(idle)
        assertEquals(remote, page.readText())
        client(root, server).sync(idle)
        assertEquals(uploaded, server.remote.files.getValue("测试本/分区/p.mojian"))
    }

    @Test fun journalConflictDefersDependenciesAndReplaysInterruptedChoiceWithoutLosingOtherPages() = isolated { _, root, server ->
        File(root, INDEX).writeText("{}")
        val a = "测试本/分区/a.mojian"
        val b = "测试本/分区/b.mojian"
        val c = "测试本/分区/c.mojian"
        val asset = ".mojian/assets/a/original.pdf"
        fun write(path: String, value: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(value) }
        val ids = mapOf(a to "a", b to "b", c to "c")
        fun value(path: String, body: String) = ids[path]?.let { text(it, body) } ?: body
        for (path in listOf(a, b, c, asset)) write(path, value(path, "base"))
        val engine = client(root, server)
        engine.sync(idle)
        for (path in listOf(a, b, c, asset)) server.update(path, value(path, "remote"))
        engine.sync({ false })
        write("$asset.previous", "base")
        write(asset, "remote")
        write(a, value(a, "my-a"))
        write(b, value(b, "my-b"))
        server.offline = true
        assertTrue(engine.status, engine.recoverIncoming(idle))
        assertEquals(value(c, "remote"), File(root, c).readText())
        assertEquals("base", File(root, asset).readText())
        assertFalse(engine.hasPendingIncoming)
        assertEquals(setOf(a, b), engine.conflictReview().map { it.path }.toSet())
        server.offline = false
        server.update(c, value(c, "newer-c"))
        engine.sync(idle)
        assertEquals(value(c, "newer-c"), File(root, c).readText())
        assertFalse(engine.resolveConflicts(mapOf(a to NativeSyncChoice.LOCAL), { false }))
        assertTrue(engine.hasPendingIncoming)
        server.offline = true
        val restarted = client(root, server)
        assertTrue(restarted.status, restarted.recoverIncoming(idle))
        assertEquals(value(a, "my-a"), File(root, a).readText())
        assertEquals(value(b, "my-b"), File(root, b).readText())
        assertEquals("remote", File(root, asset).readText())
        assertEquals(listOf(b), restarted.conflictReview().map { it.path })
        assertTrue(restarted.resolveConflicts(mapOf(b to NativeSyncChoice.REMOTE), idle))
        assertEquals(value(b, "remote"), File(root, b).readText())
        assertTrue(restarted.conflictReview().isEmpty())
    }

    @Test fun reviewedConflictsResolvePerFileIncludingKeepBoth() = isolated { _, root, server ->
        File(root, INDEX).writeText("{}")
        val page = File(root, "测试本/分区/p.mojian").apply { parentFile!!.mkdirs(); writeText(pageFile("p", """{"revision":1,"strokes":[{"id":"a","color":1}]}""")) }
        val note = File(root, "测试本/分区/q.mojian").apply { writeText(pageFile("q", """{"revision":1,"strokes":[{"id":"b","color":1}]}""")) }
        client(root, server).sync(idle)
        page.writeText(pageFile("p", """{"revision":2,"strokes":[{"id":"a","color":2},{"id":"mine"}]}"""))
        note.writeText(pageFile("q", """{"revision":2,"strokes":[{"id":"b","color":2}]}"""))
        server.update("测试本/分区/p.mojian", pageFile("p", """{"revision":2,"strokes":[{"id":"a","color":3},{"id":"theirs"}]}"""))
        server.update("测试本/分区/q.mojian", pageFile("q", """{"revision":2,"strokes":[{"id":"b","color":3}]}"""))
        val engine = client(root, server)
        engine.sync(idle)
        val review = engine.conflictReview().associateBy { it.path }
        assertEquals(setOf("测试本/分区/p.mojian", "测试本/分区/q.mojian"), review.keys)
        assertEquals(NativeSyncMerge.Summary(1, 1, 1), review.getValue("测试本/分区/p.mojian").summary)
        assertEquals("modify", review.getValue("测试本/分区/q.mojian").action)
        assertTrue(engine.resolveConflicts(mapOf("测试本/分区/p.mojian" to NativeSyncChoice.BOTH), idle))
        assertEquals(listOf("a", "mine", "theirs"), JSONObject(page.readText()).getJSONObject("content").getJSONArray("strokes").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } })
        assertEquals(listOf("测试本/分区/q.mojian"), engine.conflictReview().map { it.path })
        assertTrue(engine.resolveConflicts(mapOf("测试本/分区/q.mojian" to NativeSyncChoice.REMOTE), idle))
        assertEquals(pageFile("q", """{"revision":2,"strokes":[{"id":"b","color":3}]}"""), note.readText())
        assertTrue(engine.conflictReview().isEmpty())
        engine.sync(idle)
        assertEquals(page.readText(), String(server.blobs.getValue(server.remote.files.getValue("测试本/分区/p.mojian").hash!!)))
        assertFalse(File(root, ".mojian-sync/conflict.json").exists())
    }

    @Test fun cancelledSyncDoesNotUploadOrApply() = isolated { _, root, server ->
        File(root, INDEX).writeText("local")
        client(root, server).sync(idle) { true }
        assertEquals(0, server.commits)
        assertTrue(server.remote.files.isEmpty())
    }

    @Test fun crashAfterFirstIncomingWriteCanReplayRemainingJournal() = isolated { _, root, server ->
        File(root, INDEX).writeText("base")
        client(root, server).sync(idle)
        val page = "测试本/page.mojian"
        server.update(page, "page")
        server.update(INDEX, "index")
        client(root, server).sync({ false })
        val journal = NativeSyncFiles.read(File(root, ".mojian-sync/incoming.json"))
        val entry = journal.getJSONObject("files").getJSONObject(page).getJSONObject("entry")
        val hash = entry.getString("hash")
        NativeSyncFiles.copyVerified(File(root, ".mojian-sync/transfer/$hash"), NativeSyncFiles.safeFile(root, page), hash)
        client(root, server).sync(idle)
        assertEquals("page", NativeSyncFiles.safeFile(root, page).readText())
        assertEquals("index", File(root, INDEX).readText())
    }

    @Test fun changedServerIdentityStopsWithoutReplacingLocalFiles() = isolated { _, root, server ->
        File(root, INDEX).writeText("original")
        client(root, server).sync(idle)
        server.remote = NativeSyncManifest("other-workspace", 0, emptyMap())
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals("original", File(root, INDEX).readText())
        assertTrue(engine.status.contains("身份"))
    }
}
