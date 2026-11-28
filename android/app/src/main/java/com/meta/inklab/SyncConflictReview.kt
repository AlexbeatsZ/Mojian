package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

/** VS Code source-control letters: what taking the server's version does here (native-sync.md "Conflict review"). */
internal enum class ConflictMark(val letter: String, val color: Int) {
    MODIFY("M", 0xFFE2C08D.toInt()),
    ADD("A", 0xFF73C991.toInt()),
    DELETE("D", 0xFFF14C4C.toInt());

    companion object {
        fun of(action: String) = when (action) { "add" -> ADD; "delete" -> DELETE; else -> MODIFY }
    }
}

/** A conflicted item as navigation shows it; [pageId] is set for page files and page assets, [notebookId] while the page is listed. */
internal data class ConflictEntry(
    val item: NativeSyncConflictItem,
    val label: String,
    val notebookId: String?,
    val pageId: String?,
) {
    val path get() = item.path
    val mark get() = ConflictMark.of(item.action)
    val isJson get() = SyncConflictReview.mergeable(item.path)
    /** "两边都要" needs both JSON versions (the engine refuses it otherwise). */
    val canKeepBoth get() = isJson && item.local != null && item.remote != null
    val isPage get() = pageId != null && SyncConflictReview.isPageFile(item.path)
}

/** Strokes the canvas preview marks: only on the server (green), only here (red), and this device's version of strokes both changed (yellow). */
internal data class StrokeDiff(val remote: List<JSONObject>, val local: List<JSONObject>, val changed: List<JSONObject>)

internal object SyncConflictReview {
    fun isPageFile(path: String) = path.endsWith(FolderNames.PAGE_EXTENSION)
    fun mergeable(path: String) = isPageFile(path) || path.endsWith(".json")

    /**
     * The page a conflicted item belongs to: `.mojian/assets/<page>/…` by path, a page file by the id in either
     * preserved copy's head (page files are named by title, not id).
     */
    fun pageIdOf(item: NativeSyncConflictItem): String? {
        val parts = item.path.split('/')
        if (parts.size == 4 && parts[0] == FolderNames.HIDDEN_ROOT && parts[1] == "assets") return parts[2]
        if (!isPageFile(item.path)) return null
        return listOfNotNull(item.local, item.remote).firstNotNullOfOrNull { file ->
            runCatching { PageFileFormat.readHead(file).id }.getOrNull()
        }
    }

    /** A page is labelled by its file name, which is its title; other items by their name. */
    fun label(path: String) = path.substringAfterLast('/').let { if (isPageFile(path)) it.removeSuffix(FolderNames.PAGE_EXTENSION) else it }

    fun entries(items: List<NativeSyncConflictItem>, notebookOf: (String) -> String?): List<ConflictEntry> =
        items.map { item ->
            val pageId = pageIdOf(item)
            ConflictEntry(item, label(item.path), pageId?.let(notebookOf), pageId)
        }.sortedWith(compareBy({ !it.isPage }, { it.label }, { it.path }))

    /** "本机 +1 · 服务器 +1 · 改动 1"; zero counts are omitted. */
    fun summaryText(summary: NativeSyncMerge.Summary?): String {
        summary ?: return ""
        return listOfNotNull(
            summary.localOnly.takeIf { it > 0 }?.let { "本机 +$it" },
            summary.remoteOnly.takeIf { it > 0 }?.let { "服务器 +$it" },
            summary.changed.takeIf { it > 0 }?.let { "改动 $it" },
        ).joinToString(" · ")
    }

    fun strokeDiff(local: JSONObject?, remote: JSONObject?): StrokeDiff {
        val ours = byId(local?.optJSONArray("strokes"))
        val theirs = byId(remote?.optJSONArray("strokes"))
        return StrokeDiff(
            remote = theirs.filterKeys { it !in ours }.values.toList(),
            local = ours.filterKeys { it !in theirs }.values.toList(),
            changed = ours.filter { (id, stroke) -> theirs[id]?.let { !same(it, stroke) } == true }.values.toList(),
        )
    }

    private fun JSONObject.keyNames(): List<String> = keys().asSequence().toList()

    private fun identified(array: JSONArray) = array.length() > 0 && (0 until array.length()).all { (array.opt(it) as? JSONObject)?.optString("id")?.isNotEmpty() == true }

    private fun byId(array: JSONArray?): LinkedHashMap<String, JSONObject> {
        val result = LinkedHashMap<String, JSONObject>()
        array ?: return result
        for (index in 0 until array.length()) (array.opt(index) as? JSONObject)?.let { item -> item.optString("id").takeIf(String::isNotEmpty)?.let { result[it] = item } }
        return result
    }

    /** Structural equality; numbers compare by value so 1 and 1.0 written by different clients match. */
    fun same(left: Any?, right: Any?): Boolean = when {
        left === right -> true
        left == null || right == null || left == JSONObject.NULL || right == JSONObject.NULL -> (left == null || left == JSONObject.NULL) && (right == null || right == JSONObject.NULL)
        left is JSONObject && right is JSONObject -> left.length() == right.length() && left.keyNames().all { right.has(it) && same(left.opt(it), right.opt(it)) }
        left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { same(left.opt(it), right.opt(it)) }
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }
}
