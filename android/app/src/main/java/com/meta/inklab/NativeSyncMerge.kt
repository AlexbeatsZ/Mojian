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
        val order = theirs.keys.filterTo(ArrayList()) { it in kept }
        val placed = order.toHashSet()
        var anchor = -1
        for (id in ours.keys) {
            if (id !in kept) continue
            if (id in placed) { anchor = order.indexOf(id); continue }
            order.add(anchor + 1, id); placed += id; anchor += 1
        }
        return JSONArray().apply { order.forEach { put(kept.getValue(it)) } }
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
