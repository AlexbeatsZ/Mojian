package com.meta.inklab

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArchitectureRepairTest {
    private fun isolated(action: (File) -> Unit) {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-architecture-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/workspace-v2/$name.json"))
        .bufferedReader().use { it.readText() }

    @Test fun sharedGoldenHierarchyPreservesExtensionsAndNavigationIsLocal() = isolated { root ->
        val index = root.resolve("library.json").apply { writeText(fixture("library")) }
        val repository = PageContentRepository(root)
        val library = repository.loadLibrary()
        val before = index.readText()
        val notebook = library.activeNotebook()
        notebook.expanded = false
        notebook.allSections().first().expanded = false
        assertFalse(SharedWorkspaceCodec.encodeLibrary(library, JSONObject(before)).toString(), repository.saveLibrary(library))
        assertEquals(before, index.readText())
        val navigation = WorkspaceNavigationPreferences.encode(library)
        val reopened = repository.loadLibrary()
        WorkspaceNavigationPreferences.restore(reopened, navigation)
        assertFalse(reopened.activeNotebook().expanded)
        assertFalse(reopened.activeNotebook().allSections().first().expanded)
        assertEquals("page.dot", reopened.activeNotebook().selectedPageId)
        reopened.activeNotebook().title = "renamed"
        assertTrue(repository.saveLibrary(reopened))
        val saved = JSONObject(index.readText())
        assertTrue(saved.getJSONObject("futureLibrary").getBoolean("kept"))
        assertEquals("retained", saved.getJSONArray("notebooks").getJSONObject(0).getString("futureNotebook"))
    }

    @Test fun assetFailurePublishesNoPartialBytesAndBlocksSyncDuringWriting() = isolated { root ->
        val repository = PageContentRepository(root)
        val epoch = LocalMutationEpoch.forWorkspace(root)
        assertThrows(IllegalStateException::class.java) {
            repository.writeAsset("book", "page", "asset.bin") {
                assertNull(epoch.capture())
                it.write(byteArrayOf(1, 2))
                error("copy interrupted")
            }
        }
        assertFalse(repository.assetDirectory("book", "page").resolve("asset.bin").exists())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        assertNotNull(epoch.capture())
        val file = repository.writeAsset("book", "page", "asset.bin") { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test fun externallyReloadedRevisionCannotGoBackwards() = isolated { root ->
        val repository = PageContentRepository(root)
        val queue = PageWriteQueue()
        try {
            var live = repository.load("book", "page")
            fun save(document: PageInsertDocument, value: String) {
                val snapshot = PageInsertDocument("book", "page", sharedSource = SharedWorkspaceCodec.copy(document.sharedSource).put("edit", value))
                queue.submit(repository, document, snapshot) { throw AssertionError(it) }
                queue.flush()
            }
            save(live, "first")
            val file = repository.pageFile("book", "page")
            val remote = JSONObject(file.readText()).put("revision", 40).put("remote", true)
            val temporary = root.resolve("remote.tmp").apply { writeText(remote.toString()) }
            Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            live = repository.load("book", "page")
            save(live, "second")
            assertEquals(41, JSONObject(file.readText()).getLong("revision"))
        } finally { queue.close() }
    }

    @Test fun recoveryRestoresMissingIndexAndKeepsCorruptBytes() = isolated { root ->
        root.resolve("library.json.previous").writeText(fixture("library"))
        val repository = PageContentRepository(root)
        assertEquals("book.dot", repository.loadLibrary().activeNotebookId)
        val page = repository.pageFile("book.dot", "page.dot").apply { parentFile!!.mkdirs() }
        File(page.path + ".previous").writeText(fixture("page"))
        page.writeText("corrupt data")
        assertEquals(5, repository.load("book.dot", "page.dot").sharedSource.getLong("revision"))
        assertTrue(root.resolve(".mojian-corrupt.previous").walkTopDown().any { it.isFile && it.readText() == "corrupt data" })
    }

    @Test fun unrelatedAppearanceEditPreservesUnknownEnumValues() {
        val source = JSONObject().put("paperKind", 81).put("background", 91).put("future", true)
        val view = SharedWorkspaceCodec.decodeAppearance(source)
        view.pageTitleVisible = false
        val result = SharedWorkspaceCodec.encodeAppearance(view, source)
        assertEquals(81, result.getInt("paperKind"))
        assertEquals(91, result.getInt("background"))
        assertTrue(result.getBoolean("future"))
    }

    @Test fun sharedThreeWayDecisionsIncludeBothDeletionConflicts() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/sync-decisions.json")).bufferedReader().use { it.readText() }
        SharedWorkspaceCodec.items(org.json.JSONArray(text)).forEach { row ->
            fun value(key: String): String? = if (row.isNull(key)) null else row.getString(key)
            assertEquals(row.getString("decision"), NativeSyncDecisions.decide(value("base"), value("local"), value("remote")).name)
        }
    }
    @Test fun identifiersAndBrushMetadataAreLossless() {
        listOf("book.dot", "page-0123_ab").forEach { assertEquals(it, PageContentRepository.safeId(it)) }
        // Native sync paths are ASCII-only; a local-only ID would stop the whole library's sync.
        listOf("..", "a/b", "a:b", "中文笔记", ".hidden", "trailing.").forEach { id -> assertThrows(IllegalArgumentException::class.java) { PageContentRepository.safeId(id) } }
        assertEquals(BrushProfiles.StoredParameters(), BrushProfiles.storedParameters("inklab-pen-style-v1:base64-p3-s2"))
        assertEquals(BrushProfiles.StoredParameters(250, 120, 75), BrushProfiles.storedParameters("inklab-fountain-v4-p2.5-s1.2-t75"))
    }
}
