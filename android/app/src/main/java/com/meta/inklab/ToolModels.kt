package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

enum class DrawingToolKind(val label: String) {
    PEN("钢笔"),
    HIGHLIGHTER("荧光笔"),
    ERASER("橡皮"),
    LASSO("框选"),
}

enum class InkPalette(val label: String, val argb: Int) {
    BLACK("黑", 0xFF1B1B1F.toInt()),
    BLUE("蓝", 0xFF185ABD.toInt()),
    RED("红", 0xFFC42B1C.toInt()),
}

data class ToolPreset(
    val id: String,
    val kind: DrawingToolKind,
    var color: InkPalette = InkPalette.BLACK,
    var widthMm: Float = 0.4f,
    var pressurePercent: Int = 200,
    var inkFlowPercent: Int = 100,
    var opacityPercent: Int = 32,
    var eraserRadiusMm: Float = 4.5f,
    var pressureEraseEnabled: Boolean = true,
) {
    val displayName: String
        get() =
            when (kind) {
                DrawingToolKind.PEN -> "${color.label}色笔"
                DrawingToolKind.HIGHLIGHTER -> "${color.label}色荧光"
                DrawingToolKind.ERASER -> "橡皮"
                DrawingToolKind.LASSO -> "框选"
            }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("kind", kind.name)
            put("color", color.name)
            put("widthMm", widthMm.toDouble())
            put("pressurePercent", pressurePercent)
            put("inkFlowPercent", inkFlowPercent)
            put("opacityPercent", opacityPercent)
            put("eraserRadiusMm", eraserRadiusMm.toDouble())
            put("pressureEraseEnabled", pressureEraseEnabled)
        }

    companion object {
        fun fromJson(json: JSONObject): ToolPreset? {
            return try {
                val id = json.getString("id")
                val kind = DrawingToolKind.valueOf(json.getString("kind"))
                val colorName = json.optString("color", InkPalette.BLACK.name)
                val color = runCatching { InkPalette.valueOf(colorName) }.getOrDefault(InkPalette.BLACK)
                val widthMm = json.optDouble("widthMm", 0.4).toFloat()
                val pressurePercent = json.optInt("pressurePercent", 200)
                val inkFlowPercent = json.optInt("inkFlowPercent", 100)
                val opacityPercent = json.optInt("opacityPercent", 32)
                val eraserRadiusMm = json.optDouble("eraserRadiusMm", 4.5).toFloat()
                val pressureEraseEnabled = json.optBoolean("pressureEraseEnabled", true)
                ToolPreset(
                    id = id,
                    kind = kind,
                    color = color,
                    widthMm = widthMm,
                    pressurePercent = pressurePercent,
                    inkFlowPercent = inkFlowPercent,
                    opacityPercent = opacityPercent,
                    eraserRadiusMm = eraserRadiusMm,
                    pressureEraseEnabled = pressureEraseEnabled,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

class ToolShelf(initialTools: List<ToolPreset> = defaults()) {
    private val mutableTools = initialTools.toMutableList()
    private var sequence = mutableTools.size + 1

    var selectedId: String = mutableTools.firstOrNull()?.id ?: defaults().first().id
        private set

    val tools: List<ToolPreset>
        get() = mutableTools

    val selected: ToolPreset
        get() = mutableTools.firstOrNull { it.id == selectedId } ?: mutableTools.first()

    fun select(id: String): Boolean {
        if (mutableTools.none { it.id == id }) return false
        selectedId = id
        return true
    }

    fun add(kind: DrawingToolKind): ToolPreset {
        val preset =
            when (kind) {
                DrawingToolKind.PEN ->
                    ToolPreset(
                        id = nextId("pen"),
                        kind = kind,
                        color = nextPenColor(),
                    )
                DrawingToolKind.HIGHLIGHTER ->
                    ToolPreset(
                        id = nextId("highlighter"),
                        kind = kind,
                        color = InkPalette.BLUE,
                        widthMm = 5.5f,
                        opacityPercent = 32,
                        pressureEraseEnabled = false,
                    )
                DrawingToolKind.ERASER ->
                    ToolPreset(
                        id = nextId("eraser"),
                        kind = kind,
                        eraserRadiusMm = 4.5f,
                        pressureEraseEnabled = false,
                    )
                DrawingToolKind.LASSO ->
                    ToolPreset(
                        id = nextId("lasso"),
                        kind = kind,
                        pressureEraseEnabled = false,
                    )
            }
        mutableTools.add(preset)
        selectedId = preset.id
        return preset
    }

    fun deleteSelected(): Boolean {
        if (mutableTools.size <= 1) return false
        val index = mutableTools.indexOfFirst { it.id == selectedId }
        if (index < 0) return false
        mutableTools.removeAt(index)
        selectedId = mutableTools[index.coerceAtMost(mutableTools.lastIndex)].id
        return true
    }

    fun restoreFrom(other: ToolShelf) {
        mutableTools.clear()
        mutableTools.addAll(other.mutableTools)
        sequence = (mutableTools.mapNotNull { it.id.substringAfterLast("-", "").toIntOrNull() }.maxOrNull() ?: mutableTools.size) + 1
        selectedId = if (mutableTools.any { it.id == other.selectedId }) other.selectedId else mutableTools.first().id
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("selectedId", selectedId)
        val array = JSONArray()
        for (tool in mutableTools) {
            array.put(tool.toJson())
        }
        root.put("tools", array)
        return root.toString()
    }

    private fun nextPenColor(): InkPalette {
        val used = mutableTools.filter { it.kind == DrawingToolKind.PEN }.map { it.color }.toSet()
        return InkPalette.entries.firstOrNull { it !in used } ?: InkPalette.BLACK
    }

    private fun nextId(prefix: String): String = "$prefix-${sequence++}"

    companion object {
        fun fromJson(jsonStr: String): ToolShelf? {
            if (jsonStr.isBlank()) return null
            return try {
                val root = JSONObject(jsonStr)
                val array = root.optJSONArray("tools") ?: return null
                val list = mutableListOf<ToolPreset>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    ToolPreset.fromJson(obj)?.let { list.add(it) }
                }
                if (list.isEmpty()) return null
                val shelf = ToolShelf(list)
                val selectedId = root.optString("selectedId", "")
                if (selectedId.isNotEmpty() && list.any { it.id == selectedId }) {
                    shelf.select(selectedId)
                }
                shelf
            } catch (_: Exception) {
                null
            }
        }

        fun defaults(): List<ToolPreset> =
            listOf(
                ToolPreset("pen-black", DrawingToolKind.PEN, InkPalette.BLACK),
                ToolPreset("pen-blue", DrawingToolKind.PEN, InkPalette.BLUE),
                ToolPreset("pen-red", DrawingToolKind.PEN, InkPalette.RED),
                ToolPreset(
                    id = "highlighter-blue",
                    kind = DrawingToolKind.HIGHLIGHTER,
                    color = InkPalette.BLUE,
                    widthMm = 5.5f,
                    opacityPercent = 32,
                    pressureEraseEnabled = false,
                ),
                ToolPreset(
                    id = "eraser-stroke",
                    kind = DrawingToolKind.ERASER,
                    eraserRadiusMm = 4.5f,
                    pressureEraseEnabled = false,
                ),
                ToolPreset(
                    id = "lasso-freeform",
                    kind = DrawingToolKind.LASSO,
                    pressureEraseEnabled = false,
                ),
            )
    }
}

