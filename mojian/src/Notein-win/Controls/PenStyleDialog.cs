using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Controls;

public sealed class PenStyleDialog : Window
{
    private readonly ToolShelf _shelf;
    private readonly ToolPreset _tool;
    private readonly ComboBox _presets = new() { MinWidth = 180, Height = 30, DisplayMemberPath = "Name" };
    private readonly TextBox _name = new() { Height = 30, MaxLength = 40, VerticalContentAlignment = VerticalAlignment.Center };
    private readonly StackPanel _parameters = new();
    private readonly TextBlock _error = new() { Foreground = Brushes.Salmon, TextWrapping = TextWrapping.Wrap };
    private PenStyle _draft;
    private string? _editingId;
    public NamedPenStyle? SavedPreset { get; private set; }
    private static readonly Brush Text = new SolidColorBrush(Color.FromRgb(238, 238, 240));

    public PenStyleDialog(ToolShelf shelf, ToolPreset tool)
    {
        _shelf = shelf; _tool = tool; _draft = shelf.EffectivePenStyle(tool);
        Title = "笔触配置"; Width = 530; Height = 750; MaxHeight = SystemParameters.WorkArea.Height - 48;
        MinWidth = 440; MinHeight = 400; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Background = new SolidColorBrush(Color.FromRgb(35, 35, 35)); Foreground = Text;
        FontFamily = new FontFamily("Segoe UI, Microsoft YaHei UI"); FontSize = 13;
        if (Application.Current.TryFindResource("ShapeChooserStyle") is Style chooserStyle) _presets.Style = chooserStyle;
        _name.Background = new SolidColorBrush(Color.FromRgb(48, 48, 48)); _name.Foreground = Text;
        _name.BorderBrush = new SolidColorBrush(Color.FromRgb(74, 74, 74)); _name.Padding = new Thickness(7, 0, 7, 0);
        var root = new DockPanel { Margin = new Thickness(18) };
        var footer = new StackPanel(); DockPanel.SetDock(footer, Dock.Bottom);
        footer.Children.Add(_error);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        var cancel = Button("取消", () => DialogResult = false); cancel.IsCancel = true;
        var save = Button("保存", Save); save.IsDefault = true;
        buttons.Children.Add(cancel); buttons.Children.Add(save); footer.Children.Add(buttons); root.Children.Add(footer);
        var header = new StackPanel { Margin = new Thickness(0, 0, 0, 14) }; DockPanel.SetDock(header, Dock.Top);
        var row = new DockPanel(); var create = Button("新建预设", NewPreset); DockPanel.SetDock(create, Dock.Right); row.Children.Add(create);
        row.Children.Add(_presets); header.Children.Add(row);
        header.Children.Add(new TextBlock { Text = "名称", Margin = new Thickness(0, 12, 0, 5), Foreground = Text }); header.Children.Add(_name);
        root.Children.Add(header);
        root.Children.Add(new ScrollViewer { Content = _parameters, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = root;
        _presets.ItemsSource = shelf.PenStyles;
        _presets.SelectionChanged += (_, _) => { if (_presets.SelectedItem is NamedPenStyle style) EditPreset(style); };
        _presets.SelectedItem = shelf.PenStyles.FirstOrDefault(p => p.Id == tool.PenStyleId) ?? shelf.PenStyles[0];
    }

    private static Button Button(string title, Action action)
    {
        var b = new Button { Content = title, MinWidth = 76, Height = 30, Margin = new Thickness(8, 0, 0, 0), Padding = new Thickness(10, 3, 10, 3) };
        if (Application.Current.TryFindResource("RibbonIconButtonStyle") is Style style) b.Style = style;
        b.Background = new SolidColorBrush(Color.FromRgb(48, 48, 48));
        b.Click += (_, _) => action(); return b;
    }
    public void EditPreset(NamedPenStyle preset)
    {
        _editingId = preset.Id; _name.Text = preset.Name; _draft = preset.Style.Copy(); _error.Text = ""; RenderParameters();
    }
    public void NewPreset()
    {
        _presets.SelectedIndex = -1; _editingId = null; _name.Text = ""; _error.Text = ""; _name.Focus();
    }
    public void Save()
    {
        try
        {
            SavedPreset = _shelf.SavePenStyle(_editingId, _name.Text, _draft);
            _shelf.ApplyPenStyle(_tool, SavedPreset);
            DialogResult = true;
        }
        catch (ArgumentException error) { _error.Text = error.Message; }
    }
    private void RenderParameters()
    {
        _parameters.Children.Clear();
        Curve("压力 → 宽度", _draft.PressureEnabled, v => _draft.PressureEnabled = v, _draft.PressureCurve, 1, "压力 %");
        Curve("速度 → 宽度", _draft.SpeedEnabled, v => _draft.SpeedEnabled = v, _draft.SpeedCurve, 64, "速度 cm/s");
        var direction = Toggle("方向 → 宽度", _draft.DirectionEnabled, v => _draft.DirectionEnabled = v);
        Number(direction, "增粗幅度", _draft.DirectionAmount, 0, 1, v => $"{v * 100:0}%", v => _draft.DirectionAmount = v);
        Taper("起笔收尖", true); Taper("收笔收尖", false);
        var stability = Section("稳定性");
        Number(stability, "位置与压力平滑", _draft.StabilityPercent, 0, 100, v => $"{v:0}%", v => _draft.StabilityPercent = (int)Math.Round(v), 1);
        var advanced = new StackPanel();
        var tilt = Toggle("倾角 → 宽度", _draft.TiltEnabled, v => _draft.TiltEnabled = v, advanced);
        Number(tilt, "倾斜 60° 时宽度", _draft.TiltWidth, .1, 3, v => $"{v * 100:0}%", v => _draft.TiltWidth = v);
        var turn = Toggle("转弯增粗", _draft.TurnEnabled, v => _draft.TurnEnabled = v, advanced);
        Number(turn, "增粗幅度", _draft.TurnAmount, 0, 1, v => $"{v * 100:0}%", v => _draft.TurnAmount = v);
        var response = new StackPanel(); advanced.Children.Add(response);
        Number(response, "压力响应平滑", _draft.PressureResponseMs, 0, 100, v => $"{v:0} ms", v => _draft.PressureResponseMs = (int)Math.Round(v), 1);
        Number(response, "速度响应平滑", _draft.SpeedResponseMs, 0, 100, v => $"{v:0} ms", v => _draft.SpeedResponseMs = (int)Math.Round(v), 1);
        Number(response, "方向响应平滑", _draft.DirectionResponseMs, 0, 100, v => $"{v:0} ms", v => _draft.DirectionResponseMs = (int)Math.Round(v), 1);
        _parameters.Children.Add(new Expander { Header = "高级", Content = advanced, Foreground = Text, Margin = new Thickness(0, 12, 0, 8) });
    }
    private StackPanel Section(string title, StackPanel? parent = null)
    {
        var panel = new StackPanel { Margin = new Thickness(0, 6, 12, 8) };
        panel.Children.Add(new TextBlock { Text = title, FontWeight = FontWeights.SemiBold, Foreground = Text, Margin = new Thickness(0, 6, 0, 7) });
        (parent ?? _parameters).Children.Add(panel); return panel;
    }
    private StackPanel Toggle(string title, bool enabled, Action<bool> change, StackPanel? parent = null)
    {
        var section = new StackPanel { Margin = new Thickness(0, 6, 12, 8) };
        var check = new CheckBox { Content = title, IsChecked = enabled, Foreground = Text, Margin = new Thickness(0, 6, 0, 7) };
        var body = new StackPanel { IsEnabled = enabled };
        check.Click += (_, _) => { body.IsEnabled = check.IsChecked == true; change(body.IsEnabled); };
        section.Children.Add(check); section.Children.Add(body); (parent ?? _parameters).Children.Add(section); return body;
    }
    private void Curve(string title, bool enabled, Action<bool> toggle, List<CurvePoint> points, double maxX, string axis)
    {
        var body = Toggle(title, enabled, toggle);
        var editor = new PenCurveEditor { Points = points, MaximumX = maxX, AxisLabel = axis };
        body.Children.Add(editor);
        var tools = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 4, 0, 0) };
        tools.Children.Add(Button("删除控制点", editor.DeleteSelected)); body.Children.Add(tools);
    }
    private void Taper(string title, bool start)
    {
        var body = Toggle(title, start ? _draft.StartEnabled : _draft.EndEnabled, v => { if (start) _draft.StartEnabled = v; else _draft.EndEnabled = v; });
        Number(body, "长度", start ? _draft.StartLengthMm : _draft.EndLengthMm, .1, 20, v => $"{v:0.0} mm", v => { if (start) _draft.StartLengthMm = v; else _draft.EndLengthMm = v; }, .1);
        Number(body, "尖端宽度", start ? _draft.StartMinimum : _draft.EndMinimum, 0, 1, v => $"{v * 100:0}%", v => { if (start) _draft.StartMinimum = v; else _draft.EndMinimum = v; });
    }
    private static void Number(StackPanel parent, string title, double value, double min, double max, Func<double, string> label, Action<double> change, double step = .01)
    {
        var row = new DockPanel { Margin = new Thickness(0, 7, 0, 0) };
        var current = new TextBlock { Text = label(value), Foreground = Text }; DockPanel.SetDock(current, Dock.Right); row.Children.Add(current);
        row.Children.Add(new TextBlock { Text = title, Foreground = Text }); parent.Children.Add(row);
        var slider = new Slider { Minimum = min, Maximum = max, Value = value, TickFrequency = step, IsSnapToTickEnabled = true, Margin = new Thickness(0, 4, 0, 0) };
        slider.ValueChanged += (_, _) => { current.Text = label(slider.Value); change(slider.Value); }; parent.Children.Add(slider);
    }
}
