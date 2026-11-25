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

/** A conflicted file as navigation shows it; [notebookId]/[pageId] are set for page JSON and page assets. */
internal data class ConflictEntry(
    val item: NativeSyncConflictItem,
    val label: String,
    val notebookId: String?,
    val pageId: String?,
) {
    val path get() = item.path
    val mark get() = ConflictMark.of(item.action)
    val isJson get() = item.path.endsWith(".json")
    /** "两边都要" needs both JSON versions (the engine refuses it otherwise). */
    val canKeepBoth get() = isJson && item.local != null && item.remote != null
    val isPage get() = pageId != null && item.path.endsWith(".json") && item.path.contains("/pages/")
}

/** One library change between the two sides of a library.json conflict. */
internal data class LibraryChange(val side: Side, val label: String) {
    enum class Side(val symbol: String) { LOCAL("本机："), REMOTE("服务器："), CHANGED("改动：") }
}

/** Strokes the canvas preview marks: only on the server (green), only here (red), and this device's version of strokes both changed (yellow). */
internal data class StrokeDiff(val remote: List<JSONObject>, val local: List<JSONObject>, val changed: List<JSONObject>)

internal object SyncConflictReview {
    const val LIBRARY = "library.json"

    /** `notebooks/<nb>/pages/<page>.json` and `notebooks/<nb>/assets/<page>/...` belong to a page. */
    fun pageOf(path: String): Pair<String, String>? {
        val parts = path.split('/')
        if (parts.size < 4 || parts[0] != "notebooks") return null
        return when {
            parts.size == 4 && parts[2] == "pages" && parts[3].endsWith(".json") -> parts[1] to parts[3].removeSuffix(".json")
            parts.size >= 5 && parts[2] == "assets" -> parts[1] to parts[3]
            else -> null
        }
    }

    fun entries(items: List<NativeSyncConflictItem>, pageTitle: (String, String) -> String?): List<ConflictEntry> =
        items.map { item ->
            val page = pageOf(item.path)
            val label = when {
                item.path == LIBRARY -> "笔记目录"
                page != null && item.path.contains("/pages/") -> pageTitle(page.first, page.second)?.takeIf(String::isNotBlank) ?: page.second
                else -> item.path.substringAfterLast('/')
            }
            ConflictEntry(item, label, page?.first, page?.second)
        }.sortedWith(compareBy({ it.path != LIBRARY }, { it.label }))

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

    /** Pages/sections only on one side or changed on both, labeled by their title trail. */
    fun libraryChanges(local: JSONObject, remote: JSONObject): List<LibraryChange> {
        val changes = mutableListOf<LibraryChange>()
        fun title(item: JSONObject) = item.optString("title").ifBlank { item.optString("id") }
        fun walk(left: JSONObject, right: JSONObject, trail: List<String>) {
            for (key in (left.keyNames() + right.keyNames()).distinct()) {
                val a = left.opt(key) as? JSONArray ?: continue
                val b = right.opt(key) as? JSONArray ?: continue
                if (!(a.length() == 0 || identified(a)) || !(b.length() == 0 || identified(b)) || a.length() + b.length() == 0) continue
                val ours = byId(a); val theirs = byId(b)
                for ((id, item) in ours) {
                    val other = theirs[id]
                    when {
                        other == null -> changes += LibraryChange(LibraryChange.Side.LOCAL, (trail + title(item)).joinToString(" / "))
                        same(item, other) -> Unit
                        hasIdentifiedChildren(item) || hasIdentifiedChildren(other) -> {
                            if (!sameIgnoringArrays(item, other)) changes += LibraryChange(LibraryChange.Side.CHANGED, (trail + title(item)).joinToString(" / "))
                            walk(item, other, trail + title(item))
                        }
                        else -> changes += LibraryChange(LibraryChange.Side.CHANGED, (trail + title(item)).joinToString(" / "))
                    }
                }
                theirs.filterKeys { it !in ours }.values.forEach { changes += LibraryChange(LibraryChange.Side.REMOTE, (trail + title(it)).joinToString(" / ")) }
            }
        }
        walk(local, remote, emptyList())
        return changes
    }

    private fun JSONObject.keyNames(): List<String> = keys().asSequence().toList()

    private fun identified(array: JSONArray) = array.length() > 0 && (0 until array.length()).all { (array.opt(it) as? JSONObject)?.optString("id")?.isNotEmpty() == true }

    private fun hasIdentifiedChildren(item: JSONObject) = item.keyNames().any { (item.opt(it) as? JSONArray)?.let(::identified) == true }

    private fun sameIgnoringArrays(left: JSONObject, right: JSONObject): Boolean {
        val keys = (left.keyNames() + right.keyNames()).distinct().filter { left.opt(it) !is JSONArray && right.opt(it) !is JSONArray }
        return keys.all { same(left.opt(it), right.opt(it)) }
    }

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
