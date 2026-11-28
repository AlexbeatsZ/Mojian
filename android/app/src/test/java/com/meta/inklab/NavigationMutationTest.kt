package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class NavigationMutationTest {
    private fun isolated(name: String, action: (java.io.File, PageContentRepository) -> Unit) {
        val parent = java.io.File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = java.nio.file.Files.createTempDirectory(parent.toPath(), name).toFile()
        try {
            FolderLibrary(root).apply(SharedWorkspaceCodec.encodeLibrary(NotebookLibrary.sample(), org.json.JSONObject().put("version", 2)))
            action(root, PageContentRepository(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun remoteFolderChangeRefusesDeletionAndKeepsPagesAndAssets() = isolated("mojian-navigation-conflict") { root, repository ->
        val library = repository.loadLibrary()
        val notebook = library.activeNotebook()
        val page = notebook.allSections().flatMap { it.pages }.first()
        repository.save(repository.load(notebook.id, page.id))
        val asset = repository.assetDirectory(notebook.id, page.id).resolve("original.bin").apply { writeText("original") }
        val pageFile = repository.pageFile(notebook.id, page.id)
        val mutation = NavigationMutation()
        mutation.afterCommit { repository.deleteNotebookData(notebook.id) }
        library.deleteNotebook(notebook.id)
        val remote = java.io.File(repository.notebookDirectory(notebook.id), "另一台设备的分区").apply { assertTrue(mkdirs()) }
        assertThrows(IllegalStateException::class.java) { mutation.commit { repository.saveLibrary(library) } }
        assertTrue(pageFile.isFile)
        assertEquals("original", asset.readText())
        assertTrue(remote.isDirectory)
    }

    @Test
    fun remoteReintroductionAfterCommitPreventsCleanup() = isolated("mojian-navigation-reintroduced") { root, repository ->
        val library = repository.loadLibrary()
        val notebook = library.activeNotebook()
        val page = notebook.allSections().flatMap { it.pages }.first()
        repository.save(repository.load(notebook.id, page.id))
        val directory = repository.notebookDirectory(notebook.id)
        library.deleteNotebook(notebook.id)
        assertTrue(repository.saveLibrary(library))
        assertFalse(directory.exists())
        val asset = java.io.File(root, ".mojian/assets/${page.id}/kept.bin").apply { parentFile!!.mkdirs(); writeText("kept") }
        java.io.File(directory, "另一台设备的分区").mkdirs()
        assertThrows(IllegalStateException::class.java) { repository.deletePageData(notebook.id, page.id) }
        assertThrows(IllegalStateException::class.java) { repository.deleteNotebookData(notebook.id) }
        assertTrue(asset.isFile)
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
