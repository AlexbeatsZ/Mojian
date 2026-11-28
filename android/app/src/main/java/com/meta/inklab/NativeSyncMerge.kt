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
    private const val STROKES = "strokes"

    sealed interface Result {
        /** The merge equals the remote file apart from volatile fields: just receive it. */
        data object Remote : Result
        data class Merged(val document: JSONObject) : Result
    }

    fun merge(base: JSONObject, local: JSONObject, remote: JSONObject, now: String = java.time.Instant.now().toString()): Result? {
        val merged = try { mergeObject(base, local, remote) } catch (_: Conflict) { return null }
        if (sameIgnoringVolatile(merged, remote)) return Result.Remote
        // A folder-library page keeps its revision and edit time in "content".
        val editable = merged.optJSONObject("content") ?: merged
        if (editable.has("revision")) editable.put("revision", editable.optLong("revision") + 1)
        if (editable.has("updatedAt")) editable.put("updatedAt", now)
        return Result.Merged(merged)
    }

    /**
     * "Keep both" for a reviewed conflict: every id item from either side survives, the same id or key
     * changed on both sides keeps the local value. Order follows the remote array plus local additions.
     */
    fun union(local: JSONObject, remote: JSONObject, now: String = java.time.Instant.now().toString()): JSONObject {
        val merged = unionValue(null, local, remote) as JSONObject
        val editable = merged.optJSONObject("content") ?: merged
        val ours = local.optJSONObject("content") ?: local
        val theirs = remote.optJSONObject("content") ?: remote
        if (editable.has("revision")) editable.put("revision", maxOf(ours.optLong("revision"), theirs.optLong("revision")) + 1)
        if (editable.has("updatedAt")) editable.put("updatedAt", now)
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
        return keys.all { key ->
            val l = left.opt(key); val r = right.opt(key)
            if (key == "content" && l is JSONObject && r is JSONObject) sameIgnoringVolatile(l, r) else equal(value(left, key), value(right, key))
        }
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

    private fun mergeObject(base: JSONObject, local: JSONObject, remote: JSONObject, stroke: Boolean = false): JSONObject {
        val result = JSONObject()
        for (key in linkedSetOf<String>().apply { addAll(remote.keyNames()); addAll(local.keyNames()); addAll(base.keyNames()) }) {
            if (stroke && isSampleField(key)) continue
            val before = value(base, key)
            val mine = value(local, key)
            val theirs = value(remote, key)
            val merged = when {
                stroke && (equalStrokeField(key, mine, theirs) || equalStrokeField(key, mine, before)) -> theirs
                stroke && equalStrokeField(key, theirs, before) -> mine
                else -> mergeValue(key, before, mine, theirs)
            }
            if (merged !== Absent) result.put(key, merged)
        }
        if (stroke) {
            val samples = when {
                samplesEqual(local, remote) || samplesEqual(local, base) -> remote
                samplesEqual(remote, base) -> local
                else -> throw Conflict()
            }
            for (key in samples.keyNames().filter(::isSampleField)) result.put(key, samples.get(key))
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
            val stroke = key == STROKES
            val merged = when {
                before !== Absent && mine === Absent -> if (itemEqual(other, before, stroke)) Absent else throw Conflict()
                before !== Absent && other === Absent -> if (itemEqual(mine, before, stroke)) Absent else throw Conflict()
                stroke && mine is JSONObject && other is JSONObject -> mergeObject(before as? JSONObject ?: JSONObject(), mine, other, stroke = true)
                else -> mergeValue(null, before, mine, other)
            }
            if (merged !== Absent) kept[id] = merged
        }
        return JSONArray().apply { order(ours.keys, theirs.keys, kept.keys).forEach { put(kept.getValue(it)) } }
    }

    private fun isSampleField(key: String) = key == InkPoints.POINTS || key == InkPoints.CODED

    private fun itemEqual(left: Any, right: Any, stroke: Boolean): Boolean {
        if (!stroke || left !is JSONObject || right !is JSONObject) return equal(left, right)
        return (left.keyNames() + right.keyNames()).filterNot(::isSampleField)
            .all { equalStrokeField(it, value(left, it), value(right, it)) } && samplesEqual(left, right)
    }

    private fun equalStrokeField(key: String, left: Any, right: Any): Boolean {
        if (equal(left, right)) return true
        val explicitValue = when {
            left === Absent -> right
            right === Absent -> left
            else -> return false
        }
        return when (key) {
            "stabilityPercent" -> equal(explicitValue, 50)
            "strokeUnitLengthCm" -> equal(explicitValue, 0)
            else -> false
        }
    }

    private fun samplesEqual(left: JSONObject, right: JSONObject): Boolean {
        val leftPlain = value(left, InkPoints.POINTS)
        val rightPlain = value(right, InkPoints.POINTS)
        val leftCoded = value(left, InkPoints.CODED)
        val rightCoded = value(right, InkPoints.CODED)
        if (equal(leftPlain, rightPlain) && equal(leftCoded, rightCoded)) return true
        if (leftCoded is String && rightPlain is JSONArray && leftPlain === Absent && rightCoded === Absent) return samplesEqual(right, left)
        return leftCoded === Absent && rightPlain === Absent && leftPlain is JSONArray
            && rightCoded is String && InkPoints.encode(leftPlain) == rightCoded
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
