package com.meta.inklab

import org.json.JSONObject

object SharedSceneComposer {
    data class Item<T>(val value: T, val metadata: JSONObject, val visible: Boolean = true)

    fun <T> ordered(source: JSONObject, items: List<Item<T>>): List<T> {
        val layers = SharedWorkspaceCodec.items(source.optJSONArray("layers")).associateBy { it.optString("id") }
        return items.withIndex().filter { (_, item) ->
            item.visible && layers[item.metadata.optString("layerId")]?.optBoolean("visible", true) != false
        }.sortedWith(compareBy<IndexedValue<Item<T>>> {
            if (it.value.metadata.optBoolean("isPageBackground")) Int.MIN_VALUE
            else layers[it.value.metadata.optString("layerId")]?.optInt("order") ?: Int.MAX_VALUE
        }.thenBy { it.value.metadata.optLong("zOrder") }.thenBy { it.index }).map { it.value.value }
    }
}
