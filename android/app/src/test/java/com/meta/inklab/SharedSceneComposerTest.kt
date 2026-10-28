package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedSceneComposerTest {
    @Test
    fun mixedScenePreservesBackgroundLayerZOrderStableTiesAndVisibility() {
        val source = JSONObject().put("layers", JSONArray()
            .put(JSONObject().put("id", "upper").put("order", 2))
            .put(JSONObject().put("id", "lower").put("order", 1))
            .put(JSONObject().put("id", "hidden").put("visible", false)))
        fun item(value: String, layer: String, order: Long = 0) = SharedSceneComposer.Item(value,
            JSONObject().put("layerId", layer).put("zOrder", order))
        val entries = listOf(item("text", "upper"), item("image", "lower", 4), item("ink", "lower", 2),
            item("tie", "lower", 2), item("hidden", "hidden"),
            SharedSceneComposer.Item("background", JSONObject().put("isPageBackground", true)),
            SharedSceneComposer.Item("invisible stroke", JSONObject(), false), item("unknown", "unknown"))
        assertEquals(listOf("background", "ink", "tie", "image", "text", "unknown"), SharedSceneComposer.ordered(source, entries))
    }
}
