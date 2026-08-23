using System.Windows.Input;

namespace Notein_win.Models;

public enum ShortcutCategory
{
    File,
    Home,
    Insert,
    Draw,
    View,
}

public sealed record ShortcutActionDescriptor(string Id, string Label, ShortcutCategory Category)
{
    public string GestureText { get; set; } = "未设置";
}

public sealed record ShortcutActionGroup(string Label, IReadOnlyList<ShortcutActionDescriptor> Actions);

public readonly record struct ShortcutGesture(Key Key, ModifierKeys Modifiers)
{
    public bool IsValid => Key is not Key.None and not Key.LeftCtrl and not Key.RightCtrl
        and not Key.LeftAlt and not Key.RightAlt and not Key.LeftShift and not Key.RightShift
        and not Key.LWin and not Key.RWin;

    public string Serialize() => $"{(int)Modifiers}:{(int)Key}";

    public string DisplayText
    {
        get
        {
            var parts = new List<string>();
            if (Modifiers.HasFlag(ModifierKeys.Control)) parts.Add("Ctrl");
            if (Modifiers.HasFlag(ModifierKeys.Alt)) parts.Add("Alt");
            if (Modifiers.HasFlag(ModifierKeys.Shift)) parts.Add("Shift");
            if (Modifiers.HasFlag(ModifierKeys.Windows)) parts.Add("Win");
            parts.Add(Key switch
            {
                Key.OemPlus => "+",
                Key.Add => "+",
                Key.OemMinus => "-",
                Key.Subtract => "-",
                Key.Next => "PageDown",
                Key.Prior => "PageUp",
                Key.D0 when Modifiers.HasFlag(ModifierKeys.Control) => "0",
                _ => Key.ToString(),
            });
            return string.Join(" + ", parts);
        }
    }

    public static bool TryParse(string? value, out ShortcutGesture gesture)
    {
        gesture = default;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var parts = value.Split(':');
        if (parts.Length != 2 || !int.TryParse(parts[0], out var modifiers) || !int.TryParse(parts[1], out var key)) return false;
        gesture = new ShortcutGesture((Key)key, (ModifierKeys)modifiers);
        return gesture.IsValid;
    }

    public static ShortcutGesture FromEvent(KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        var modifiers = Keyboard.Modifiers;
        if (key == Key.OemPlus) modifiers &= ~ModifierKeys.Shift;
        return new ShortcutGesture(key, modifiers);
    }
}

public static class ShortcutActionCatalog
{
    public static IReadOnlyList<ShortcutActionDescriptor> Build(IEnumerable<ToolPreset> tools)
    {
        var result = new List<ShortcutActionDescriptor>
        {
            new("file:backstage", "打开文件页", ShortcutCategory.File),
            new("file:save", "保存", ShortcutCategory.File),
            new("file:export-page-pdf", "导出当前页面 PDF", ShortcutCategory.File),
            new("file:export-notebook", "导出当前笔记本 .mjn", ShortcutCategory.File),

            new("home:paste", "粘贴", ShortcutCategory.Home),
            new("home:cut", "剪切", ShortcutCategory.Home),
            new("home:copy", "复制", ShortcutCategory.Home),
            new("home:format-painter", "格式刷", ShortcutCategory.Home),
            new("home:bold", "加粗", ShortcutCategory.Home),
            new("home:italic", "倾斜", ShortcutCategory.Home),
            new("home:underline", "下划线", ShortcutCategory.Home),
            new("home:strikethrough", "删除线", ShortcutCategory.Home),
            new("home:subscript", "下标", ShortcutCategory.Home),
            new("home:clear-formatting", "清除所有格式", ShortcutCategory.Home),
            new("home:bullets", "项目符号", ShortcutCategory.Home),
            new("home:numbering", "编号", ShortcutCategory.Home),
            new("home:indent-decrease", "减少缩进", ShortcutCategory.Home),
            new("home:indent-increase", "增加缩进", ShortcutCategory.Home),
            new("home:align-left", "左对齐", ShortcutCategory.Home),
            new("home:align-center", "居中", ShortcutCategory.Home),
            new("home:align-right", "右对齐", ShortcutCategory.Home),
            new("home:tag-important", "放置重要标记", ShortcutCategory.Home),
            new("home:tag-question", "放置问题标记", ShortcutCategory.Home),
            new("home:find-tags", "查找标记", ShortcutCategory.Home),

            new("insert:table", "插入表格", ShortcutCategory.Insert),
            new("insert:attachment", "插入附件", ShortcutCategory.Insert),
            new("insert:pdf", "插入 PDF 打印输出", ShortcutCategory.Insert),
            new("insert:spreadsheet", "插入电子表格", ShortcutCategory.Insert),
            new("insert:image", "插入图片", ShortcutCategory.Insert),
            new("insert:sticker", "插入贴纸", ShortcutCategory.Insert),
            new("insert:link", "插入笔记链接", ShortcutCategory.Insert),

            new("draw:undo", "撤销", ShortcutCategory.Draw),
            new("draw:redo", "重做", ShortcutCategory.Draw),
            new("draw:cursor", "键入文本", ShortcutCategory.Draw),
            new("draw:previous-tool", "上一个绘图工具", ShortcutCategory.Draw),
            new("draw:next-tool", "下一个绘图工具", ShortcutCategory.Draw),
            new("draw:pan", "全景手柄", ShortcutCategory.Draw),
            new("draw:insert-space", "插入空白空间", ShortcutCategory.Draw),
            new("draw:delete-selection", "删除选中内容", ShortcutCategory.Draw),
            new("draw:clear", "清空页面", ShortcutCategory.Draw),
            new("draw:background-blank", "空白背景", ShortcutCategory.Draw),
            new("draw:background-grid", "标准网格", ShortcutCategory.Draw),
            new("draw:background-ruled", "标准横线", ShortcutCategory.Draw),
            new("draw:background-dots", "标准点阵", ShortcutCategory.Draw),

            new("view:search", "搜索", ShortcutCategory.View),
            new("view:normal-page", "普通视图", ShortcutCategory.View),
            new("view:full-page", "全页视图", ShortcutCategory.View),
            new("view:split", "切换拆分视图", ShortcutCategory.View),
            new("view:zoom-in", "放大", ShortcutCategory.View),
            new("view:zoom-out", "缩小", ShortcutCategory.View),
            new("view:zoom-reset", "缩放到 100%", ShortcutCategory.View),
            new("view:fit-width", "适应页宽", ShortcutCategory.View),
            new("view:toggle-title", "显示或隐藏页面标题", ShortcutCategory.View),
            new("view:toggle-theme", "切换页面明暗", ShortcutCategory.View),
            new("view:previous-page", "上一个页面", ShortcutCategory.View),
            new("view:next-page", "下一个页面", ShortcutCategory.View),
        };

        result.AddRange(tools.Select(tool => new ShortcutActionDescriptor($"tool:{tool.Id}", $"切换到：{tool.DisplayName}", ShortcutCategory.Draw)));
        return result;
    }

    public static Dictionary<string, string> DefaultBindings() => new(StringComparer.Ordinal)
    {
        ["file:save"] = new ShortcutGesture(Key.S, ModifierKeys.Control).Serialize(),
        ["home:paste"] = new ShortcutGesture(Key.V, ModifierKeys.Control).Serialize(),
        ["home:cut"] = new ShortcutGesture(Key.X, ModifierKeys.Control).Serialize(),
        ["home:copy"] = new ShortcutGesture(Key.C, ModifierKeys.Control).Serialize(),
        ["home:bold"] = new ShortcutGesture(Key.B, ModifierKeys.Control).Serialize(),
        ["home:italic"] = new ShortcutGesture(Key.I, ModifierKeys.Control).Serialize(),
        ["home:underline"] = new ShortcutGesture(Key.U, ModifierKeys.Control).Serialize(),
        ["draw:undo"] = new ShortcutGesture(Key.Z, ModifierKeys.Control).Serialize(),
        ["draw:redo"] = new ShortcutGesture(Key.Y, ModifierKeys.Control).Serialize(),
        ["draw:delete-selection"] = new ShortcutGesture(Key.Delete, ModifierKeys.None).Serialize(),
        ["view:search"] = new ShortcutGesture(Key.F, ModifierKeys.Control).Serialize(),
        ["view:full-page"] = new ShortcutGesture(Key.F11, ModifierKeys.None).Serialize(),
        ["view:zoom-in"] = new ShortcutGesture(Key.OemPlus, ModifierKeys.Control).Serialize(),
        ["view:zoom-out"] = new ShortcutGesture(Key.OemMinus, ModifierKeys.Control).Serialize(),
        ["view:zoom-reset"] = new ShortcutGesture(Key.D0, ModifierKeys.Control).Serialize(),
        ["view:previous-page"] = new ShortcutGesture(Key.Prior, ModifierKeys.Control).Serialize(),
        ["view:next-page"] = new ShortcutGesture(Key.Next, ModifierKeys.Control).Serialize(),
    };
}
