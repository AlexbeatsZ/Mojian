package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSyncHashCacheTest {
    private val INDEX = "测试本/.mojian-folder.json"

    @Test fun unsupportedItemsAreReportedAndBlockCloneWithoutPublishingPartialCopy() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "mojian-sync-unsupported").toFile()
        try {
            val root = File(directory, "source").apply { mkdirs() }
            File(root, "测试本").mkdirs()
            File(root, INDEX).writeText("{}")
            val note = File(root, "测试本/说明.txt").apply { writeText("original") }
            val cache = NativeSyncHashCache()
            assertEquals(setOf("测试本", INDEX), cache.scan(root).keys)
            assertEquals(setOf("测试本/说明.txt"), cache.failures.keys)
            val target = File(directory, "copy")
            val error = assertThrows(IllegalArgumentException::class.java) { NativeSyncClient.cloneWorkspace(root, target) }
            assertTrue(error.message!!.contains("测试本/说明.txt"))
            assertFalse(target.exists())
            assertEquals("original", note.readText())
            note.delete()
            listOf("页.mojian.previous", "pending.tmp", "页-sync-conflict-device.mojian").forEach { File(root, "测试本/$it").writeText("recovery") }
            File(root, ".mojian/previous").mkdirs()
            assertEquals(setOf("测试本", INDEX, ".mojian"), cache.scan(root).keys)
            assertTrue(cache.failures.isEmpty())
            File(root, "测试本/坏名.").mkdirs()
            cache.scan(root)
            assertEquals(setOf("测试本/坏名."), cache.failures.keys)
        } finally { directory.deleteRecursively() }
    }

    @Test fun unchangedFilesReuseHashesAndChangedOrDeletedFilesInvalidateCache() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-sync-hashes").toFile()
        try {
            File(root, "测试本").mkdirs()
            val file = File(root, INDEX).apply { writeText("first") }
            var hashes = 0
            val cache = NativeSyncHashCache { hashes++; NativeSyncFiles.hash(it) }
            val initial = cache.scan(root)
            assertEquals(initial, cache.scan(root))
            assertEquals(1, hashes)
            file.writeText("changed length")
            assertNotEquals(initial, cache.scan(root))
            assertEquals(2, hashes)
            file.delete()
            assertEquals(mapOf("测试本" to NativeSyncFiles.DIRECTORY), cache.scan(root))
            file.writeText("first")
            cache.scan(root)
            assertEquals(3, hashes)
            cache.invalidate(INDEX)
            cache.scan(root)
            assertEquals(4, hashes)
        } finally { root.deleteRecursively() }
    }

    @Test fun mtimeRewrittenWhileHashingDoesNotFailTheScan() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-sync-mtime-drift").toFile()
        try {
            File(root, "测试本").mkdirs()
            File(root, INDEX).writeText("library")
            val cache = NativeSyncHashCache { file ->
                file.setLastModified(file.lastModified() - 86_400_000L)
                NativeSyncFiles.hash(file)
            }
            assertEquals(setOf("测试本", INDEX), cache.scan(root).keys)
        } finally { root.deleteRecursively() }
    }
}
