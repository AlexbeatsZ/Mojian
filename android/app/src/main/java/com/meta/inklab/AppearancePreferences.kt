package com.meta.inklab

import org.json.JSONObject

/** Device preferences override legacy page themes without rewriting the shared library. */
data class AppearancePreferences(val darkBackground: Boolean = true,
    val defaultBackground: CanvasBackgroundFormat = CanvasBackgroundFormat.GRID_STANDARD) {
    fun display(settings: PageViewSettings) = settings.copy(darkBackground = darkBackground)
    fun newPage(template: PageViewSettings) = display(template).copy(backgroundFormat = defaultBackground, a4PageCount = 1)
    fun toJson(): String = JSONObject().put("darkBackground", darkBackground)
        .put("defaultBackground", defaultBackground.name).toString()

    companion object {
        fun restore(json: String?, legacyPage: PageViewSettings, legacyTemplate: PageViewSettings): AppearancePreferences {
            val saved = runCatching { JSONObject(json.orEmpty()) }.getOrNull()
            return AppearancePreferences(saved?.optBoolean("darkBackground", legacyPage.darkBackground) ?: legacyPage.darkBackground,
                runCatching { CanvasBackgroundFormat.valueOf(saved?.optString("defaultBackground").orEmpty()) }
                    .getOrDefault(legacyTemplate.backgroundFormat))
        }
    }
}
