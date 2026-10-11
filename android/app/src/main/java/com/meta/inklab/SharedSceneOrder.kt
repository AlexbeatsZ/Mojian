package com.meta.inklab

import org.json.JSONObject

object SharedSceneOrder {
    fun visible(source: JSONObject,metadata: JSONObject): Boolean = SharedWorkspaceCodec.items(source.optJSONArray("layers"))
        .firstOrNull { it.optString("id")==metadata.optString("layerId") }?.optBoolean("visible",true) ?: true
    fun layer(source: JSONObject,metadata: JSONObject): Int = if(metadata.optBoolean("isPageBackground")) Int.MIN_VALUE else
        SharedWorkspaceCodec.items(source.optJSONArray("layers")).firstOrNull { it.optString("id")==metadata.optString("layerId") }?.optInt("order") ?: Int.MAX_VALUE
}
