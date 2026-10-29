package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSyncConfigurationTest {
    @Test fun defaultUsesSakuraHttpsButDoesNotEnableSyncOrShipCredentials() {
        val configuration = NativeSyncConfiguration()
        assertEquals("https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/", configuration.endpoint)
        assertFalse(configuration.enabled)
        assertTrue(configuration.token.isEmpty())
    }

    @Test fun endpointRejectsCredentialsRedirectTargetsAndNonLocalHttp() {
        listOf("http://example.com/mojian-sync/v1", "https://user:pass@example.com/", "https://example.com/?token=x", "https://example.com/#x").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { NativeSyncFiles.endpoint(value) }
        }
        assertEquals("http", NativeSyncFiles.endpoint("http://localhost:18742/mojian-sync/v1").scheme)
        assertEquals("https", NativeSyncFiles.endpoint("https://example.com/mojian-sync/v1").scheme)
    }

    @Test fun pathsExcludeTraversalStateRecoveryAndDevicePreferences() {
        listOf("../library.json", "settings.json", ".mojian-sync/state.json", "notebooks/book/pages/p.json.previous", "notebooks/book/pages/p-sync-conflict-x.json").forEach {
            assertFalse(NativeSyncClient.validPath(it))
        }
        assertTrue(NativeSyncClient.validPath("notebooks/book/assets/page/original.pdf"))
    }

    @Test fun configurationStaysPrivateAndCloneRequiresFreshExternalTarget() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "mojian-native-config").toFile()
        try {
            val source = File(directory, "source").apply { mkdirs() }
            File(source, "library.json").writeText("{}")
            val privateDirectory = File(directory, "private").apply { mkdirs() }
            val config = NativeSyncConfiguration("https://example.com/mojian-sync/v1", "secret-token", true)
            config.save(privateDirectory, source)
            assertEquals(config, NativeSyncConfiguration.load(privateDirectory, source))
            assertFalse(config.toString().contains("secret-token"))
            assertThrows(IllegalArgumentException::class.java) { config.save(source, source) }
            assertThrows(IllegalArgumentException::class.java) { NativeSyncConfiguration.cloneWorkspace(source, source) }
            assertThrows(IllegalArgumentException::class.java) { NativeSyncConfiguration.cloneWorkspace(source, File(source, "nested")) }
            val target = File(directory, "target")
            NativeSyncConfiguration.cloneWorkspace(source, target)
            assertEquals("{}", File(target, "library.json").readText())
            assertThrows(IllegalArgumentException::class.java) { NativeSyncConfiguration.cloneWorkspace(source, target) }
            val syncthing = File(directory, "syncthing").apply { mkdirs() }
            File(syncthing, ".stfolder").mkdir()
            assertThrows(IllegalArgumentException::class.java) { NativeSyncConfiguration.cloneWorkspace(source, File(syncthing, "nested")) }
        } finally { directory.deleteRecursively() }
    }
}
