package com.meta.inklab

enum class HomeCommand(
    val id: String,
    val label: String,
    val icon: String,
) {
    PASTE("paste", "粘贴", "clipboard_paste"),
    CUT("cut", "剪切", "cut"),
    COPY("copy", "复制", "copy"),
    COPY_SVG("copy-svg", "复制为 SVG", "copy"),
    COPY_PNG("copy-png", "复制为 PNG", "copy"),
    FORMAT_PAINTER("format-painter", "格式刷", "paint_brush"),
    FONT_FAMILY("font-family", "字体", "text_font"),
    FONT_SIZE("font-size", "字号", "text_font_size"),
    BOLD("bold", "加粗", "text_bold"),
    ITALIC("italic", "倾斜", "text_italic"),
    UNDERLINE("underline", "下划线", "text_underline"),
    STRIKETHROUGH("strikethrough", "删除线", "text_strikethrough"),
    FONT_COLOR("font-color", "字体颜色", "text_color"),
    HIGHLIGHT("highlight", "文本突出显示", "highlight_text"),
    SUBSCRIPT("subscript", "下标", "text_subscript"),
    CLEAR_FORMATTING("clear-formatting", "清除所有格式", "text_clear_formatting"),
    BULLETS("bullets", "项目符号", "text_bullet_list_ltr"),
    NUMBERING("numbering", "编号", "text_number_list_ltr"),
    INDENT_DECREASE("indent-decrease", "减少缩进", "text_indent_decrease_ltr"),
    INDENT_INCREASE("indent-increase", "增加缩进", "text_indent_increase_ltr"),
    ALIGN_LEFT("align-left", "左对齐", "text_align_left"),
    ALIGN_CENTER("align-center", "居中", "text_align_center"),
    ALIGN_RIGHT("align-right", "右对齐", "text_align_right"),
    LINE_SPACING("line-spacing", "行距", "text_line_spacing"),
    TAG("tag", "标记", "tag"),
    FIND_TAGS("find-tags", "查找标记", "tag_search"),
    STYLES("styles", "样式", "style_guide"),
    ;

    companion object {
        fun fromId(id: String): HomeCommand? = entries.firstOrNull { it.id == id }
    }
}

enum class CanvasTagKind(val label: String) {
    IMPORTANT("重要"),
    QUESTION("问题"),
}

/** Ordered set used by the full-page, horizontally scrollable quick toolbar. */
class FullPageToolbarConfig(initialIds: List<String> = DEFAULT_IDS) {
    private val mutableIds = initialIds.distinct().toMutableList()

    val ids: List<String>
        get() = mutableIds

    fun contains(id: String): Boolean = id in mutableIds

    fun add(id: String): Boolean {
        if (id in mutableIds) return false
        mutableIds.add(id)
        return true
    }

    fun remove(id: String): Boolean = mutableIds.remove(id)

    companion object {
        val DEFAULT_IDS =
            listOf(
                "draw:undo",
                "draw:redo",
                "tool:lasso-freeform",
                "tool:eraser-stroke",
                "tool:highlighter-blue",
                "tool:pen-black",
                "tool:pen-blue",
                "tool:pen-red",
                "draw:add",
                "tool:shape-default",
                "draw:pan",
                "draw:clear",
            )
    }
}

data class FullPageToolDescriptor(
    val id: String,
    val label: String,
    val icon: String,
    val redAccent: Boolean = false,
)
