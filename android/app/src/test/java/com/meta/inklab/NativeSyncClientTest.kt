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
            destination.parentFile!!.mkdirs(); destination.writeBytes(blobs.getValue(hash)); afterDownload?.invoke()
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
            action(directory, root, Server())
        } finally { directory.deleteRecursively() }
    }

    private fun client(root: File, server: Server) = NativeSyncClient(root, "https://example.test/mojian-sync/v1", "test-token", transport = server)
    private val idle: (() -> Unit) -> Boolean = { action -> action(); true }

    @Test fun accidentallyMissingLocalLibraryNeverUploadsDeletion() = isolated { _, root, server ->
        val library = File(root, "library.json").apply { writeText("base") }
        val engine = client(root, server)
        engine.sync(idle)
        val revision = server.remote.revision
        val expectedHash = server.remote.files.getValue("library.json").hash
        library.delete()
        engine.sync(idle)
        assertEquals(revision, server.remote.revision)
        assertEquals(expectedHash, server.remote.files.getValue("library.json").hash)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        assertTrue(engine.status.contains("library.json"))
    }

    @Test fun remoteLibraryTombstoneStopsSyncAndPreservesLocalLibrary() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        val engine = client(root, server)
        engine.sync(idle)
        server.update("library.json", null)
        engine.sync(idle)
        assertEquals("base", File(root, "library.json").readText())
        assertTrue(engine.status.contains("远端 library.json"))
        assertFalse(engine.hasPendingIncoming)
    }

    @Test fun localMutationBeforeConflictCaptureDoesNotPermanentlyBlockSync() = isolated { _, root, server ->
        val page = File(root, "library.json").apply { writeText("base") }
        val engine = client(root, server)
        engine.sync(idle)
        page.writeText("local edit")
        server.update("library.json", "remote edit")
        server.afterManifest = { LocalMutationEpoch.forWorkspace(root).begin().use { } }
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/conflict.json").exists())
        assertFalse(File(root, ".mojian-sync/conflicts").exists())
        server.afterManifest = null
        engine.sync(idle)
        assertTrue(File(root, ".mojian-sync/conflict.json").isFile)
    }

    @Test fun localMutationDuringConflictDownloadDoesNotPublishConflictMarker() = isolated { _, root, server ->
        val page = File(root, "library.json").apply { writeText("base") }
        val engine = client(root, server)
        engine.sync(idle)
        page.writeText("local edit")
        server.update("library.json", "remote edit")
        server.afterDownload = { LocalMutationEpoch.forWorkspace(root).begin().use { } }
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/conflict.json").exists())
        assertEquals("local edit", page.readText())
        server.afterDownload = null
        engine.sync(idle)
        assertTrue(File(root, ".mojian-sync/conflict.json").isFile)
    }

    @Test fun localMutationDuringScanAndStageCannotPublishOutboxEvenWhenBytesReturnToSameValue() = isolated { _, root, server ->
        File(root, "library.json").writeText("local")
        val epoch = LocalMutationEpoch.forWorkspace(root)
        server.afterManifest = { epoch.begin().use { File(root, "library.json").writeText("local") } }
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
        File(root, "library.json").writeText("old-library")
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
        File(root, "library.json").writeText("first")
        server.loseNextReceipt = true
        client(root, server).sync(idle)
        assertTrue(File(root, ".mojian-sync/outbox.json").isFile)
        File(root, "library.json").writeText("second")
        client(root, server).sync(idle)
        assertEquals(2, server.remote.revision)
        assertEquals(NativeSyncFiles.hash(File(root, "library.json")), server.remote.files.getValue("library.json").hash)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
    }

    @Test fun oldPartialReceiptIsAcceptedAfterOtherDevicesCommitUnrelatedFiles() = isolated { _, root, server ->
        File(root, "library.json").writeText("local")
        server.loseNextReceipt = true
        client(root, server).sync(idle)
        val operation = NativeSyncFiles.read(File(root, ".mojian-sync/outbox.json")).getString("operationId")
        server.update("notebooks/book/pages/other.json", "other device")
        val engine = client(root, server)
        engine.sync(idle)
        assertFalse(File(root, ".mojian-sync/outbox.json").exists())
        assertEquals("other device", File(root, "notebooks/book/pages/other.json").readText())
        assertEquals(2L, server.remote.revision)
        assertTrue(NativeSyncFiles.validId(operation))
    }

    @Test fun incomingWaitsForIdleAndReplaysAfterRestart() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        server.update("library.json", "remote")
        client(root, server).sync({ false })
        assertEquals("base", File(root, "library.json").readText())
        assertTrue(File(root, ".mojian-sync/incoming.json").isFile)
        client(root, server).sync(idle)
        assertEquals("remote", File(root, "library.json").readText())
        assertFalse(File(root, ".mojian-sync/incoming.json").exists())
    }

    @Test fun startupRecoveryDoesNotContactServerAndPublishesCheckpointOnlyAfterAllFiles() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        val path = "notebooks/book/pages/page.json"
        server.update(path, "page")
        server.update("library.json", "index")
        client(root, server).sync({ false })
        val before = NativeSyncFiles.read(File(root, ".mojian-sync/state.json")).getJSONObject("files").toString()
        server.offline = true
        val engine = client(root, server)
        assertTrue(engine.hasPendingIncoming)
        assertFalse(engine.recoverIncoming({ false }))
        assertEquals(before, NativeSyncFiles.read(File(root, ".mojian-sync/state.json")).getJSONObject("files").toString())
        assertTrue(engine.recoverIncoming(idle))
        assertFalse(engine.hasPendingIncoming)
        assertEquals("index", File(root, "library.json").readText())
        assertEquals("page", NativeSyncFiles.safeFile(root, path).readText())
    }

    @Test fun incomingGateRejectsGenerationChangeAndCanRetryWithoutNetwork() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        val engine = client(root, server)
        engine.sync(idle)
        server.update("library.json", "remote")
        engine.sync({ false })
        server.offline = true
        assertFalse(engine.recoverIncoming({ action ->
            File(root, "library.json").writeText("changed after preparation")
            action()
            true
        }))
        assertEquals("changed after preparation", File(root, "library.json").readText())
        assertTrue(engine.hasPendingIncoming)
    }

    @Test fun cancellationAfterIncomingCommitBeginsDoesNotExposePartialLibrary() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        server.update("notebooks/book/pages/page.json", "page")
        server.update("library.json", "index")
        client(root, server).sync({ false })
        var cancelled = false
        val engine = client(root, server)
        assertTrue(engine.recoverIncoming({ action -> cancelled = true; action(); true }, { cancelled }))
        assertFalse(engine.hasPendingIncoming)
        assertEquals("index", File(root, "library.json").readText())
    }

    @Test fun pendingIncomingDoesNotOverwriteLocallyEditedFile() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        server.update("library.json", "remote")
        client(root, server).sync({ false })
        File(root, "library.json").writeText("local after staging")
        client(root, server).sync(idle)
        assertEquals("local after staging", File(root, "library.json").readText())
        assertTrue(File(root, ".mojian-sync/incoming.json").exists())
    }

    @Test fun tombstoneSurvivesRestartAndDoesNotResurrectFile() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        val path = "notebooks/book/pages/page.json"
        val page = NativeSyncFiles.safeFile(root, path).apply { parentFile!!.mkdirs(); writeText("page") }
        client(root, server).sync(idle)
        server.update(path, null)
        client(root, server).sync(idle)
        assertFalse(page.exists())
        client(root, server).sync(idle)
        assertNull(server.remote.files.getValue(path).hash)
        assertEquals(2, server.remote.revision)
    }

    @Test fun concurrentEditPreservesBothCopiesAndLocalOriginal() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        File(root, "library.json").writeText("local")
        server.update("library.json", "remote")
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals("local", File(root, "library.json").readText())
        val folder = File(root, ".mojian-sync/conflicts").listFiles()!!.single()
        assertEquals("local", File(folder, "local/library.json").readText())
        assertEquals("remote", File(folder, "remote/library.json").readText())
        assertTrue(engine.status.contains("冲突"))
        engine.sync(idle)
        assertEquals(1, File(root, ".mojian-sync/conflicts").listFiles()!!.size)
    }

    @Test fun cancelledSyncDoesNotUploadOrApply() = isolated { _, root, server ->
        File(root, "library.json").writeText("local")
        client(root, server).sync(idle) { true }
        assertEquals(0, server.commits)
        assertTrue(server.remote.files.isEmpty())
    }

    @Test fun crashAfterFirstIncomingWriteCanReplayRemainingJournal() = isolated { _, root, server ->
        File(root, "library.json").writeText("base")
        client(root, server).sync(idle)
        val page = "notebooks/book/pages/page.json"
        server.update(page, "page")
        server.update("library.json", "index")
        client(root, server).sync({ false })
        val journal = NativeSyncFiles.read(File(root, ".mojian-sync/incoming.json"))
        val entry = journal.getJSONObject("files").getJSONObject(page).getJSONObject("entry")
        val hash = entry.getString("hash")
        NativeSyncFiles.copyVerified(File(root, ".mojian-sync/transfer/$hash"), NativeSyncFiles.safeFile(root, page), hash)
        client(root, server).sync(idle)
        assertEquals("page", NativeSyncFiles.safeFile(root, page).readText())
        assertEquals("index", File(root, "library.json").readText())
    }

    @Test fun changedServerIdentityStopsWithoutReplacingLocalFiles() = isolated { _, root, server ->
        File(root, "library.json").writeText("original")
        client(root, server).sync(idle)
        server.remote = NativeSyncManifest("other-workspace", 0, emptyMap())
        val engine = client(root, server)
        engine.sync(idle)
        assertEquals("original", File(root, "library.json").readText())
        assertTrue(engine.status.contains("身份"))
    }
}
