package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

/** Dependency components of the current synchronization plan. */
internal class NativeSyncDependencies private constructor() {
    private val parents = mutableMapOf<String, String>()
    private var unknown = false
    val known: Boolean get() = !unknown

    fun group(path: String): String = if (unknown) LIBRARY else root(pageKey(path))

    private fun root(key: String): String {
        var group = key
        while (true) group = parents[group] ?: return group
    }

    private fun connect(left: String, right: String) {
        val first = root(left)
        val second = root(right)
        if (first != second) parents[second] = first
    }

    companion object {
        private const val LIBRARY = "library.json"

        fun fromIndexes(local: JSONObject?, remote: JSONObject?, indexChanges: Boolean, structuralPaths: Iterable<String>): NativeSyncDependencies {
            val dependencies = NativeSyncDependencies()
            try {
                val ours = readIndex(requireNotNull(local))
                val theirs = readIndex(requireNotNull(remote))
                for (index in listOf(ours, theirs)) {
                    index.forEach { (page, reference) ->
                        reference.sourceOwner?.let { dependencies.connect(page, it) }
                    }
                }
                if (indexChanges) {
                    for (page in ours.keys + theirs.keys) {
                        if (ours[page] != theirs[page]) dependencies.connect(LIBRARY, page)
                    }
                    for (path in structuralPaths) dependencies.connect(LIBRARY, pageKey(path))
                }
            } catch (_: org.json.JSONException) {
                dependencies.unknown = true
            } catch (_: IllegalArgumentException) {
                dependencies.unknown = true
            }
            return dependencies
        }

        private fun pageKey(path: String): String {
            if (path == LIBRARY) return LIBRARY
            val parts = path.split('/')
            return "${parts[1]}/" + if (parts[2] == "pages") parts[3].removeSuffix(".json") else parts[3]
        }

        private data class Reference(val sourceOwner: String?, val sourceAsset: String?, val renderedAsset: String?)

        private fun readIndex(index: JSONObject): Map<String, Reference> {
            fun objects(source: JSONObject, key: String, required: Boolean = false): Sequence<JSONObject> {
                if (!source.has(key) && !required) return emptySequence()
                val array: JSONArray = source.getJSONArray(key)
                return (0 until array.length()).asSequence().map(array::getJSONObject)
            }
            val pages = mutableMapOf<String, Reference>()
            fun visit(notebook: String, sections: Sequence<JSONObject>) {
                for (section in sections) {
                    for (page in objects(section, "pages")) {
                        val pageId = page.getString("id")
                        val path = "notebooks/$notebook/pages/$pageId.json"
                        require(NativeSyncFiles.validPath(path)) { "笔记目录页路径无效" }
                        val pdf = if (page.isNull("pdfPage")) null else page.getJSONObject("pdfPage")
                        val owner = pdf?.optString("sourceOwnerPageId")?.ifEmpty { null }
                        val source = pdf?.optString("sourceAssetName")?.ifEmpty { null }
                            ?: page.optString("pdfSourceAssetName").ifEmpty { null }
                        val rendered = pdf?.optString("renderedAssetName")?.ifEmpty { null }
                        require(owner == null || NativeSyncFiles.validPath("notebooks/$notebook/pages/$owner.json")) { "PDF 素材所有者无效" }
                        require(pages.put(pageKey(path), Reference(owner?.let { "$notebook/$it" }, source, rendered)) == null) { "笔记目录包含重复页标识" }
                    }
                    visit(notebook, objects(section, "children"))
                }
            }
            for (notebook in objects(index, "notebooks", required = true)) visit(notebook.getString("id"), objects(notebook, "sections", required = true))
            return pages
        }
    }
}
