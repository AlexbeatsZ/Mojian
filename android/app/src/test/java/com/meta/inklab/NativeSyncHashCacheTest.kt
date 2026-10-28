package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSyncHashCacheTest {
    @Test fun unsupportedCanonicalAssetFailsScanAndCloneWithoutPublishingPartialCopy() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "mojian-sync-unsupported").toFile()
        try {
            val root = File(directory, "source").apply { mkdirs() }
            File(root, "library.json").writeText("{}")
            val asset = File(root, "notebooks/book/assets/page/original.中文").apply { parentFile!!.mkdirs(); writeText("original") }
            assertThrows(IllegalArgumentException::class.java) { NativeSyncClient.scanWorkspace(root) }
            val target = File(directory, "copy")
            assertThrows(IllegalArgumentException::class.java) { NativeSyncClient.cloneWorkspace(root, target) }
            assertFalse(target.exists())
            assertEquals("original", asset.readText())
            asset.delete()
            listOf("note.json.previous", "pending.tmp", "note-sync-conflict-device.json").forEach {
                File(root, "notebooks/book/pages/$it").apply { parentFile!!.mkdirs(); writeText("recovery") }
            }
            assertEquals(setOf("library.json"), NativeSyncClient.scanWorkspace(root).keys)
            File(root, "notebooks/book/pages/页面.json").writeText("unsupported-page")
            assertThrows(IllegalArgumentException::class.java) { NativeSyncClient.scanWorkspace(root) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun unchangedFilesReuseHashesAndChangedOrDeletedFilesInvalidateCache() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-sync-hashes").toFile()
        try {
            val file = File(root, "library.json").apply { writeText("first") }
            var hashes = 0
            val cache = NativeSyncHashCache { hashes++; NativeSyncFiles.hash(it) }
            val initial = cache.scan(root)
            assertEquals(initial, cache.scan(root))
            assertEquals(1, hashes)
            file.writeText("changed length")
            assertNotEquals(initial, cache.scan(root))
            assertEquals(2, hashes)
            file.delete()
            assertTrue(cache.scan(root).isEmpty())
            file.writeText("first")
            cache.scan(root)
            assertEquals(3, hashes)
            cache.invalidate("library.json")
            cache.scan(root)
            assertEquals(4, hashes)
        } finally { root.deleteRecursively() }
    }
}
