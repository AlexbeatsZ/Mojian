using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using Microsoft.Win32;
using Notein_win.Models;

namespace Notein_win.Controls;

public enum PageCreationMode
{
    Page,
    Pdf,
}

public sealed class BackgroundFormatOption
{
    public CanvasBackgroundFormat Format { get; init; }
    public string DisplayName { get; init; } = string.Empty;
}

public partial class PageCreationDialog : Window
{
    public PageCreationMode CreationMode { get; private set; }
    public string PageTitle { get; private set; } = "无标题页";
    public PageAppearance PageAppearance { get; private set; } = new();
    public string PdfPath { get; private set; } = string.Empty;

    private static readonly (string Light, string Dark, string Name)[] ThemeColors =
    [
        ("#FAFAFA", "#1B1B1B", "白色"),
        ("#F3E9D2", "#302A21", "米色"),
        ("#E2EDF8", "#1D2B38", "蓝色"),
        ("#E0F1E7", "#1E3028", "绿色"),
        ("#F4E2E8", "#352329", "粉色"),
    ];
    private (string Hex, string Name)[] AvailableColors => ThemeColors.Select(color =>
        (PageAppearance.Theme == PageTheme.Dark ? color.Dark : color.Light, color.Name)).ToArray();

    private static readonly BackgroundFormatOption[] BackgroundOptions =
    [
        new() { Format = CanvasBackgroundFormat.Blank, DisplayName = "空白 (无底纹)" },
        new() { Format = CanvasBackgroundFormat.RuledStandard, DisplayName = "标准横线" },
        new() { Format = CanvasBackgroundFormat.RuledNarrow, DisplayName = "窄行距横线" },
        new() { Format = CanvasBackgroundFormat.RuledWide, DisplayName = "宽行距横线" },
        new() { Format = CanvasBackgroundFormat.RuledClean, DisplayName = "纯横线" },
        new() { Format = CanvasBackgroundFormat.RuledTwoColumnStandard, DisplayName = "标准双栏横线" },
        new() { Format = CanvasBackgroundFormat.RuledTwoColumnNarrow, DisplayName = "窄双栏横线" },
        new() { Format = CanvasBackgroundFormat.RuledTwoColumnWide, DisplayName = "宽双栏横线" },
        new() { Format = CanvasBackgroundFormat.GridStandard, DisplayName = "标准方格" },
        new() { Format = CanvasBackgroundFormat.GridDense, DisplayName = "密集方格" },
        new() { Format = CanvasBackgroundFormat.GridWide, DisplayName = "宽距方格" },
        new() { Format = CanvasBackgroundFormat.DotsStandard, DisplayName = "标准点阵" },
        new() { Format = CanvasBackgroundFormat.DotsDense, DisplayName = "密集点阵" },
        new() { Format = CanvasBackgroundFormat.DotsWide, DisplayName = "宽距点阵" },
    ];

    private string _selectedColorHex = "#1B1B1B";
    private readonly List<Border> _swatchBorders = [];

    public PageCreationDialog(PageAppearance initialAppearance)
    {
        InitializeComponent();
        PageAppearance = initialAppearance.Clone();
        _selectedColorHex = string.IsNullOrWhiteSpace(initialAppearance.PageColor) ? "#1B1B1B" : initialAppearance.PageColor;

        // Populate baseline dropdown
        BackgroundPicker.ItemsSource = BackgroundOptions;
        var selectedBg = BackgroundOptions.FirstOrDefault(b => b.Format == initialAppearance.Background)
            ?? BackgroundOptions.Single(b => b.Format == CanvasBackgroundFormat.GridStandard);
        BackgroundPicker.SelectedItem = selectedBg;

        // Set paper card
        if (initialAppearance.PaperKind == PaperKind.A4)
        {
            CardA4.IsChecked = true;
        }
        else
        {
            CardBoundless.IsChecked = true;
        }

        // Populate color swatches
        BuildColorSwatches();

        // Drag to move window
        MouseDown += (_, e) =>
        {
            if (e.ChangedButton == MouseButton.Left) DragMove();
        };
    }

    private void BuildColorSwatches()
    {
        ColorSwatchesHost.Children.Clear();
        _swatchBorders.Clear();

        foreach (var (hex, name) in AvailableColors)
        {
            var isSelected = string.Equals(hex, _selectedColorHex, StringComparison.OrdinalIgnoreCase);
            
            var container = new Border
            {
                Width = 42,
                Height = 38,
                Margin = new Thickness(0, 0, 4, 0),
                Background = Brushes.Transparent,
                Cursor = Cursors.Hand,
                ToolTip = name,
            };

            var swatch = new Border
            {
                Width = 26,
                Height = 26,
                CornerRadius = new CornerRadius(2),
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex)),
                BorderBrush = isSelected ? (Brush)FindResource("AccentPurple") : (Brush)FindResource("BorderStrong"),
                BorderThickness = new Thickness(isSelected ? 1.5 : 1),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };

            container.Child = swatch;
            var currentHex = hex;
            container.MouseLeftButtonDown += (_, _) =>
            {
                _selectedColorHex = currentHex;
                UpdateSwatchSelection();
            };

            _swatchBorders.Add(swatch);
            ColorSwatchesHost.Children.Add(container);
        }
    }

    private void UpdateSwatchSelection()
    {
        for (var i = 0; i < AvailableColors.Length; i++)
        {
            var isSelected = string.Equals(AvailableColors[i].Hex, _selectedColorHex, StringComparison.OrdinalIgnoreCase);
            _swatchBorders[i].BorderBrush = isSelected ? (Brush)FindResource("AccentPurple") : (Brush)FindResource("BorderStrong");
            _swatchBorders[i].BorderThickness = new Thickness(isSelected ? 1.5 : 1);
        }
    }

    private void OnTabChanged(object sender, RoutedEventArgs e)
    {
        if (TabNormalPage == null || NormalPagePanel == null || PdfPagePanel == null) return;

        if (TabNormalPage.IsChecked == true)
        {
            NormalPagePanel.Visibility = Visibility.Visible;
            PdfPagePanel.Visibility = Visibility.Collapsed;
            ConfirmButton.Content = "创建";
        }
        else
        {
            NormalPagePanel.Visibility = Visibility.Collapsed;
            PdfPagePanel.Visibility = Visibility.Visible;
            ConfirmButton.Content = "选择";
        }
    }

    private void OnPaperCardSelected(object sender, RoutedEventArgs e)
    {
        // RadioButtons automatically update IsChecked
    }

    private void OnBackgroundChanged(object sender, SelectionChangedEventArgs e)
    {
    }

    private void OnBrowsePdf(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择 PDF", Filter = "PDF 文件 (*.pdf)|*.pdf", Multiselect = false };
        if (dialog.ShowDialog(this) == true)
        {
            PdfPathInput.Text = dialog.FileName;
        }
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void OnConfirm(object sender, RoutedEventArgs e)
    {
        if (TabPdfPage.IsChecked == true)
        {
            if (string.IsNullOrWhiteSpace(PdfPathInput.Text) || PdfPathInput.Text == "请选择 PDF 文件...")
            {
                MessageBox.Show(this, "请先选择一个 PDF 文件。", "新建页面", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            CreationMode = PageCreationMode.Pdf;
            PdfPath = PdfPathInput.Text;
            DialogResult = true;
            Close();
            return;
        }

        CreationMode = PageCreationMode.Page;
        PageTitle = "无标题页";
        var isA4 = CardA4.IsChecked == true;
        var selectedBg = (BackgroundPicker.SelectedItem as BackgroundFormatOption)?.Format ?? CanvasBackgroundFormat.GridStandard;

        PageAppearance = new PageAppearance
        {
            PaperKind = isA4 ? PaperKind.A4 : PaperKind.Infinite,
            Background = selectedBg,
            Theme = PageAppearance.Theme,
            PageColor = _selectedColorHex,
            ShowTitle = true,
            SheetCount = 1,
        };
        DialogResult = true;
        Close();
    }
}
