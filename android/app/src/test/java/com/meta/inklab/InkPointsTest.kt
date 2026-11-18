package com.meta.inklab

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPointsTest {
    private val cases: JSONArray = JSONObject(requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/points-coded.json"))
        .bufferedReader().use { it.readText() }).getJSONArray("cases")
    private val scales = mapOf("x" to 1000.0, "y" to 1000.0, "pressure" to 10000.0, "elapsedMilliseconds" to 1.0,
        "tiltX" to 100.0, "tiltY" to 100.0, "tiltRadians" to 10000.0, "orientationRadians" to 10000.0)

    @Test
    fun goldenVectorsEncodeExactlyAndDecodeWithinHalfAQuantum() {
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val points = case.getJSONArray("points")
            val coded = case.opt("coded").takeIf { it is String } as String?
            assertEquals(name, coded, InkPoints.encode(points))
            if (coded == null) continue
            val decoded = InkPoints.decode(coded)
            assertEquals(name, points.length(), decoded.length())
            for (p in 0 until points.length()) {
                val expected = points.getJSONObject(p); val actual = decoded.getJSONObject(p)
                fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
                assertEquals("$name[$p] keys", expected.keyNames(), actual.keyNames())
                for (key in expected.keyNames())
                    assertTrue("$name[$p].$key", Math.abs(expected.getDouble(key) - actual.getDouble(key)) <= .5 / scales.getValue(key) + 1e-12)
            }
            assertEquals(name, coded, InkPoints.encode(decoded))
        }
    }

    @Test
    fun packReplacesOnlyEligibleArraysAndReadDecodesOnDemand() {
        val plain = JSONObject().put("id", "a").put("points", JSONArray().put(JSONObject().put("x", 1.5).put("y", 2).put("pressure", .5)))
        InkPoints.pack(plain)
        assertFalse(plain.has("points"))
        assertTrue(plain.getString("pointsCoded").startsWith(InkPoints.PREFIX))
        assertEquals(1.5, InkPoints.read(plain).getJSONObject(0).getDouble("x"), 1e-9)
        assertFalse("read must not expand the stored stroke", plain.has("points"))
        assertEquals(1, InkPoints.count(plain))
        val extended = JSONObject().put("points", JSONArray().put(JSONObject().put("x", 1).put("y", 2).put("futureSample", true)))
        InkPoints.pack(extended)
        assertTrue(extended.has("points"))
        assertNull(InkPoints.encode(JSONArray()))
        assertEquals(0, InkPoints.read(JSONObject()).length())
        assertThrows(JSONException::class.java) { InkPoints.decode("1:AQ==") }
        assertThrows(JSONException::class.java) { InkPoints.decode("2:AA==") }
        assertThrows(JSONException::class.java) { InkPoints.decode("1:@@") }
    }

    @Test
    fun streamedPageKeepsPackedStringsInsteadOfPointObjects() {
        val coded = cases.getJSONObject(0).getString("coded")
        val page = """{"version":2,"strokes":[{"id":"s","pointsCoded":"$coded","vectorPath":"F1M0,0l1,0 0,1z"}]}"""
        val parsed = StreamingJson.readObject(java.io.StringReader(page))
        val stroke = parsed.getJSONArray("strokes").getJSONObject(0)
        assertEquals(coded, stroke.get("pointsCoded"))
        assertEquals(3, InkPoints.read(stroke).length())
        // JVM org.json is unordered (Android keeps insertion order): compare content, not key order.
        val written = java.io.StringWriter().also { WorkspaceJson.write(parsed, it) }.toString()
        assertEquals(WorkspaceJson.fingerprint(JSONObject(page)), WorkspaceJson.fingerprint(JSONObject(written)))
        assertTrue(written.contains("\"pointsCoded\":\"$coded\""))
        assertFalse(written.contains("\"points\""))
    }
}
