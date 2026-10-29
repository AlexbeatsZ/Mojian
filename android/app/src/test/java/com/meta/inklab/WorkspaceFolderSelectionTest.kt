package com.meta.inklab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFolderSelectionTest {
    @Test fun localTreeSupportsPrimaryAndRemovableStorageWithoutTraversal() {
        val primary = File("/storage/emulated/0")
        val removable = File("/storage/ABCD-1234")
        val volumes = mapOf("primary" to primary, "ABCD-1234" to removable)
        assertEquals(File(primary, "Documents/我的笔记").canonicalFile, WorkspaceFolderSelection.resolveLocalTree("primary:Documents/我的笔记", volumes))
        assertEquals(removable.canonicalFile, WorkspaceFolderSelection.resolveLocalTree("ABCD-1234:", volumes))
        listOf("primary:../other", "primary:/other", "primary:Documents/../../other", "primary:Documents\\other", "cloud:Notes", "invalid").forEach {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceFolderSelection.resolveLocalTree(it, volumes) }
        }
    }

    @Test fun newFolderCreatesCanonicalLibraryAndNeverOverwritesExistingContent() {
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "mojian-folder-selection").toFile()
        try {
            WorkspaceFolderSelection.createEmptyLibrary(root)
            val library = PageContentRepository(root).loadLibrary()
            assertEquals(1, library.notebooks.size)
            assertTrue(library.activeNotebook().sections.isEmpty())
            val original = File(root, "library.json").readText()
            assertThrows(IllegalArgumentException::class.java) { WorkspaceFolderSelection.createEmptyLibrary(root) }
            assertEquals(original, File(root, "library.json").readText())
            File(root, "library.json").delete()
            File(root, "unrelated.txt").writeText("keep")
            assertThrows(IllegalArgumentException::class.java) { WorkspaceFolderSelection.createEmptyLibrary(root) }
            assertEquals("keep", File(root, "unrelated.txt").readText())
        } finally { root.deleteRecursively() }
    }
}
