package com.meta.inklab

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class NotebookPackageExporter(private val repository: PageContentRepository) {
    fun write(
        output: OutputStream,
        notebook: NotebookWorkspace,
        currentPageId: String?,
        currentLayers: Map<String, ByteArray>,
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            putBytes(zip, "manifest.json", manifest(notebook, currentPageId).toString(2).toByteArray(Charsets.UTF_8))
            visitPages(notebook.sections) { page ->
                val pageFile = repository.pageFile(notebook.id, page.id)
                if (pageFile.isFile) putFile(zip, "pages/${safe(page.id)}.json", pageFile)
                val assetDirectory = repository.assetDirectory(notebook.id, page.id)
                assetDirectory.listFiles()?.filter(File::isFile)?.sortedBy(File::getName)?.forEach { asset ->
                    putFile(zip, "assets/${safe(page.id)}/${safe(asset.name)}", asset)
                }
            }
            if (currentPageId != null) {
                currentLayers.forEach { (name, bytes) ->
                    putBytes(zip, "layers/${safe(currentPageId)}/${safeLabel(name)}.png", bytes)
                }
            }
        }
    }

    private fun manifest(notebook: NotebookWorkspace, currentPageId: String?): JSONObject =
        JSONObject().apply {
            put("format", "mojian-notebook")
            put("version", 1)
            put("notebookId", notebook.id)
            put("title", notebook.title)
            put("currentPageId", currentPageId ?: JSONObject.NULL)
            put("pdfLayerOrder", JSONArray(listOf("页面背景", "插入内容", "墨迹与文字")))
            put("sections", sectionsJson(notebook.sections))
        }

    private fun sectionsJson(sections: List<SectionNode>): JSONArray =
        JSONArray().apply {
            sections.forEach { section ->
                put(
                    JSONObject().apply {
                        put("id", section.id)
                        put("title", section.title)
                        put(
                            "pages",
                            JSONArray().apply {
                                section.pages.forEach { page ->
                                    put(
                                        JSONObject().apply {
                                            put("id", page.id)
                                            put("title", page.title)
                                            put("kind", if (page.pdfPage == null) "PAGE" else "PDF")
                                            put("viewSettings", page.viewSettings.toJson())
                                            page.pdfPage?.let { pdf ->
                                                put(
                                                    "pdf",
                                                    JSONObject()
                                                        .put("sourceOwnerPageId", pdf.sourceOwnerPageId)
                                                        .put("sourceAssetName", pdf.sourceAssetName)
                                                        .put("sourceDocumentName", pdf.sourceDocumentName)
                                                        .put("pageNumber", pdf.pageNumber)
                                                        .put("pageCount", pdf.pageCount)
                                                        .put("widthPoints", pdf.widthPoints.toDouble())
                                                        .put("heightPoints", pdf.heightPoints.toDouble()),
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put("children", sectionsJson(section.children))
                    },
                )
            }
        }

    private fun visitPages(sections: List<SectionNode>, action: (NotePage) -> Unit) {
        sections.forEach { section ->
            section.pages.forEach(action)
            visitPages(section.children, action)
        }
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun safe(value: String): String = PageContentRepository.safeId(value)

    private fun safeLabel(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace("..", "_").ifBlank { "layer" }
}
