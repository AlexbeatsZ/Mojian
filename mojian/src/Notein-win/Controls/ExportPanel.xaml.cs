using System.Windows;
using System.Windows.Controls;
using Notein_win.Models;

namespace Notein_win.Controls;

public partial class ExportPanel : UserControl
{
    public event Action<ExportOptions, bool, bool>? Requested;
    public Func<ExportOptions, (string Target, string Summary)>? Describe { get; set; }
    public Func<ExportOptions, (bool HasDarkPages, IReadOnlyList<ExportImageChoice> Images)>? PrintImages { get; set; }
    private readonly Dictionary<string, bool> _imageSelection = [];
    private IReadOnlyList<ExportImageChoice> _images = [];
    private bool _refreshing;
    private bool _printing;
    public bool Printing { get => _printing; set { _printing = value; Refresh(); } }
    public ExportPanel() { InitializeComponent(); Refresh(); Loaded += (_, _) => { DecorateFormats(); Refresh(); }; IsVisibleChanged += (_, _) => { if (IsVisible) Refresh(); }; }
    private bool _decorated;
    private void DecorateFormats()
    {
        if (_decorated) return; _decorated = true;
        var keys = new[] { "document_pdf", "notebook", "image", "document_page" };
        for (var i = 0; i < FormatList.Items.Count; i++)
        {
            var item = (ListBoxItem)FormatList.Items[i];
            var labels = (UIElement)item.Content;
            item.Content = null;
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(new System.Windows.Shapes.Path { Data = (System.Windows.Media.Geometry)FindResource("Fluent_" + keys[i]),
                Fill = (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString("#CBB7DF")!,
                Width = 26, Height = 30, Stretch = System.Windows.Media.Stretch.Uniform, Margin = new Thickness(0, 0, 14, 0) });
            row.Children.Add(labels); item.Content = row;
        }
    }
    public ExportOptions Options => new((ExportScope)Math.Max(0, ScopeList.SelectedIndex),
        Printing ? ExportFormat.Pdf : (ExportFormat)Math.Max(0, FormatList.SelectedIndex),
        (ExportPaper)Math.Max(0, PaperList.SelectedIndex), OrientationList.SelectedIndex == 1,
        new[] { 0d, 6, 12, 20 }[Math.Max(0, MarginList.SelectedIndex)], TrimCheck.IsChecked == true, RangeText.Text, Printing,
        _images.Where(i => _imageSelection.GetValueOrDefault(i.Id, i.DisplayInverted)).Select(i => i.Id).ToHashSet(), HideFormatCheck.IsChecked == true);
    public void Refresh()
    {
        if (Summary == null || _refreshing) return;
        _refreshing = true;
        Heading.Text = Printing ? "打印" : "导出";
        ActionButton.Content = Printing ? "打印…" : "导出…";
        FormatPanel.Visibility = Printing ? Visibility.Collapsed : Visibility.Visible;
        LayoutHeading.Text = Printing ? "2. 分页设置" : "3. 分页设置";
        var package = Options.Format == ExportFormat.Package;
        LayoutPanel.Visibility = package ? Visibility.Collapsed : Visibility.Visible;
        PreviewButton.Visibility = package ? Visibility.Collapsed : Visibility.Visible;
        HideFormatCheck.Visibility = Printing ? Visibility.Visible : Visibility.Collapsed;
        try
        {
            var images = Printing ? PrintImages?.Invoke(Options) : null;
            _images = images?.Images ?? [];
            PrintColorPanel.Visibility = images?.HasDarkPages == true ? Visibility.Visible : Visibility.Collapsed;
            PrintImageList.Children.Clear();
            foreach (var image in _images)
            {
                var check = new CheckBox { Content = image.Label, IsChecked = _imageSelection.GetValueOrDefault(image.Id, image.DisplayInverted) };
                check.Checked += (_, _) => { _imageSelection[image.Id] = true; };
                check.Unchecked += (_, _) => { _imageSelection[image.Id] = false; };
                PrintImageList.Children.Add(check);
            }
            var description = Describe?.Invoke(Options);
            TargetText.Text = description?.Target ?? "";
            Summary.Text = description?.Summary ?? "每张纸独立输出，内容保持原有比例";
            ActionButton.IsEnabled = PreviewButton.IsEnabled = true;
        }
        catch (Exception e) { Summary.Text = e.Message; ActionButton.IsEnabled = PreviewButton.IsEnabled = false; }
        finally { _refreshing = false; }
    }
    private void OnChanged(object sender, RoutedEventArgs e) => Refresh();
    private void OnAction(object sender, RoutedEventArgs e) => Requested?.Invoke(Options, Printing, false);
    private void OnPreview(object sender, RoutedEventArgs e) => Requested?.Invoke(Options, Printing, true);
}
