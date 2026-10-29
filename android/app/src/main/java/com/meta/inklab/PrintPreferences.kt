package com.meta.inklab

import org.json.JSONObject

/** Only reusable choices are retained; image IDs belong to the current output scope. */
object PrintPreferences {
    fun save(options: ExportOptions): String = JSONObject().apply {
        put("scope", options.scope.name); put("paper", options.paper.name)
        put("landscape", options.landscape); put("marginMm", options.marginMm.toDouble())
        put("trimTrailingBlank", options.trimTrailingBlank); put("pageRange", options.pageRange)
        put("hideBackgroundFormat", options.hideBackgroundFormat)
    }.toString()

    fun restore(json: String?): ExportOptions {
        val saved = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return ExportOptions(printing = true)
        return ExportOptions(
            scope = runCatching { ExportScope.valueOf(saved.optString("scope")) }.getOrDefault(ExportScope.PAGE),
            paper = runCatching { ExportPaper.valueOf(saved.optString("paper")) }.getOrDefault(ExportPaper.AUTO),
            landscape = saved.optBoolean("landscape", false),
            marginMm = saved.optDouble("marginMm", 6.0).toFloat().takeIf { it in listOf(0f, 6f, 12f, 20f) } ?: 6f,
            trimTrailingBlank = saved.optBoolean("trimTrailingBlank", true),
            pageRange = saved.optString("pageRange", ""), printing = true,
            hideBackgroundFormat = saved.optBoolean("hideBackgroundFormat", false))
    }
}
