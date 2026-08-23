using Microsoft.Win32;
using Notein_win.Models;
using System.Windows;
using System.Windows.Controls;

namespace Notein_win.Controls;

public enum PageCreationMode
{
    Page,
    Pdf,
}

public partial class PageCreationDialog : Window
{
    public PageCreationMode CreationMode { get; private set; }
    public string PageTitle { get; private set; } = "无标题页";
    public PageAppearance PageAppearance { get; private set; } = new();
    public string PdfPath { get; private set; } = string.Empty;

    public PageCreationDialog(PageAppearance initialAppearance)
    {
        InitializeComponent();
        PageAppearance = initialAppearance.Clone();
        SelectByTag(PaperPicker, initialAppearance.PaperKind == PaperKind.A4 ? "A4" : "Infinite");
        SelectByTag(BackgroundPicker, initialAppearance.Background.ToString());
        SelectByTag(ColorPicker, $"{initialAppearance.Theme}|{initialAppearance.PageColor}");
    }

    private void OnBrowsePdf(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择 PDF", Filter = "PDF 文件 (*.pdf)|*.pdf", Multiselect = false };
        if (dialog.ShowDialog(this) == true) PdfPathInput.Text = dialog.FileName;
    }

    private void OnConfirm(object sender, RoutedEventArgs e)
    {
        if (ModeTabs.SelectedIndex == 1)
        {
            if (string.IsNullOrWhiteSpace(PdfPathInput.Text))
            {
                MessageBox.Show(this, "请先选择一个 PDF 文件。", "添加页面", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            CreationMode = PageCreationMode.Pdf;
            PdfPath = PdfPathInput.Text;
            DialogResult = true;
            return;
        }

        CreationMode = PageCreationMode.Page;
        PageTitle = string.IsNullOrWhiteSpace(TitleInput.Text) ? "无标题页" : TitleInput.Text.Trim();
        var paper = TagOf(PaperPicker);
        var background = TagOf(BackgroundPicker);
        var color = TagOf(ColorPicker).Split('|', 2);
        PageAppearance = new PageAppearance
        {
            PaperKind = Enum.TryParse<PaperKind>(paper, out var paperKind) ? paperKind : PaperKind.Infinite,
            Background = Enum.TryParse<CanvasBackgroundFormat>(background, out var format) ? format : CanvasBackgroundFormat.GridStandard,
            Theme = color.Length > 0 && Enum.TryParse<PageTheme>(color[0], out var theme) ? theme : PageTheme.Dark,
            PageColor = color.Length > 1 ? color[1] : "#1B1B1B",
            ShowTitle = true,
            SheetCount = 1,
        };
        DialogResult = true;
    }

    private static string TagOf(ComboBox comboBox) => (comboBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? string.Empty;

    private static void SelectByTag(ComboBox comboBox, string tag)
    {
        foreach (var item in comboBox.Items.OfType<ComboBoxItem>())
        {
            if (!string.Equals(item.Tag?.ToString(), tag, StringComparison.OrdinalIgnoreCase)) continue;
            comboBox.SelectedItem = item;
            return;
        }
    }
}
