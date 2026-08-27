namespace Notein_win.Models;

public enum RibbonTab
{
    File,
    Home,
    Insert,
    Draw,
    View,
}

public enum HomeCommand
{
    Paste,
    Cut,
    Copy,
    FormatPainter,
    FontFamily,
    FontSize,
    Bold,
    Italic,
    Underline,
    Strikethrough,
    FontColor,
    Highlight,
    Subscript,
    ClearFormatting,
    Bullets,
    Numbering,
    IndentDecrease,
    IndentIncrease,
    AlignLeft,
    AlignCenter,
    AlignRight,
    LineSpacing,
    Tag,
    FindTags,
    Styles,
}

public static class HomeCommandExtensions
{
    public static string GetId(this HomeCommand cmd) => cmd switch
    {
        HomeCommand.Paste => "paste",
        HomeCommand.Cut => "cut",
        HomeCommand.Copy => "copy",
        HomeCommand.FormatPainter => "format-painter",
        HomeCommand.FontFamily => "font-family",
        HomeCommand.FontSize => "font-size",
        HomeCommand.Bold => "bold",
        HomeCommand.Italic => "italic",
        HomeCommand.Underline => "underline",
        HomeCommand.Strikethrough => "strikethrough",
        HomeCommand.FontColor => "font-color",
        HomeCommand.Highlight => "highlight",
        HomeCommand.Subscript => "subscript",
        HomeCommand.ClearFormatting => "clear-formatting",
        HomeCommand.Bullets => "bullets",
        HomeCommand.Numbering => "numbering",
        HomeCommand.IndentDecrease => "indent-decrease",
        HomeCommand.IndentIncrease => "indent-increase",
        HomeCommand.AlignLeft => "align-left",
        HomeCommand.AlignCenter => "align-center",
        HomeCommand.AlignRight => "align-right",
        HomeCommand.LineSpacing => "line-spacing",
        HomeCommand.Tag => "tag",
        HomeCommand.FindTags => "find-tags",
        HomeCommand.Styles => "styles",
        _ => cmd.ToString().ToLowerInvariant()
    };

    public static string GetLabel(this HomeCommand cmd) => cmd switch
    {
        HomeCommand.Paste => "粘贴",
        HomeCommand.Cut => "剪切",
        HomeCommand.Copy => "复制",
        HomeCommand.FormatPainter => "格式刷",
        HomeCommand.FontFamily => "字体",
        HomeCommand.FontSize => "字号",
        HomeCommand.Bold => "加粗",
        HomeCommand.Italic => "倾斜",
        HomeCommand.Underline => "下划线",
        HomeCommand.Strikethrough => "删除线",
        HomeCommand.FontColor => "字体颜色",
        HomeCommand.Highlight => "文本突出显示",
        HomeCommand.Subscript => "下标",
        HomeCommand.ClearFormatting => "清除所有格式",
        HomeCommand.Bullets => "项目符号",
        HomeCommand.Numbering => "编号",
        HomeCommand.IndentDecrease => "减少缩进",
        HomeCommand.IndentIncrease => "增加缩进",
        HomeCommand.AlignLeft => "左对齐",
        HomeCommand.AlignCenter => "居中",
        HomeCommand.AlignRight => "右对齐",
        HomeCommand.LineSpacing => "行距",
        HomeCommand.Tag => "标记",
        HomeCommand.FindTags => "查找标记",
        HomeCommand.Styles => "样式",
        _ => cmd.ToString()
    };

    public static string GetIconKey(this HomeCommand cmd) => cmd switch
    {
        HomeCommand.Paste => "clipboard_paste",
        HomeCommand.Cut => "cut",
        HomeCommand.Copy => "copy",
        HomeCommand.FormatPainter => "paint_brush",
        HomeCommand.FontFamily => "text_font",
        HomeCommand.FontSize => "text_font_size",
        HomeCommand.Bold => "text_bold",
        HomeCommand.Italic => "text_italic",
        HomeCommand.Underline => "text_underline",
        HomeCommand.Strikethrough => "text_strikethrough",
        HomeCommand.FontColor => "text_color",
        HomeCommand.Highlight => "highlight_text",
        HomeCommand.Subscript => "text_subscript",
        HomeCommand.ClearFormatting => "text_clear_formatting",
        HomeCommand.Bullets => "text_bullet_list_ltr",
        HomeCommand.Numbering => "text_number_list_ltr",
        HomeCommand.IndentDecrease => "text_indent_decrease_ltr",
        HomeCommand.IndentIncrease => "text_indent_increase_ltr",
        HomeCommand.AlignLeft => "text_align_left",
        HomeCommand.AlignCenter => "text_align_center",
        HomeCommand.AlignRight => "text_align_right",
        HomeCommand.LineSpacing => "text_line_spacing",
        HomeCommand.Tag => "tag",
        HomeCommand.FindTags => "tag_search",
        HomeCommand.Styles => "style_guide",
        _ => "cursor",
    };
}

public class FullPageToolbarConfig
{
    private readonly List<string> _mutableIds;

    public IReadOnlyList<string> Ids => _mutableIds;

    public FullPageToolbarConfig(IEnumerable<string>? initialIds = null)
    {
        _mutableIds = (initialIds ?? DefaultIds).Distinct().ToList();
    }

    public bool Contains(string id) => _mutableIds.Contains(id);

    public bool Add(string id)
    {
        if (_mutableIds.Contains(id)) return false;
        _mutableIds.Add(id);
        return true;
    }

    public bool Remove(string id) => _mutableIds.Remove(id);

    public static readonly IReadOnlyList<string> DefaultIds = new List<string>
    {
        "draw:undo",
        "draw:redo",
        "tool:lasso-freeform",
        "tool:eraser-stroke",
        "tool:highlighter-blue",
        "tool:pen-black",
        "tool:pen-blue",
        "tool:pen-red",
        "draw:add",
        "draw:pan",
        "draw:clear",
    };
}

public record FullPageToolDescriptor(
    string Id,
    string Label,
    string IconKey,
    string TintHex = "#F4F4F4",
    bool PreserveColors = false,
    bool IsSelected = false);
