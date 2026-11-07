package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

enum class DrawingToolKind(val label: String) {
    PEN("钢笔"),
    HIGHLIGHTER("荧光笔"),
    ERASER("橡皮"),
    LASSO("套索"),
    SHAPE("图形"),
}

enum class LassoShape(val label: String) {
    FREEFORM("自由圈选"),
    RECTANGLE("矩形"),
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
    /** Notein-style input smoothing (0-100%); 50% is the AndroidX default 20 ms window. */
    var stabilityPercent: Int = 50,
    var opacityPercent: Int = 32,
    var eraserRadiusMm: Float = 4.5f,
    var pressureEraseEnabled: Boolean = false,
    var pressureEraseThresholdPercent: Int = 78,
    var pressureEraseDwellMillis: Long = 140L,
    var lassoShape: LassoShape = LassoShape.FREEFORM,
    var shapeKind: ShapeKind = ShapeKind.LINE,
    var holdToStraightenEnabled: Boolean = true,
    var straightenDelayMillis: Long = 1000L,
    var pressureEraseTravelLimitMm: Float = 1f,
    var penStyleId: String = "",
    var penStyle: PenStyle? = null,
) {
    val displayName: String
        get() =
            when (kind) {
                DrawingToolKind.PEN -> "${color.label}色笔"
                DrawingToolKind.HIGHLIGHTER -> "${color.label}色荧光"
                DrawingToolKind.ERASER -> "橡皮"
                DrawingToolKind.LASSO -> "套索"
                DrawingToolKind.SHAPE -> "图形"
            }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("kind", kind.name)
            put("color", color.name)
            put("widthMm", widthMm.toDouble())
            put("pressurePercent", pressurePercent)
            put("inkFlowPercent", inkFlowPercent)
            put("stabilityPercent", stabilityPercent)
            put("penStyleId", penStyleId)
            penStyle?.let { put("penStyle", it.toJson()) }
            put("opacityPercent", opacityPercent)
            put("eraserRadiusMm", eraserRadiusMm.toDouble())
            put("pressureEraseEnabled", pressureEraseEnabled)
            put("pressureEraseThresholdPercent", pressureEraseThresholdPercent)
            put("pressureEraseDwellMillis", pressureEraseDwellMillis)
            put("pressureEraseTravelLimitMm", pressureEraseTravelLimitMm.toDouble())
            put("lassoShape", lassoShape.name)
            put("shapeKind", shapeKind.name)
            put("holdToStraightenEnabled", holdToStraightenEnabled)
            put("straightenDelayMillis", straightenDelayMillis)
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
                    .coerceIn(MINIMUM_ERASER_RADIUS_MM, MAXIMUM_ERASER_RADIUS_MM)
                val pressureEraseEnabled = json.optBoolean("pressureEraseEnabled", false)
                val pressureEraseThresholdPercent = json.optInt("pressureEraseThresholdPercent", 78).coerceIn(40, 100)
                val pressureEraseDwellMillis = json.optLong("pressureEraseDwellMillis", 140L).coerceIn(50L, 500L)
                val lassoShape =
                    runCatching { LassoShape.valueOf(json.optString("lassoShape", LassoShape.FREEFORM.name)) }
                        .getOrDefault(LassoShape.FREEFORM)
                ToolPreset(
                    id = id,
                    kind = kind,
                    color = color,
                    widthMm = widthMm,
                    pressurePercent = pressurePercent,
                    inkFlowPercent = inkFlowPercent,
                    stabilityPercent = json.optInt("stabilityPercent", 50).coerceIn(0, 100),
                    penStyleId = json.optString("penStyleId", ""),
                    penStyle = PenStyle.fromJson(json.optJSONObject("penStyle")),
                    opacityPercent = opacityPercent,
                    eraserRadiusMm = eraserRadiusMm,
                    pressureEraseEnabled = pressureEraseEnabled,
                    pressureEraseThresholdPercent = pressureEraseThresholdPercent,
                    pressureEraseDwellMillis = pressureEraseDwellMillis,
                    pressureEraseTravelLimitMm = json.optDouble("pressureEraseTravelLimitMm", 1.0).toFloat().coerceIn(.1f, 10f),
                    lassoShape = lassoShape,
                    shapeKind = runCatching { ShapeKind.valueOf(json.optString("shapeKind", "LINE")) }.getOrDefault(ShapeKind.LINE),
                    holdToStraightenEnabled = json.optBoolean("holdToStraightenEnabled", true),
                    straightenDelayMillis = json.optLong("straightenDelayMillis", 1000L).coerceIn(200L, 2000L),
                )
            } catch (_: Exception) {
                null
            }
        }

        const val MINIMUM_ERASER_RADIUS_MM = 0.25f
        const val MAXIMUM_ERASER_RADIUS_MM = 40f
    }
}

class ToolShelf(initialTools: List<ToolPreset> = defaults()) {
    private val mutableTools = initialTools.toMutableList()
        .apply { restoreMissingDefaults() }
    private var sequence = mutableTools.size + 1
    private val mutablePenStyles = NamedPenStyle.defaults().toMutableList()
    val penStyles: List<NamedPenStyle> get() = mutablePenStyles

    init {
        mutableTools.filter { it.kind == DrawingToolKind.PEN && it.penStyleId.isBlank() }.forEach { tool ->
            val style = effectivePenStyle(tool)
            val preset = mutablePenStyles.firstOrNull { it.style.sameParameters(style) } ?: run {
                val baseName = "${tool.displayName}（原设置）"; var title = baseName; var suffix = 2
                while (mutablePenStyles.any { it.name == title }) title = "$baseName ${suffix++}"
                NamedPenStyle("migrated-${tool.id}", title, style).also { mutablePenStyles.add(it) }
            }
            tool.penStyleId = preset.id
        }
    }

    fun effectivePenStyle(tool: ToolPreset) = tool.penStyle?.snapshot() ?: PenStyle.fromLegacy(tool.pressurePercent, tool.inkFlowPercent, tool.stabilityPercent)

    fun applyPenStyle(tool: ToolPreset, preset: NamedPenStyle) {
        tool.penStyleId = preset.id
        tool.penStyle = preset.style.snapshot()
        tool.stabilityPercent = preset.style.stabilityPercent
    }

    fun savePenStyle(id: String?, name: String, style: PenStyle): NamedPenStyle {
        val title = name.trim()
        require(title.length in 1..40) { "名称须为 1–40 个字符。" }
        require(mutablePenStyles.none { it.id != id && it.name.equals(title, ignoreCase = true) }) { "已有同名预设，请使用其他名称。" }
        val saved = NamedPenStyle(id ?: java.util.UUID.randomUUID().toString(), title, requireNotNull(PenStyle.fromJson(style.toJson())))
        val index = mutablePenStyles.indexOfFirst { it.id == saved.id }
        require(id == null || index >= 0) { "预设已不存在，请新建预设。" }
        if (index < 0) mutablePenStyles.add(saved) else mutablePenStyles[index] = saved
        mutableTools.filter { it.kind == DrawingToolKind.PEN && it.penStyleId == saved.id }.forEach { applyPenStyle(it, saved) }
        return saved
    }

    var selectedId: String = mutableTools.first().id
        private set

    var pressureEraserId: String = mutableTools.first { it.kind == DrawingToolKind.ERASER }.id
        private set

    val tools: List<ToolPreset>
        get() = mutableTools

    val selected: ToolPreset
        get() = mutableTools.firstOrNull { it.id == selectedId } ?: mutableTools.first()

    val pressureEraser: ToolPreset
        get() = mutableTools.firstOrNull { it.id == pressureEraserId && it.kind == DrawingToolKind.ERASER }
            ?: mutableTools.first { it.kind == DrawingToolKind.ERASER }

    fun select(id: String): Boolean {
        if (mutableTools.none { it.id == id }) return false
        selectedId = id
        if (mutableTools.first { it.id == id }.kind == DrawingToolKind.ERASER) pressureEraserId = id
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
                        pressureEraseEnabled = true,
                    )
                DrawingToolKind.SHAPE -> ToolPreset(nextId("shape"), kind)
                DrawingToolKind.LASSO ->
                    ToolPreset(
                        id = nextId("lasso"),
                        kind = kind,
                        pressureEraseEnabled = false,
                    )
            }
        mutableTools.add(preset)
        if (kind == DrawingToolKind.PEN) applyPenStyle(preset, mutablePenStyles.first { it.id == "natural" })
        selectedId = preset.id
        if (kind == DrawingToolKind.ERASER) pressureEraserId = preset.id
        return preset
    }

    fun restoreFrom(other: ToolShelf) {
        mutableTools.clear()
        mutableTools.addAll(other.mutableTools)
        mutablePenStyles.clear()
        mutablePenStyles.addAll(other.mutablePenStyles.map { it.copy(style = it.style.snapshot()) })
        mutableTools.restoreMissingDefaults()
        sequence = (mutableTools.mapNotNull { it.id.substringAfterLast("-", "").toIntOrNull() }.maxOrNull() ?: mutableTools.size) + 1
        selectedId = if (mutableTools.any { it.id == other.selectedId }) other.selectedId else mutableTools.first().id
        pressureEraserId = if (mutableTools.any { it.id == other.pressureEraserId && it.kind == DrawingToolKind.ERASER }) {
            other.pressureEraserId
        } else {
            mutableTools.first { it.kind == DrawingToolKind.ERASER }.id
        }
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("selectedId", selectedId)
        root.put("pressureEraserId", pressureEraserId)
        val array = JSONArray()
        for (tool in mutableTools) {
            array.put(tool.toJson())
        }
        root.put("tools", array)
        root.put("penStyles", JSONArray().apply { mutablePenStyles.forEach { put(it.toJson()) } })
        return root.toString()
    }

    private fun nextPenColor(): InkPalette {
        val used = mutableTools.filter { it.kind == DrawingToolKind.PEN }.map { it.color }.toSet()
        return InkPalette.entries.firstOrNull { it !in used } ?: InkPalette.BLACK
    }

    private fun nextId(prefix: String): String = "$prefix-${sequence++}"

    private fun MutableList<ToolPreset>.restoreMissingDefaults() {
        val knownIds = mapTo(mutableSetOf()) { it.id }
        defaults().forEach { preset -> if (knownIds.add(preset.id)) add(preset) }
    }

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
                root.optJSONArray("penStyles")?.let { styles ->
                    for (i in 0 until styles.length()) NamedPenStyle.fromJson(styles.optJSONObject(i))?.let { saved ->
                        val index = shelf.mutablePenStyles.indexOfFirst { it.id == saved.id }
                        if (index < 0) shelf.mutablePenStyles.add(saved) else shelf.mutablePenStyles[index] = saved
                    }
                }
                if (root.optInt("version", 1) < 2) {
                    shelf.pressureEraser.pressureEraseEnabled =
                        list.any { it.kind in setOf(DrawingToolKind.PEN, DrawingToolKind.HIGHLIGHTER) && it.pressureEraseEnabled }
                }
                val selectedId = root.optString("selectedId", "")
                if (selectedId.isNotEmpty() && list.any { it.id == selectedId }) {
                    shelf.select(selectedId)
                }
                val pressureEraserId = root.optString("pressureEraserId", "")
                if (list.any { it.id == pressureEraserId && it.kind == DrawingToolKind.ERASER }) {
                    shelf.pressureEraserId = pressureEraserId
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
                    pressureEraseEnabled = true,
                ),
                ToolPreset("shape-default", DrawingToolKind.SHAPE),
                ToolPreset(
                    id = "lasso-freeform",
                    kind = DrawingToolKind.LASSO,
                    pressureEraseEnabled = false,
                ),
            )
    }
}
