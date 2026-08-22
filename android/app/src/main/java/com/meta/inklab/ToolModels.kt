package com.meta.inklab

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
}

class ToolShelf(initialTools: List<ToolPreset> = defaults()) {
    private val mutableTools = initialTools.toMutableList()
    private var sequence = mutableTools.size + 1

    var selectedId: String = mutableTools.first().id
        private set

    val tools: List<ToolPreset>
        get() = mutableTools

    val selected: ToolPreset
        get() = mutableTools.first { it.id == selectedId }

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

    private fun nextPenColor(): InkPalette {
        val used = mutableTools.filter { it.kind == DrawingToolKind.PEN }.map { it.color }.toSet()
        return InkPalette.entries.firstOrNull { it !in used } ?: InkPalette.BLACK
    }

    private fun nextId(prefix: String): String = "$prefix-${sequence++}"

    companion object {
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
