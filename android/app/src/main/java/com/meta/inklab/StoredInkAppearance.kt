package com.meta.inklab

import org.json.JSONObject

/** Matches desktop rendering without rewriting historical stroke metadata. */
object StoredInkAppearance {
    fun opacityPercent(stroke: JSONObject): Int {
        // Older desktop freehand pens inherited the tool's highlighter-only 32% field.
        // Desktop fountain rendering ignores it; shape/vector sources use real opacity.
        if (stroke.optInt("kind") == 0 && stroke.optString("vectorPath").isBlank() &&
            stroke.optString("brushProfile", "fountain-v1") != "shape-v1") return 100
        return stroke.optInt("opacityPercent", 100).coerceIn(0, 100)
    }
}
