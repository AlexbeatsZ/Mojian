package com.meta.inklab

/**
 * Dependency components of a folder-library v3 plan: a page file, its assets under `.mojian/assets/<page-id>` and the
 * page that owns its PDF source form one group; folders and descriptors are independent (Syncthing semantics), so one
 * held page never holds back its whole section. Same rules as the Windows NativeSyncDependencies.
 */
internal class NativeSyncDependencies private constructor() {
    private val parents = mutableMapOf<String, String>()
    private val pageOfPath = mutableMapOf<String, String>()

    fun group(path: String): String = root(key(path))

    private fun key(path: String): String {
        pageOfPath[path]?.let { return it }
        val parts = path.split('/')
        if (parts.size == 4 && parts[0] == ".mojian" && parts[1] == "assets") return "page:${parts[2]}"
        return "path:$path"
    }

    private fun root(group: String): String {
        var current = group
        while (true) current = parents[current] ?: return current
    }

    private fun connect(left: String, right: String) {
        val a = root(left); val b = root(right)
        if (a != b) parents[b] = a
    }

    companion object {
        /** [pages]: page file path to its page id and PDF owner page id, read from local heads or downloaded blobs. */
        fun fromPages(pages: List<Triple<String, String?, String?>>): NativeSyncDependencies = NativeSyncDependencies().apply {
            pages.forEach { (path, pageId, owner) ->
                if (pageId == null) return@forEach
                pageOfPath[path] = "page:$pageId"
                connect("page:$pageId", "path:$path")
                if (owner != null) connect("page:$pageId", "page:$owner")
            }
        }
    }
}
