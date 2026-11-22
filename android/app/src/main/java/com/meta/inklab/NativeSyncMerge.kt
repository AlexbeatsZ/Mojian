package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Three-way JSON merge for workspace and page files (shared/fixtures/json-merge.json).
 * Arrays whose items all carry a unique string id merge per item; objects merge per key.
 * Returns null when both sides changed the same value differently.
 */
internal fun JSONObject.keyNames(): Set<String> = keys().asSequence().toCollection(LinkedHashSet())

object NativeSyncMerge {
    private object Absent
    private class Conflict : RuntimeException(null, null, false, false)
    private val maximumKeys = setOf("revision", "sheetCount", "infiniteWidth", "infiniteHeight")
    private val latestKeys = setOf("updatedAt")
    private val volatileKeys = setOf("revision", "updatedAt")
    private const val ERASABLE = "strokes"

    sealed interface Result {
        /** The merge equals the remote file apart from volatile fields: just receive it. */
        data object Remote : Result
        data class Merged(val document: JSONObject) : Result
    }

    fun merge(base: JSONObject, local: JSONObject, remote: JSONObject, now: String = java.time.Instant.now().toString()): Result? {
        val merged = try { mergeObject(base, local, remote) } catch (_: Conflict) { return null }
        if (sameIgnoringVolatile(merged, remote)) return Result.Remote
        if (merged.has("revision")) merged.put("revision", merged.optLong("revision") + 1)
        if (merged.has("updatedAt")) merged.put("updatedAt", now)
        return Result.Merged(merged)
    }

    /**
     * "Keep both" for a reviewed conflict: every id item from either side survives, the same id or key
     * changed on both sides keeps the local value. Order follows the remote array plus local additions.
     */
    fun union(local: JSONObject, remote: JSONObject, now: String = java.time.Instant.now().toString()): JSONObject {
        val merged = unionValue(null, local, remote) as JSONObject
        if (merged.has("revision")) merged.put("revision", maxOf(local.optLong("revision"), remote.optLong("revision")) + 1)
        if (merged.has("updatedAt")) merged.put("updatedAt", now)
        return merged
    }

    private fun unionValue(key: String?, local: Any, remote: Any): Any {
        if (local === Absent) return remote
        if (remote === Absent || equal(local, remote)) return local
        if (local is JSONObject && remote is JSONObject) return JSONObject().apply {
            for (name in linkedSetOf<String>().apply { addAll(remote.keyNames()); addAll(local.keyNames()) })
                put(name, unionValue(name, value(local, name), value(remote, name)))
        }
        if (local is JSONArray && remote is JSONArray && identified(local) && identified(remote)) {
            val ours = byId(local); val theirs = byId(remote)
            val kept = HashMap<String, Any>()
            for (id in ours.keys + theirs.keys) kept[id] = unionValue(null, ours[id] ?: Absent, theirs[id] ?: Absent)
            return JSONArray().apply { order(ours.keys, theirs.keys, kept.keys).forEach { put(kept.getValue(it)) } }
        }
        return local
    }

    /** Remote order, each local-only id inserted after its nearest local predecessor. */
    private fun order(local: Collection<String>, remote: Collection<String>, kept: Set<String>): List<String> {
        val order = remote.filterTo(ArrayList()) { it in kept }
        val placed = order.toHashSet()
        var anchor = -1
        for (id in local) {
            if (id !in kept) continue
            if (id in placed) { anchor = order.indexOf(id); continue }
            order.add(anchor + 1, id); placed += id; anchor += 1
        }
        return order
    }

    /** Items (anything with an id, e.g. strokes or pages) present only locally, only remotely, or on both sides but different. */
    data class Summary(val localOnly: Int, val remoteOnly: Int, val changed: Int)

    fun summarize(local: JSONObject, remote: JSONObject): Summary {
        var localOnly = 0; var remoteOnly = 0; var changed = 0
        fun walk(left: Any, right: Any) {
            when {
                left is JSONObject && right is JSONObject -> for (name in left.keyNames() + right.keyNames()) walk(value(left, name), value(right, name))
                left is JSONArray && right is JSONArray && identified(left) && identified(right) -> {
                    val ours = byId(left); val theirs = byId(right)
                    for ((id, item) in ours) {
                        val other = theirs[id]
                        if (other == null) localOnly += count(item)
                        else if (!equal(item, other)) { if (item is JSONObject && other is JSONObject && hasIdentified(item)) walk(item, other) else changed++ }
                    }
                    theirs.keys.filter { it !in ours }.forEach { remoteOnly += count(theirs.getValue(it)) }
                }
                left === Absent && right !== Absent -> remoteOnly += countNested(right)
                right === Absent && left !== Absent -> localOnly += countNested(left)
            }
        }
        walk(local, remote)
        return Summary(localOnly, remoteOnly, changed)
    }

    /** A container (notebook/section) counts the leaves it carries; a leaf counts once. */
    private fun count(item: Any): Int = countNested(item).coerceAtLeast(1)
    private fun countNested(item: Any): Int = when (item) {
        is JSONObject -> item.keyNames().sumOf { countNested(item.get(it)) }
        is JSONArray -> if (identified(item)) (0 until item.length()).sumOf { count(item.get(it)) } else 0
        else -> 0
    }
    private fun hasIdentified(item: JSONObject) = item.keyNames().any { (item.get(it) as? JSONArray)?.let { array -> array.length() > 0 && identified(array) } == true }

    private fun sameIgnoringVolatile(left: JSONObject, right: JSONObject): Boolean {
        val keys = (left.keyNames() + right.keyNames()) - volatileKeys
        return keys.all { equal(value(left, it), value(right, it)) }
    }

    private fun value(source: JSONObject, key: String): Any = if (source.has(key)) source.get(key) else Absent

    private fun mergeValue(key: String?, base: Any, local: Any, remote: Any): Any {
        if (equal(local, remote)) return local
        if (equal(local, base)) return remote
        if (equal(remote, base)) return local
        if (key in maximumKeys && local is Number && remote is Number)
            return if (BigDecimal(local.toString()) >= BigDecimal(remote.toString())) local else remote
        if (key in latestKeys && local is String && remote is String) return maxOf(local, remote)
        if (local is JSONObject && remote is JSONObject && (base is JSONObject || base === Absent))
            return mergeObject(base as? JSONObject ?: JSONObject(), local, remote)
        if (local is JSONArray && remote is JSONArray && identified(local) && identified(remote) &&
            (base === Absent || base is JSONArray && identified(base)))
            return mergeIdentified(key, base as? JSONArray ?: JSONArray(), local, remote)
        throw Conflict()
    }

    private fun mergeObject(base: JSONObject, local: JSONObject, remote: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in linkedSetOf<String>().apply { addAll(remote.keyNames()); addAll(local.keyNames()); addAll(base.keyNames()) }) {
            val merged = mergeValue(key, value(base, key), value(local, key), value(remote, key))
            if (merged !== Absent) result.put(key, merged)
        }
        return result
    }

    private fun identified(array: JSONArray): Boolean {
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val id = (array.opt(index) as? JSONObject)?.opt("id") as? String ?: return false
            if (!ids.add(id)) return false
        }
        return true
    }

    private fun byId(array: JSONArray): LinkedHashMap<String, Any> =
        LinkedHashMap<String, Any>(array.length() * 2).apply {
            for (index in 0 until array.length()) array.getJSONObject(index).let { put(it.getString("id"), it) }
        }

    private fun mergeIdentified(key: String?, base: JSONArray, local: JSONArray, remote: JSONArray): JSONArray {
        val ancestor = byId(base); val ours = byId(local); val theirs = byId(remote)
        val kept = HashMap<String, Any>()
        for (id in ancestor.keys + ours.keys + theirs.keys) {
            if (id in kept) continue
            val before = ancestor[id] ?: Absent; val mine = ours[id] ?: Absent; val other = theirs[id] ?: Absent
            val merged = when {
                before !== Absent && mine === Absent -> if (equal(other, before) || key == ERASABLE) Absent else throw Conflict()
                before !== Absent && other === Absent -> if (equal(mine, before) || key == ERASABLE) Absent else throw Conflict()
                else -> mergeValue(null, before, mine, other)
            }
            if (merged !== Absent) kept[id] = merged
        }
        return JSONArray().apply { order(ours.keys, theirs.keys, kept.keys).forEach { put(kept.getValue(it)) } }
    }

    private fun equal(left: Any?, right: Any?): Boolean {
        if (left === right) return true
        if (left is Number && right is Number) {
            if ((left is Int || left is Long) && (right is Int || right is Long)) return left.toLong() == right.toLong()
            return BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
        }
        if (left is JSONObject && right is JSONObject) {
            if (left.length() != right.length()) return false
            return left.keyNames().all { right.has(it) && equal(left.get(it), right.get(it)) }
        }
        if (left is JSONArray && right is JSONArray) {
            if (left.length() != right.length()) return false
            return (0 until left.length()).all { equal(left.get(it), right.get(it)) }
        }
        return left == right
    }
}
