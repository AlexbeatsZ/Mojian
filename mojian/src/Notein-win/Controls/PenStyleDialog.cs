using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using Notein_win.Models;

namespace Notein_win.Controls;

public sealed class PenStyleDialog : Window
{
    private readonly ToolShelf _shelf;
    private readonly ToolPreset _tool;
    private readonly TextBox _content = new() { AcceptsReturn = true, AcceptsTab = true, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        FontFamily = new FontFamily("Consolas, Microsoft YaHei UI"), FontSize = 13, Background = new SolidColorBrush(Color.FromRgb(24, 24, 24)), Foreground = Brushes.Gainsboro,
        BorderBrush = new SolidColorBrush(Color.FromRgb(70, 70, 70)), Padding = new Thickness(14), MaxLength = PenConfiguration.MaximumBytes };
    private readonly TextBlock _error = new() { Foreground = Brushes.Salmon, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) };
    public NamedPenStyle? SavedPreset { get; private set; }
    public string ConfigurationText { get => _content.Text; set => _content.Text = value; }

    public PenStyleDialog(ToolShelf shelf, ToolPreset tool)
    {
        _shelf = shelf; _tool = tool;
        Title = "笔触配置"; Width = 820; Height = 620; MinWidth = 460; MinHeight = 360; MaxHeight = SystemParameters.WorkArea.Height - 48;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; Background = new SolidColorBrush(Color.FromRgb(32, 32, 32));
        var root = new DockPanel { Margin = new Thickness(24) };
        var label = new TextBlock { Text = "笔触配置 JSON", Foreground = Brushes.Gainsboro, Margin = new Thickness(0, 0, 0, 10) }; DockPanel.SetDock(label, Dock.Top); root.Children.Add(label);
        var footer = new DockPanel { Margin = new Thickness(0, 14, 0, 0), LastChildFill = false }; DockPanel.SetDock(footer, Dock.Bottom);
        var back = Button("返回", () => DialogResult = false); back.IsCancel = true; footer.Children.Add(back);
        var import = Button("导入", Import); DockPanel.SetDock(import, Dock.Right); import.Background = new SolidColorBrush(Color.FromRgb(45, 140, 240)); footer.Children.Add(import);
        var choose = Button("选择文件", ChooseFile); DockPanel.SetDock(choose, Dock.Right); footer.Children.Add(choose); root.Children.Add(footer);
        DockPanel.SetDock(_error, Dock.Bottom); root.Children.Add(_error); root.Children.Add(_content); Content = root;
        var preset = shelf.PenStyles.First(p => p.Id == tool.PenStyleId);
        ConfigurationText = (preset.Configuration ?? PenConfiguration.FromStyle(preset)).Text;
        _content.TextChanged += (_, _) => _error.Text = "";
    }

    private static Button Button(string text, Action action)
    {
        var button = new Button { Content = text, MinWidth = 80, Height = 36, Margin = new Thickness(0, 0, 10, 0), Padding = new Thickness(14, 4, 14, 4), Foreground = Brushes.White, Background = new SolidColorBrush(Color.FromRgb(44, 44, 44)) };
        if (Application.Current.TryFindResource("RibbonIconButtonStyle") is Style style) button.Style = style;
        button.Click += (_, _) => action(); return button;
    }
    private void ChooseFile()
    {
        var dialog = new OpenFileDialog { Title = "选择笔触配置", Filter = "笔触配置 (*.json)|*.json|文本文件 (*.txt)|*.txt|所有文件 (*.*)|*.*", Multiselect = false };
        if (dialog.ShowDialog(this) != true) return;
        try { LoadFile(dialog.FileName); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { _error.Text = error.Message; }
    }
    public void LoadFile(string path)
    {
        if (new FileInfo(path).Length > PenConfiguration.MaximumBytes) throw new ArgumentException("配置文件不能超过 128 KiB。");
        ConfigurationText = File.ReadAllText(path); // Choosing a file never imports or changes the shelf.
    }
    public void Import()
    {
        try
        {
            var config = PenConfiguration.Parse(ConfigurationText);
            _ = config.Resolve(config.ValuesFor(_tool));
            SavedPreset = _shelf.ImportPenConfiguration(ConfigurationText);
            _shelf.ApplyPenStyle(_tool, SavedPreset); DialogResult = true;
        }
        catch (ArgumentException error) { _error.Text = error.Message; }
    }
}
