package com.meta.inklab

import android.view.KeyEvent
import org.json.JSONObject

enum class ShortcutCategory(val label: String) {
    FILE("文件"),
    HOME("开始"),
    INSERT("插入"),
    DRAW("绘图"),
    VIEW("视图"),
}

data class ShortcutActionDescriptor(
    val id: String,
    val label: String,
    val category: ShortcutCategory,
)

object ShortcutActionCatalog {
    fun actions(tools: List<ToolPreset>): List<ShortcutActionDescriptor> =
        buildList {
            fun add(category: ShortcutCategory, id: String, label: String) {
                add(ShortcutActionDescriptor(id, label, category))
            }

            add(ShortcutCategory.FILE, "file:backstage", "打开文件页")
            add(ShortcutCategory.FILE, "file:save", "保存")
            add(ShortcutCategory.FILE, "file:export-page-pdf", "导出当前页面 PDF")
            add(ShortcutCategory.FILE, "file:export-notebook", "导出当前笔记本 .mjn")

            add(ShortcutCategory.HOME, "home:paste", "粘贴")
            add(ShortcutCategory.HOME, "home:cut", "剪切")
            add(ShortcutCategory.HOME, "home:copy", "复制")
            add(ShortcutCategory.HOME, "home:copy-svg", "复制为 SVG")
            add(ShortcutCategory.HOME, "home:copy-png", "复制为 PNG")
            add(ShortcutCategory.HOME, "home:bold", "加粗")
            add(ShortcutCategory.HOME, "home:italic", "倾斜")
            add(ShortcutCategory.HOME, "home:underline", "下划线")
            add(ShortcutCategory.HOME, "home:strikethrough", "删除线")
            add(ShortcutCategory.HOME, "home:clear-formatting", "清除所有格式")
            add(ShortcutCategory.HOME, "home:bullets", "项目符号")
            add(ShortcutCategory.HOME, "home:numbering", "编号")
            add(ShortcutCategory.HOME, "home:indent-decrease", "减少缩进")
            add(ShortcutCategory.HOME, "home:indent-increase", "增加缩进")
            add(ShortcutCategory.HOME, "home:align-left", "左对齐")
            add(ShortcutCategory.HOME, "home:align-center", "居中")
            add(ShortcutCategory.HOME, "home:align-right", "右对齐")
            add(ShortcutCategory.HOME, "home:tag-important", "标记：重要")
            add(ShortcutCategory.HOME, "home:tag-question", "标记：问题")
            add(ShortcutCategory.HOME, "home:find-tags", "查找标记")

            add(ShortcutCategory.INSERT, "insert:table-2x2", "插入 2 × 2 表格")
            add(ShortcutCategory.INSERT, "insert:attachment", "插入附件")
            add(ShortcutCategory.INSERT, "insert:pdf-printout", "插入 PDF 打印样式")
            add(ShortcutCategory.INSERT, "insert:spreadsheet", "插入电子表格")
            add(ShortcutCategory.INSERT, "insert:images", "插入图片")
            add(ShortcutCategory.INSERT, "insert:stickers", "插入贴纸")
            add(ShortcutCategory.INSERT, "insert:note-link", "插入笔记链接")

            add(ShortcutCategory.DRAW, "draw:undo", "撤销")
            add(ShortcutCategory.DRAW, "draw:redo", "重做")
            tools
                .sortedWith(
                    compareBy<ToolPreset> {
                        when (it.kind) {
                            DrawingToolKind.LASSO -> 0
                            DrawingToolKind.ERASER -> 1
                            DrawingToolKind.HIGHLIGHTER -> 2
                            DrawingToolKind.PEN -> 3
                            DrawingToolKind.SHAPE -> 4
                        }
                    },
                ).forEach { tool -> add(ShortcutCategory.DRAW, "tool:${tool.id}", "切换工具：${tool.displayName}") }
            add(ShortcutCategory.DRAW, "draw:previous-tool", "切换到上一个工具")
            add(ShortcutCategory.DRAW, "draw:next-tool", "切换到下一个工具")
            add(ShortcutCategory.DRAW, "draw:pan", "切换全景手柄")
            add(ShortcutCategory.DRAW, "draw:insert-space", "切换插入空间")
            add(ShortcutCategory.DRAW, "draw:delete-selection", "删除所选内容")
            add(ShortcutCategory.DRAW, "draw:clear", "清纸")
            CanvasBackgroundFormat.entries.forEach { format ->
                add(ShortcutCategory.DRAW, "draw:background:${format.name}", "背景：${format.label}")
            }

            add(ShortcutCategory.VIEW, "view:full-page", "整页视图")
            add(ShortcutCategory.VIEW, "view:normal", "普通视图")
            add(ShortcutCategory.VIEW, "view:split", "切换拆分视图")
            add(ShortcutCategory.VIEW, "view:zoom-lock", "切换缩放锁定")
            add(ShortcutCategory.VIEW, "view:zoom-in", "放大")
            add(ShortcutCategory.VIEW, "view:zoom-out", "缩小")
            add(ShortcutCategory.VIEW, "view:zoom-reset", "缩放到 100%")
            add(ShortcutCategory.VIEW, "view:fit-width", "适应页宽")
            ViewportState.ZOOM_PRESETS.forEach { percent ->
                add(ShortcutCategory.VIEW, "view:zoom:$percent", "缩放到 $percent%")
            }
            add(ShortcutCategory.VIEW, "view:toggle-title", "显示或隐藏页面标题")
            add(ShortcutCategory.VIEW, "view:toggle-theme", "切换明暗页面")
            add(ShortcutCategory.VIEW, "view:paper:A4", "纸张：A4")
            add(ShortcutCategory.VIEW, "view:paper:BOUNDLESS", "纸张：无边界")
            add(ShortcutCategory.VIEW, "view:section-pane", "显示或隐藏分区栏")
            add(ShortcutCategory.VIEW, "view:page-pane", "显示或隐藏页面栏")
            add(ShortcutCategory.VIEW, "view:previous-page", "上一个页面")
            add(ShortcutCategory.VIEW, "view:next-page", "下一个页面")
        }
}

data class ShortcutKeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val displayName: String
        get() =
            buildList {
                if (ctrl) add("Ctrl")
                if (alt) add("Alt")
                if (shift) add("Shift")
                if (meta) add("Meta")
                add(keyLabel(keyCode))
            }.joinToString(" + ")

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("keyCode", keyCode)
            put("ctrl", ctrl)
            put("alt", alt)
            put("shift", shift)
            put("meta", meta)
        }

    companion object {
        fun fromEvent(event: KeyEvent): ShortcutKeyChord? {
            if (event.action != KeyEvent.ACTION_DOWN || event.keyCode in modifierKeyCodes) return null
            return ShortcutKeyChord(
                keyCode = event.keyCode,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                meta = event.isMetaPressed,
            )
        }

        fun fromJson(json: JSONObject): ShortcutKeyChord? =
            json.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
                .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                ?.let {
                    ShortcutKeyChord(
                        keyCode = it,
                        ctrl = json.optBoolean("ctrl"),
                        alt = json.optBoolean("alt"),
                        shift = json.optBoolean("shift"),
                        meta = json.optBoolean("meta"),
                    )
                }

        private fun keyLabel(keyCode: Int): String =
            when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0'.code + keyCode - KeyEvent.KEYCODE_0).toChar().toString()
                KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                KeyEvent.KEYCODE_DEL -> "Backspace"
                KeyEvent.KEYCODE_SPACE -> "Space"
                KeyEvent.KEYCODE_ENTER -> "Enter"
                KeyEvent.KEYCODE_ESCAPE -> "Esc"
                KeyEvent.KEYCODE_PLUS -> "+"
                KeyEvent.KEYCODE_MINUS -> "-"
                else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
            }

        private val modifierKeyCodes =
            setOf(
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
            )
    }
}

class KeyboardShortcutSettings(initial: Map<String, ShortcutKeyChord> = defaults()) {
    private val mutableBindings = initial.toMutableMap()

    val bindings: Map<String, ShortcutKeyChord>
        get() = mutableBindings.toMap()

    fun actionFor(chord: ShortcutKeyChord): String? = mutableBindings.entries.firstOrNull { it.value == chord }?.key

    fun assign(actionId: String, chord: ShortcutKeyChord) {
        mutableBindings.entries.removeAll { it.value == chord && it.key != actionId }
        mutableBindings[actionId] = chord
    }

    fun clear(actionId: String) {
        mutableBindings.remove(actionId)
    }

    fun toJson(): String {
        val root = JSONObject()
        mutableBindings.forEach { (id, chord) -> root.put(id, chord.toJson()) }
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String?): KeyboardShortcutSettings {
            if (raw.isNullOrBlank()) return KeyboardShortcutSettings()
            return runCatching {
                val root = JSONObject(raw)
                val result = linkedMapOf<String, ShortcutKeyChord>()
                root.keys().forEach { id -> ShortcutKeyChord.fromJson(root.getJSONObject(id))?.let { result[id] = it } }
                KeyboardShortcutSettings(result)
            }.getOrDefault(KeyboardShortcutSettings())
        }

        fun defaults(): Map<String, ShortcutKeyChord> =
            linkedMapOf(
                "draw:undo" to ShortcutKeyChord(KeyEvent.KEYCODE_Z, ctrl = true),
                "draw:redo" to ShortcutKeyChord(KeyEvent.KEYCODE_Y, ctrl = true),
                "file:save" to ShortcutKeyChord(KeyEvent.KEYCODE_S, ctrl = true),
                "home:cut" to ShortcutKeyChord(KeyEvent.KEYCODE_X, ctrl = true),
                "home:copy" to ShortcutKeyChord(KeyEvent.KEYCODE_C, ctrl = true),
                "home:paste" to ShortcutKeyChord(KeyEvent.KEYCODE_V, ctrl = true),
                "home:bold" to ShortcutKeyChord(KeyEvent.KEYCODE_B, ctrl = true),
                "home:italic" to ShortcutKeyChord(KeyEvent.KEYCODE_I, ctrl = true),
                "home:underline" to ShortcutKeyChord(KeyEvent.KEYCODE_U, ctrl = true),
                "view:full-page" to ShortcutKeyChord(KeyEvent.KEYCODE_F11),
                "view:previous-page" to ShortcutKeyChord(KeyEvent.KEYCODE_PAGE_UP, ctrl = true),
                "view:next-page" to ShortcutKeyChord(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true),
                "draw:delete-selection" to ShortcutKeyChord(KeyEvent.KEYCODE_FORWARD_DEL),
            )
    }
}

enum class StylusBindingType(val label: String) {
    NONE("未设置"),
    TOGGLE_TOOL("切换到工具，再按恢复"),
    LASSO_CONTEXT("套索/菜单（短按/按住）"),
    HOLD_TOOL("按住切换到工具"),
    UNDO("撤销"),
    REDO("重做"),
    NEXT_TOOL("下一个工具"),
    PREVIOUS_TOOL("上一个工具"),
}

data class StylusActionBinding(
    var type: StylusBindingType = StylusBindingType.NONE,
    var toolId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply { put("type", type.name); toolId?.let { put("toolId", it) } }

    companion object {
        fun fromJson(json: JSONObject?): StylusActionBinding {
            if (json == null) return StylusActionBinding()
            val type = runCatching { StylusBindingType.valueOf(json.optString("type")) }.getOrDefault(StylusBindingType.NONE)
            return StylusActionBinding(type, json.optString("toolId").takeIf(String::isNotBlank))
        }
    }
}

data class XiaomiButtonProfile(
    var single: StylusActionBinding = StylusActionBinding(),
    var double: StylusActionBinding = StylusActionBinding(),
    var hold: StylusActionBinding = StylusActionBinding(),
) {
    fun toJson(): JSONObject = JSONObject().apply { put("single", single.toJson()); put("double", double.toJson()); put("hold", hold.toJson()) }

    companion object {
        fun fromJson(json: JSONObject?): XiaomiButtonProfile =
            XiaomiButtonProfile(
                single = StylusActionBinding.fromJson(json?.optJSONObject("single")),
                double = StylusActionBinding.fromJson(json?.optJSONObject("double")),
                hold = StylusActionBinding.fromJson(json?.optJSONObject("hold")),
            )
    }
}

data class XiaomiPenShortcutSettings(
    var pageUp: XiaomiButtonProfile = XiaomiButtonProfile(),
    var pageDown: XiaomiButtonProfile = XiaomiButtonProfile(),
    var doubleTapMillis: Long = 260L,
    var holdMillis: Long = 450L,
) {
    fun profile(key: XiaomiStylusKey): XiaomiButtonProfile = if (key == XiaomiStylusKey.PAGE_UP) pageUp else pageDown

    fun toJson(): String =
        JSONObject().apply {
            put("pageUp", pageUp.toJson())
            put("pageDown", pageDown.toJson())
            put("doubleTapMillis", doubleTapMillis)
            put("holdMillis", holdMillis)
        }.toString()

    fun sanitized(validToolIds: Set<String>): XiaomiPenShortcutSettings {
        doubleTapMillis = doubleTapMillis.coerceIn(150L, 600L)
        holdMillis = holdMillis.coerceIn(200L, 1_200L)
        sequenceOf(pageUp, pageDown).flatMap { sequenceOf(it.single, it.double, it.hold) }.forEach { binding ->
            if (binding.type in setOf(StylusBindingType.TOGGLE_TOOL, StylusBindingType.HOLD_TOOL) && binding.toolId !in validToolIds) {
                binding.type = StylusBindingType.NONE
                binding.toolId = null
            }
        }
        return this
    }

    companion object {
        fun fromJson(raw: String?): XiaomiPenShortcutSettings {
            if (raw.isNullOrBlank()) return XiaomiPenShortcutSettings()
            return runCatching {
                val root = JSONObject(raw)
                XiaomiPenShortcutSettings(
                    pageUp = XiaomiButtonProfile.fromJson(root.optJSONObject("pageUp")),
                    pageDown = XiaomiButtonProfile.fromJson(root.optJSONObject("pageDown")),
                    doubleTapMillis = root.optLong("doubleTapMillis", 260L),
                    holdMillis = root.optLong("holdMillis", 450L),
                )
            }.getOrDefault(XiaomiPenShortcutSettings())
        }
    }
}
