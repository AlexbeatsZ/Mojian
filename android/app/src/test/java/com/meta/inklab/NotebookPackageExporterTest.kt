package com.meta.inklab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookPackageExporterTest {
    @Test
    fun packageContainsManifestPageJsonOriginalAssetsAndLayerSnapshots() {
        val root = Files.createTempDirectory("mojian-package").toFile()
        try {
            val repository = PageContentRepository(root)
            val notebook = NotebookWorkspace.sample()
            val page = notebook.selectedPage()!!
            val document = PageInsertDocument(notebook.id, page.id)
            document.objects += InsertObject.Attachment("a", 0f, 0f, 320f, 60f, "source.pdf", "source.pdf", "application/pdf", 4)
            repository.save(document)
            repository.assetDirectory(notebook.id, page.id).resolve("source.pdf").writeBytes(byteArrayOf(1, 2, 3, 4))
            val output = ByteArrayOutputStream()

            NotebookPackageExporter(repository).write(output, notebook, page.id, mapOf("页面背景" to byteArrayOf(9, 8, 7)))

            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertTrue("manifest.json" in entries)
            assertTrue("pages/${page.id}.json" in entries)
            assertTrue("assets/${page.id}/source.pdf" in entries)
            assertTrue("layers/${page.id}/页面背景.png" in entries)
            assertEquals(listOf<Byte>(1, 2, 3, 4), entries.getValue("assets/${page.id}/source.pdf").toList())
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            val firstPage = manifest.getJSONArray("sections").getJSONObject(0).getJSONArray("pages").getJSONObject(0)
            assertEquals("PAGE", firstPage.getString("kind"))
            assertTrue(firstPage.has("viewSettings"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sectionExportContainsOnlyTheSelectedSectionTree() {
        val root = Files.createTempDirectory("mojian-section-package").toFile()
        try {
            val repository = PageContentRepository(root)
            val notebook = NotebookWorkspace.sample()
            val section = notebook.section("section-notein")!!
            val output = ByteArrayOutputStream()

            NotebookPackageExporter(repository).writeSection(output, notebook, section, null, emptyMap())

            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals("mojian-section", manifest.getString("format"))
            assertEquals(listOf("section-notein"), List(manifest.getJSONArray("sections").length()) { index -> manifest.getJSONArray("sections").getJSONObject(index).getString("id") })
        } finally {
            root.deleteRecursively()
        }
    }
}
