package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSyncMergeTest {
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keyNames().sorted().joinToString(",", "{", "}") { "\"$it\":${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test fun sharedMergeFixtures() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/json-merge.json")).bufferedReader().use { it.readText() }
        val cases = JSONArray(text)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val result = NativeSyncMerge.merge(case.getJSONObject("base"), case.getJSONObject("local"), case.getJSONObject("remote"))
            when (val expected = case.get("result")) {
                JSONObject.NULL -> assertNull(name, result)
                "remote" -> assertEquals(name, NativeSyncMerge.Result.Remote, result)
                else -> assertEquals(name, canonical(expected), canonical((result as NativeSyncMerge.Result.Merged).document))
            }
        }
    }

    @Test fun mergedPagesGetANewRevisionAndTimestamp() {
        val base = JSONObject("""{"revision":3,"updatedAt":"a","strokes":[]}""")
        val local = JSONObject("""{"revision":4,"updatedAt":"b","strokes":[{"id":"l"}]}""")
        val remote = JSONObject("""{"revision":7,"updatedAt":"c","strokes":[{"id":"r"}]}""")
        val merged = (NativeSyncMerge.merge(base, local, remote, now = "now") as NativeSyncMerge.Result.Merged).document
        assertEquals(8L, merged.getLong("revision")); assertEquals("now", merged.getString("updatedAt"))
        assertEquals(listOf("l", "r"), (0 until 2).map { merged.getJSONArray("strokes").getJSONObject(it).getString("id") })
    }
}
