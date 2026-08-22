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

    fun deletePageData(notebookId: String, pageId: String) {
        pageFile(notebookId, pageId).delete()
        assetDirectory(notebookId, pageId).deleteRecursively()
    }

    companion object {
        fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
