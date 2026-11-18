package com.meta.inklab

import java.io.Reader
import java.io.StringReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingJsonTest {
    /** Serves tiny chunks so every token, escape and \u sequence crosses read boundaries. */
    private class Trickle(private val text: String, private val chunk: Int) : Reader() {
        private var position = 0
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (position >= text.length) return -1
            val count = minOf(chunk, length, text.length - position)
            text.toCharArray(position, position + count).copyInto(buffer, offset)
            position += count
            return count
        }
        override fun close() = Unit
    }

    private val sample = """
        { "version": 2, "pageId":"page-a", "revision": 13166, "big": 12345678901234,
          "negative": -0.5, "exp": 1.5E-7, "flag": true, "off": false, "nothing": null,
          "text": "墨笺 \"quoted\" \\ \/ \b\f\n\r\t 中文 😀",
          "strokes": [ { "id": "s1", "widthMm": 0.17082438, "points": [ {"x": 1, "y": 2.25, "pressure": 0.5} ],
                         "vectorPath": "F1M266.6,584.46c-.04,.05 -.08,.09 -.13,.09z", "vectorTransform": [1,0,0,1,0,0] } ],
          "empty": {}, "none": [] }
    """.trimIndent()

    @Test
    fun matchesJsonObjectFromStringIncludingNumericTypes() {
        val expected = JSONObject(sample)
        for (chunk in listOf(1, 2, 3, 7, 64 * 1024)) {
            val actual = StreamingJson.readObject(Trickle(sample, chunk))
            assertEquals(expected.toString(), actual.toString())
            assertEquals(WorkspaceJson.fingerprint(expected), WorkspaceJson.fingerprint(actual))
            assertEquals(expected.get("revision").javaClass, actual.get("revision").javaClass)
            assertEquals(expected.get("big").javaClass, actual.get("big").javaClass)
            assertEquals(expected.get("negative").javaClass, actual.get("negative").javaClass)
            assertEquals(JSONObject.NULL, actual.get("nothing"))
        }
    }

    @Test
    fun readsStringsLongerThanTheBuffer() {
        val outline = "F1" + (0 until 40_000).joinToString("") { "M$it.5,${it % 97}.25c.1,.2 .3,.4 .5,.6z" }
        val source = JSONObject().put("strokes", org.json.JSONArray().put(JSONObject().put("vectorPath", outline))).toString()
        assertTrue(outline.length > 1_000_000)
        val actual = StreamingJson.readObject(StringReader(source))
        assertEquals(outline, actual.getJSONArray("strokes").getJSONObject(0).getString("vectorPath"))
    }

    @Test
    fun rejectsTruncatedOrTrailingContent() {
        assertThrows(org.json.JSONException::class.java) { StreamingJson.readObject(StringReader("""{"a": [1, 2""")) }
        assertThrows(org.json.JSONException::class.java) { StreamingJson.readObject(StringReader("""{"a": "x""")) }
        assertThrows(org.json.JSONException::class.java) { StreamingJson.readObject(StringReader("""{"a": 1} {""")) }
        assertThrows(org.json.JSONException::class.java) { StreamingJson.readObject(StringReader("""[1]""")) }
    }
}
