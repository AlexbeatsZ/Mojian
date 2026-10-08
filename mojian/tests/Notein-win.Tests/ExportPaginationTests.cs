using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class ExportPaginationTests
{
    [Fact]
    public void A4UsesOccupiedSheetsInsteadOfViewportGrowthAndPreservesGapContent()
    {
        var page = new NotePage { Appearance = new PageAppearance { PaperKind = PaperKind.A4, SheetCount = 9, Width = 793.7, Height = 1122.5 } };
        var document = new PageDocument { Objects = [new PageObject { X = -10, Y = 1100, Width = 60, Height = 60 }] };
        var sheets = ExportLayout.Plan(page, document, new ExportOptions());
        Assert.Equal(2, sheets.Count);
        Assert.Equal(-10, sheets[0].Source.Left);
        Assert.Equal(1154.5, sheets[0].Source.Bottom);
        Assert.Equal(sheets[0].Source.Bottom, sheets[1].Source.Top);
        Assert.Equal(9, ExportLayout.Plan(page, document, new ExportOptions(TrimTrailingBlank: false)).Count);
        Assert.Equal(9, page.Appearance.SheetCount);
    }

    [Fact]
    public void BoundlessTallContentProducesFixedMediaBoxesWithoutAnisotropicScaling()
    {
        var page = new NotePage();
        var document = new PageDocument { Objects = [new PageObject { X = -40, Y = -70, Width = 2000, Height = 6000 }] };
        var sheets = ExportLayout.Plan(page, document, new ExportOptions());
        Assert.True(sheets.Count > 1);
        Assert.All(sheets, s => { Assert.Equal(210 * 96 / 25.4, s.Width, 6); Assert.Equal(297 * 96 / 25.4, s.Height, 6); });
        Assert.Equal(-70, sheets[0].Source.Top);
        for (var i = 1; i < sheets.Count; i++) Assert.Equal(sheets[i - 1].Source.Bottom, sheets[i].Source.Top, 6);
        Assert.Equal(5930, sheets[^1].Source.Bottom, 6);
        Assert.Equal(sheets[0].Scale, sheets[^1].Scale);
        Assert.Single(ExportLayout.Plan(page, new PageDocument(), new ExportOptions()));
    }

    [Fact]
    public void OriginalPdfDimensionsAndExplicitLandscapePaperAreSeparate()
    {
        var page = new NotePage { PdfWidthPoints = 612.3, PdfHeightPoints = 792.7,
            Appearance = new PageAppearance { PaperKind = PaperKind.Pdf, Width = 816.4, Height = 1056.933333 } };
        var original = Assert.Single(ExportLayout.Plan(page, new PageDocument(), new ExportOptions()));
        Assert.Equal(612.3, original.Width * .75, 6); Assert.Equal(792.7, original.Height * .75, 6);
        Assert.Equal(0, original.Margin);
        var landscape = ExportLayout.Plan(page, new PageDocument(), new ExportOptions(Paper: ExportPaper.Letter, Landscape: true))[0];
        Assert.Equal(792, landscape.Width * .75, 6); Assert.Equal(612, landscape.Height * .75, 6);
    }

    [Fact]
    public void OutputRangeIsValidatedAgainstPhysicalPagesAndSortedWithoutDuplicates()
    {
        Assert.Equal(new[] { 0, 1, 2, 4 }, ExportLayout.SelectPages("5,1-3,2", 6));
        Assert.Throws<ArgumentException>(() => ExportLayout.SelectPages("0", 3));
        Assert.Throws<ArgumentException>(() => ExportLayout.SelectPages("1-4", 3));
        Assert.Throws<ArgumentException>(() => ExportLayout.SelectPages("2-1", 3));
        Assert.Throws<ArgumentException>(() => ExportLayout.SelectPages("1,", 3));
    }

    [Fact]
    public async Task NativePdfPrintAndPngShareTheTwoPagePlanAndKeepVectorInk()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-export-pagination", "qa");
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "paginated-vector.pdf");
        Sta(() =>
        {
            var page = new NotePage { Appearance = new PageAppearance { PaperKind = PaperKind.A4, SheetCount = 8, ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
            var stride = page.Appearance.Height + A4PageLayout.PageGap;
            var document = new PageDocument { Strokes = [
                new StrokeRecord { Color = InkPalette.Black, WidthMm = 1, Points = [new InkPoint(40, 80), new InkPoint(400, 250)] },
                new StrokeRecord { Color = InkPalette.Red, WidthMm = 1, Points = [new InkPoint(40, (float)stride + 80), new InkPoint(400, (float)stride + 250)] } ] };
            var canvas = new NoteCanvas { Document = document, Appearance = page.Appearance };
            var pages = PaginatedExportService.Capture(canvas, page, new ExportOptions());
            Assert.Equal(2, pages.Count);
            Assert.Equal(2, PaginatedExportService.FixedDocument(pages).Pages.Count);
            VectorPdfExportService.Write(path, pages);
            var pdf = Encoding.ASCII.GetString(File.ReadAllBytes(path));
            Assert.DoesNotContain("/Subtype /Image", pdf); Assert.Contains("/Count 2", pdf);
            PaginatedExportService.WritePngZip(Path.Combine(root, "pages.zip"), pages);
            using var zip = ZipFile.OpenRead(Path.Combine(root, "pages.zip"));
            Assert.Equal(new[] { "page-0001.png", "page-0002.png" }, zip.Entries.Select(e => e.FullName));
            PaginatedExportService.WriteXps(Path.Combine(root, "pages.xps"), pages);
        });
        for (var number = 1; number <= 2; number++)
        {
            var image = await PdfImportService.RenderPageAsync(path, number, 700);
            File.WriteAllBytes(Path.Combine(root, $"pdf-page-{number}.png"), image.PngBytes);
            Assert.InRange(image.WidthPoints, 595.27, 595.29);
            Assert.InRange(image.HeightPoints, 841.88, 841.9);
        }
    }

    [Fact]
    public async Task OptionalRealHomeworkUsesProductPaginationWithoutChangingSource()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_PAGINATION_PAGE_JSON");
        if (string.IsNullOrWhiteSpace(path)) return;
        var original = File.ReadAllText(path);
        var document = JsonSerializer.Deserialize<PageDocument>(original, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-export-pagination", "qa"); Directory.CreateDirectory(root);
        var pdf = Path.Combine(root, "homework-paginated.pdf");
        Sta(() =>
        {
            var page = new NotePage { Appearance = new PageAppearance { PaperKind = PaperKind.A4, SheetCount = 3, Background = CanvasBackgroundFormat.RuledWide, ShowTitle = false } };
            var canvas = new NoteCanvas { Document = document, Appearance = page.Appearance };
            var sheets = PaginatedExportService.Capture(canvas, page, new ExportOptions(Printing: true));
            Assert.Equal(2, sheets.Count);
            VectorPdfExportService.Write(pdf, sheets);
            Assert.DoesNotContain("/Subtype /Image", Encoding.ASCII.GetString(File.ReadAllBytes(pdf)));
        });
        for (var i = 1; i <= 2; i++) File.WriteAllBytes(Path.Combine(root, $"homework-page-{i}.png"), (await PdfImportService.RenderPageAsync(pdf, i, 1000)).PngBytes);
        Assert.Equal(original, File.ReadAllText(path));
    }

    [Fact]
    public void RealBackstageExportAndPrintPanelsResolveScopeAndRenderTheirActions()
    {
        Sta(() =>
        {
            var app = Application.Current ?? new Application();
            app.Resources.MergedDictionaries.Clear();
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                app.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-export-pagination", Guid.NewGuid().ToString("N"));
            var model = new MainViewModel(root, new PreferencesStore(Path.Combine(root, "preferences.json")));
            var window = new MainWindow(model) { Width = 1280, Height = 1050 };
            window.Show();
            try
            {
                model.IsBackstageOpen = true;
                model.SelectBackstageTabCommand.Execute("Export");
                window.UpdateLayout();
                var backstage = Assert.IsType<BackstageView>(window.FindName("Backstage"));
                var panel = Assert.IsType<ExportPanel>(backstage.FindName("ExportSettings"));
                Assert.Contains("张纸", ((TextBlock)panel.FindName("Summary")).Text);
                Assert.True(((Button)panel.FindName("ActionButton")).IsEnabled);
                ((ListBox)panel.FindName("ScopeList")).SelectedIndex = 2;
                Assert.Contains("张纸", ((TextBlock)panel.FindName("Summary")).Text);
                var output = Path.Combine(Path.GetTempPath(), ".agents", "mojian-export-pagination", "qa"); Directory.CreateDirectory(output);
                Capture(window, Path.Combine(output, "export-panel.png"));
                model.SelectBackstageTabCommand.Execute("Print"); window.UpdateLayout();
                var printPanel = Assert.IsType<ExportPanel>(backstage.FindName("PrintSettings"));
                Assert.Equal(Visibility.Collapsed, ((StackPanel)printPanel.FindName("FormatPanel")).Visibility);
                Capture(window, Path.Combine(output, "print-panel.png"));
            }
            finally { window.Close(); Directory.Delete(root, true); }
        });
    }

    private static void Capture(Window window, string path)
    {
        window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Render);
        var bitmap = new RenderTargetBitmap((int)window.Width, (int)window.Height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render((Visual)window.Content);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); using var stream = File.Create(path); encoder.Save(stream);
    }
    private static void Sta(Action action)
    {
        Exception? error = null; var thread = new Thread(() => { try { action(); } catch (Exception e) { error = e; } });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join(); Assert.Null(error);
    }
}
