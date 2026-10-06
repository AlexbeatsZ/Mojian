package com.meta.inklab

import java.io.File
import org.json.JSONObject

class PageContentRepository(private val root: File) {
    fun load(notebookId: String, pageId: String): PageInsertDocument {
        val file = pageFile(notebookId, pageId)
        if (!file.isFile) return PageInsertDocument(notebookId, pageId)
        return runCatching { PageInsertDocument.fromJson(JSONObject(file.readText(Charsets.UTF_8))) }
            .getOrElse { PageInsertDocument(notebookId, pageId) }
    }

    fun save(document: PageInsertDocument) {
        val file = pageFile(document.notebookId, document.pageId)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(document.toJson().toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    fun pageFile(notebookId: String, pageId: String): File =
        File(File(File(root, "notebooks"), safeId(notebookId)), "pages/${safeId(pageId)}.json")

    fun assetDirectory(notebookId: String, pageId: String): File =
        File(File(File(root, "notebooks"), safeId(notebookId)), "assets/${safeId(pageId)}").apply { mkdirs() }

    fun notebookDirectory(notebookId: String): File =
        File(File(root, "notebooks"), safeId(notebookId))

    fun deleteNotebookData(notebookId: String) {
        val directory = notebookDirectory(notebookId).canonicalFile
        require(directory.parentFile == File(root, "notebooks").canonicalFile) {
            "Notebook directory must be inside the workspace"
        }
        check(!directory.exists() || directory.deleteRecursively()) { "Unable to delete notebook data" }
    }

    fun deletePageData(notebookId: String, pageId: String) {
        pageFile(notebookId, pageId).delete()
        assetDirectory(notebookId, pageId).deleteRecursively()
    }

    fun copyPageData(
        sourceNotebookId: String,
        sourcePageId: String,
        targetNotebookId: String,
        targetPageId: String,
        extraAssets: List<File> = emptyList(),
    ) {
        val sourceDocument = load(sourceNotebookId, sourcePageId)
        if (sourceDocument.objects.isNotEmpty() || pageFile(sourceNotebookId, sourcePageId).isFile) {
            save(
                PageInsertDocument(
                    notebookId = targetNotebookId,
                    pageId = targetPageId,
                    objects = sourceDocument.objects.mapTo(mutableListOf()) { InsertObject.fromJson(it.toJson()) },
                ),
            )
        }
        val destination = assetDirectory(targetNotebookId, targetPageId)
        val sourceDirectory = assetDirectory(sourceNotebookId, sourcePageId)
        sourceDirectory.listFiles()?.filter(File::isFile)?.forEach { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
        extraAssets.filter(File::isFile).forEach { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
    }

    companion object {
        fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
