package com.meta.inklab

import org.json.JSONObject

const val DOCUMENT_UNITS_PER_MM = 12.16225f

enum class PaperSize(
    val menuLabel: String,
    val widthMm: Float,
    val heightMm: Float?,
) {
    AUTO("自动", 210f, null),
    A4_PORTRAIT("A4", 210f, 297f),
    A4_LANDSCAPE("A4 横向", 297f, 210f),
    LETTER_PORTRAIT("Letter", 215.9f, 279.4f),
    LETTER_LANDSCAPE("Letter 横向", 279.4f, 215.9f),
    ;

    val documentWidth: Float
        get() = widthMm * DOCUMENT_UNITS_PER_MM

    val documentHeight: Float?
        get() = heightMm?.times(DOCUMENT_UNITS_PER_MM)
}

enum class PageColor(
    val description: String,
    val lightArgb: Int,
    val darkArgb: Int,
) {
    WHITE("白色", 0xFFFAFAFA.toInt(), 0xFF1B1B1B.toInt()),
    SAND("米色", 0xFFF3E9D2.toInt(), 0xFF302A21.toInt()),
    BLUE("蓝色", 0xFFE2EDF8.toInt(), 0xFF1D2B38.toInt()),
    MINT("绿色", 0xFFE0F1E7.toInt(), 0xFF1E3028.toInt()),
    ROSE("粉色", 0xFFF4E2E8.toInt(), 0xFF352329.toInt()),
    ;

    fun displayArgb(darkBackground: Boolean): Int = if (darkBackground) darkArgb else lightArgb
}

data class PageViewSettings(
    var paperSize: PaperSize = PaperSize.AUTO,
    var pageColor: PageColor = PageColor.WHITE,
    var pageTitleVisible: Boolean = true,
    var darkBackground: Boolean = true,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("paperSize", paperSize.name)
            put("pageColor", pageColor.name)
            put("pageTitleVisible", pageTitleVisible)
            put("darkBackground", darkBackground)
        }

    companion object {
        fun fromJson(json: JSONObject): PageViewSettings =
            PageViewSettings(
                paperSize =
                    runCatching { PaperSize.valueOf(json.optString("paperSize", PaperSize.AUTO.name)) }
                        .getOrDefault(PaperSize.AUTO),
                pageColor =
                    runCatching { PageColor.valueOf(json.optString("pageColor", PageColor.WHITE.name)) }
                        .getOrDefault(PageColor.WHITE),
                pageTitleVisible = json.optBoolean("pageTitleVisible", true),
                darkBackground = json.optBoolean("darkBackground", true),
            )
    }
}
