package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class NavigationMutationTest {
    @Test
    fun realRemoteLibraryConflictPreservesDeletedPagesAndUnknownFields() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = java.nio.file.Files.createTempDirectory(parent.toPath(), "mojian-navigation-conflict").toFile()
        try {
            val original = NotebookLibrary.sample()
            val source = SharedWorkspaceCodec.encodeLibrary(original, org.json.JSONObject().put("version", 2).put("extension", "keep"))
            root.resolve("library.json").writeText(source.toString())
            val repository = PageContentRepository(root)
            val library = repository.loadLibrary()
            val notebook = library.activeNotebook()
            val page = notebook.allSections().flatMap { it.pages }.first()
            repository.save(PageInsertDocument(notebook.id, page.id))
            val asset = repository.assetDirectory(notebook.id, page.id).resolve("original.bin").apply { writeText("original") }
            val mutation = NavigationMutation()
            mutation.afterCommit { repository.deleteNotebookData(notebook.id) }
            library.deleteNotebook(notebook.id)
            val remote = org.json.JSONObject(source.toString()).put("remote", true)
            root.resolve("library.json").writeText(remote.toString())
            assertThrows(IllegalStateException::class.java) { mutation.commit { repository.saveLibrary(library) } }
            assertTrue(repository.pageFile(notebook.id, page.id).isFile)
            assertEquals("original", asset.readText())
            assertEquals("keep", org.json.JSONObject(root.resolve("library.json").readText()).getString("extension"))
            assertTrue(org.json.JSONObject(root.resolve("library.json").readText()).getBoolean("remote"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun remoteReintroductionAfterCommitPreventsCleanup() {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = java.nio.file.Files.createTempDirectory(parent.toPath(), "mojian-navigation-reintroduced").toFile()
        try {
            val source = SharedWorkspaceCodec.encodeLibrary(NotebookLibrary.sample(), org.json.JSONObject().put("version", 2))
            root.resolve("library.json").writeText(source.toString())
            val repository = PageContentRepository(root)
            val library = repository.loadLibrary()
            val notebook = library.activeNotebook()
            val page = notebook.allSections().flatMap { it.pages }.first()
            repository.save(PageInsertDocument(notebook.id, page.id))
            library.deleteNotebook(notebook.id)
            repository.saveLibrary(library)
            root.resolve("library.json").writeText(source.toString())
            repository.deletePageData(notebook.id, page.id)
            repository.deleteNotebookData(notebook.id)
            assertTrue(repository.pageFile(notebook.id, page.id).isFile)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun refusedDirectoryCommitNeverDeletesOriginalData() {
        val events = mutableListOf<String>()
        val mutation = NavigationMutation()
        mutation.afterCommit { events += "delete" }
        assertThrows(IllegalStateException::class.java) {
            mutation.commit { events += "publish"; error("remote directory changed") }
        }
        assertEquals(listOf("publish"), events)
    }

    @Test
    fun deletionRunsOnlyAfterDirectoryIsDurable() {
        val events = mutableListOf<String>()
        val mutation = NavigationMutation()
        mutation.afterCommit { events += "page" }
        mutation.afterCommit { events += "assets" }
        mutation.commit { events += "directory" }
        assertEquals(listOf("directory", "page", "assets"), events)
    }
}
