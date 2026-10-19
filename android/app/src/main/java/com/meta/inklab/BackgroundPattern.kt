package com.meta.inklab

import org.json.JSONObject

enum class BackgroundCategory(val label: String) {
    BLANK("空白"), RULE_LINES("横线"), GRID("网格"), DOTS("点阵"),
}

// Preserve names and ordering used by preferences and .mjn files.
enum class CanvasBackgroundFormat {
    BLANK, RULE_STANDARD, RULE_NARROW, RULE_WIDE, RULE_CLEAN,
    RULE_TWO_COLUMN_STANDARD, RULE_TWO_COLUMN_NARROW, RULE_TWO_COLUMN_WIDE,
    GRID_STANDARD, GRID_DENSE, GRID_WIDE, DOTS_STANDARD, DOTS_DENSE, DOTS_WIDE;

    val pattern: BackgroundPattern get() = BackgroundPatternCatalog.formats.getValue(name)
    val label: String get() = pattern.label
    val category: BackgroundCategory get() = pattern.category
    val stepMultiplier: Float get() = pattern.spacing / BackgroundPatternCatalog.baseSpacing
    val showMarginLine: Boolean get() = pattern.margin
    val showColumnDivider: Boolean get() = pattern.divider
}

data class BackgroundPattern(
    val label: String, val category: BackgroundCategory, val spacing: Float,
    val margin: Boolean, val divider: Boolean,
)

data class BackgroundPalette(val line: Int, val major: Int, val dot: Int, val margin: Int)

/** The same packaged file is embedded by WPF. Lengths are canonical 96-DPI document units. */
object BackgroundPatternCatalog {
    private val source = JSONObject(requireNotNull(javaClass.getResourceAsStream("/page-backgrounds.json")) {
        "Missing shared page background contract."
    }.bufferedReader(Charsets.UTF_8).use { it.readText() })
    val baseSpacing = source.getDouble("baseSpacing").toFloat()
    val lineWidth = source.getDouble("lineWidth").toFloat()
    val screenLineWidth = source.getBoolean("screenLineWidth")
    val marginWidth = source.getDouble("marginWidth").toFloat()
    val marginX = source.getDouble("marginX").toFloat()
    val dotRadius = source.getDouble("dotRadius").toFloat()
    val majorEvery = source.getInt("majorEvery")
    val a4PageGap = source.getDouble("a4PageGap").toFloat()
    val formats: Map<String, BackgroundPattern> = source.getJSONArray("formats").let { array ->
        (0 until array.length()).associate { index ->
            val item = array.getJSONObject(index)
            item.getString("id") to BackgroundPattern(item.getString("label"),
                BackgroundCategory.valueOf(item.getString("category")), item.getDouble("spacing").toFloat(),
                item.optBoolean("margin"), item.optBoolean("divider"))
        }
    }
    private fun palette(key: String): BackgroundPalette {
        val item = source.getJSONObject(key)
        fun color(name: String) = (0xFF000000L or item.getString(name).removePrefix("#").toLong(16)).toInt()
        return BackgroundPalette(color("line"), color("major"), color("dot"), color("margin"))
    }
    val dark = palette("dark")
    val light = palette("light")

    fun documentUnits(canonical: Float): Float = canonical * (DOCUMENT_UNITS_PER_MM * 25.4f / 96f)
}
