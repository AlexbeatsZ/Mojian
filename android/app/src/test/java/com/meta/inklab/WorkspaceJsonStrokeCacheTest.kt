package com.meta.inklab

import java.io.StringWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceJsonStrokeCacheTest {
    private fun stroke(id: String, points: Int) = JSONObject().put("id", id).put("vectorPath", "F1 M1,2 L3.25,4 Z")
        .put("points", JSONArray().apply { repeat(points) { put(JSONObject().put("x", it * 1.5).put("y", -it).put("pressure", .5)) } })

    private fun page(strokes: List<JSONObject>) = JSONObject().put("version", 2).put("pageId", "p")
        .put("strokes", JSONArray(strokes)).put("textItems", JSONArray().put(JSONObject().put("strokes", "not a stroke array")))

    private fun text(value: JSONObject, cache: StrokeTextCache?) = StringWriter().also { WorkspaceJson.write(value, it, cache) }.toString()

    @Test
    fun cachedPageWritesAreByteIdenticalAndReuseUnchangedStrokes() {
        val cache = StrokeTextCache()
        val a = stroke("a", 50); val b = stroke("b", 20); val c = stroke("c", 5)
        val first = page(listOf(a, b))
        assertEquals(text(first, null), text(first, cache))
        assertEquals(0, cache.hits); assertEquals(2, cache.misses)

        val second = page(listOf(a, b, c)).put("revision", 2)
        assertEquals(text(second, null), text(second, cache))
        assertEquals(2, cache.hits); assertEquals(1, cache.misses)

        // Erasing b drops its text; the uncached output matches and remaining strokes still hit.
        val third = page(listOf(c, a))
        assertEquals(text(third, null), text(third, cache))
        assertEquals(2, cache.hits); assertEquals(0, cache.misses)
        assertEquals(JSONObject(text(third, null)).toString(), third.toString())
    }

    @Test
    fun budgetStopsCachingWithoutChangingOutput() {
        val cache = StrokeTextCache(limitChars = 10)
        val value = page(listOf(stroke("a", 30), stroke("b", 30)))
        assertEquals(text(value, null), text(value, cache))
        assertEquals(text(value, null), text(value, cache))
        assertEquals(0, cache.hits)
    }

    @Test
    fun fingerprintMatchesForEqualContentAcrossDistinctStrokeObjects() {
        val one = page(listOf(stroke("a", 10)))
        val two = page(listOf(stroke("a", 10)))
        assertEquals(WorkspaceJson.fingerprint(one), WorkspaceJson.fingerprint(two))
        assertEquals(false, WorkspaceJson.fingerprint(one) == WorkspaceJson.fingerprint(page(listOf(stroke("a", 11)))))
    }
}
