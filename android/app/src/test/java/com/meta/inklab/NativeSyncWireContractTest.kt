package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeSyncWireContractTest {
    private fun repository(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "sync-server/test/fixtures/v1-wire.json").isFile) return directory
            directory = directory.parentFile
        }
        error("Cannot locate shared sync-server/test/fixtures/v1-wire.json from working directory")
    }

    private fun isolated(action: (File) -> Unit) {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-sync-wire").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }

    @Test fun consumesSharedServerFixtureForUploadReceiptAndDeletion() = isolated { root ->
        val fixture = JSONObject(File(repository(), "sync-server/test/fixtures/v1-wire.json").readText(Charsets.UTF_8))
        assertEquals("mojian-sync/v1", fixture.getString("protocol"))
        val content = File(root, "content").apply { writeText(fixture.getString("utf8"), Charsets.UTF_8) }
        assertEquals(fixture.getString("hash"), NativeSyncFiles.hash(content))
        val commit = fixture.getJSONObject("commit")
        val receipt = NativeSyncManifest.parse(fixture.getJSONObject("receipt"))
        val changes = NativeSyncFiles.changes(commit.getJSONArray("changes"))
        assertTrue(NativeSyncFiles.validId(commit.getString("deviceId")))
        assertEquals(commit.getString("workspaceId"), receipt.workspaceId)
        assertEquals(commit.getString("operationId"), receipt.operationId)
        assertEquals(changes.map { it.path }.toSet(), receipt.files.keys)
        changes.forEach { change ->
            assertEquals(0L, change.expectedVersion)
            assertEquals(change.hash, receipt.files.getValue(change.path).hash)
            assertEquals(1L, receipt.files.getValue(change.path).version)
        }
        val deletion = fixture.getJSONObject("deletion")
        val tombstone = NativeSyncManifest.parse(fixture.getJSONObject("deletionReceipt"))
        assertEquals(deletion.getString("operationId"), tombstone.operationId)
        NativeSyncFiles.changes(deletion.getJSONArray("changes")).forEach { change ->
            assertEquals(1L, change.expectedVersion)
            assertNull(change.hash)
            assertNull(tombstone.files.getValue(change.path).hash)
            assertEquals(2L, tombstone.files.getValue(change.path).version)
        }
    }

    @Test fun realNodeServerSupportsTwoHttpClientsRoundtripAndTombstone() = isolated { directory ->
        val serverModule = File(repository(), "sync-server/server.mjs").toURI().toString()
        val token = "isolated-native-sync-test-token-0123456789"
        val script = """
            const { createSyncServer, prefix } = await import(process.env.MOJIAN_TEST_MODULE);
            const service = await createSyncServer({root: process.env.MOJIAN_TEST_DATA, token: process.env.MOJIAN_TEST_TOKEN});
            service.server.listen(0, '127.0.0.1', () => console.log('http://127.0.0.1:' + service.server.address().port + prefix));
            process.stdin.resume();
            process.stdin.once('data', async () => { await service.close(); process.exit(0); });
        """.trimIndent()
        val builder = ProcessBuilder("node", "--input-type=module", "-e", script)
            .redirectError(File(directory, "server-stderr.log"))
        builder.environment().putAll(mapOf("MOJIAN_TEST_MODULE" to serverModule,
            "MOJIAN_TEST_DATA" to File(directory, "server").path, "MOJIAN_TEST_TOKEN" to token))
        val process = builder.start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val endpoint = reader.submit<String> { process.inputStream.bufferedReader().readLine() }.get(15, TimeUnit.SECONDS)
            require(endpoint != null && endpoint.startsWith("http://127.0.0.1:")) { "Node server did not announce loopback endpoint" }
            fun workspace(name: String) = File(directory, name).apply {
                mkdirs(); File(this, ".mojian-native-workspace").writeText("isolated test")
                File(this, "library.json").writeText("isolated-index")
            }
            val firstRoot = workspace("first")
            val secondRoot = workspace("second")
            val first = NativeSyncClient(firstRoot, endpoint, token)
            val second = NativeSyncClient(secondRoot, endpoint, token)
            val idle: (() -> Unit) -> Boolean = { action -> action(); true }
            val path = "notebooks/book/pages/page.json"
            val firstPage = NativeSyncFiles.safeFile(firstRoot, path).apply { parentFile!!.mkdirs(); writeText("first-device-content") }
            File(firstRoot, "library.json").writeText("isolated-index")
            first.sync(idle)
            second.sync(idle)
            val secondPage = NativeSyncFiles.safeFile(secondRoot, path)
            assertTrue("${first.status}; ${second.status}", secondPage.isFile)
            assertEquals("first-device-content", secondPage.readText())
            secondPage.writeText("second-device-edit")
            second.sync(idle)
            first.sync(idle)
            assertEquals("${first.status}; ${second.status}", "second-device-edit", firstPage.readText())
            assertTrue(secondPage.delete())
            second.sync(idle)
            first.sync(idle)
            assertFalse("${first.status}; ${second.status}", firstPage.exists())
            val remote = NativeSyncHttpTransport(endpoint, token).manifest { false }
            assertNull(remote.files.getValue(path).hash)
            assertEquals(3L, remote.revision)
            assertFalse(first.hasPendingIncoming)
            assertFalse(second.hasPendingIncoming)
        } finally {
            runCatching { process.outputStream.write(1); process.outputStream.flush() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            reader.shutdownNow()
        }
    }
}
