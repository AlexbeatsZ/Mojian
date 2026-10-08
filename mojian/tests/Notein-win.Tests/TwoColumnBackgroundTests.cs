using System.IO;
using System.IO.Compression;
using System.Text.Json.Nodes;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class TwoColumnBackgroundTests
{
    [Fact]
    public void RealRibbonMenuChangesTheSavedPageAndNewPageChooserRetainsIt()
    {
        OnSta(() =>
        {
            var application = Application.Current!;
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(
                    new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-columns-ui-" + Guid.NewGuid().ToString("N"));
            var model = new MainViewModel(root, new PreferencesStore(Path.Combine(root, "preferences.json")));
            var window = new MainWindow(model) { Width = 1100, Height = 700 };
            try
            {
                window.Show(); window.UpdateLayout();
                var anchor = Descendants(window).OfType<Button>().Single(button => Equals(button.ToolTip, "设置背景格式"));
                anchor.RaiseEvent(new RoutedEventArgs(Button.ClickEvent, anchor));
                Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.Background);
                var menu = PresentationSource.CurrentSources.Cast<PresentationSource>()
                    .SelectMany(source => source.RootVisual == null ? [] : Descendants(source.RootVisual))
                    .OfType<ContextMenu>().Single();
                var items = menu.Items.OfType<MenuItem>().Where(item => item.Header.ToString()!.Contains("双栏")).ToList();
                Assert.Equal(3, items.Count);
                items.Single(item => Equals(item.Header, "标准双栏横线")).RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent));
                Assert.Equal(CanvasBackgroundFormat.RuledTwoColumnStandard, model.ActivePageAppearance.Background);
                menu.IsOpen = false;
                var dialog = new PageCreationDialog(model.ActivePageAppearance);
                var picker = (ComboBox)dialog.FindName("BackgroundPicker");
                Assert.Equal(CanvasBackgroundFormat.RuledTwoColumnStandard, ((BackgroundFormatOption)picker.SelectedItem).Format);
                Assert.Equal(3, picker.Items.OfType<BackgroundFormatOption>().Count(option => option.DisplayName.Contains("双栏")));
                dialog.Close();
            }
            finally { window.Close(); }
            Assert.Equal(CanvasBackgroundFormat.RuledTwoColumnStandard, new WorkspaceStore(root).LoadLibrary().ActiveNotebook.SelectedPage()!.Appearance.Background);
            Directory.Delete(root, true);
        });
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        yield return root;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            foreach (var child in Descendants(VisualTreeHelper.GetChild(root, index))) yield return child;
    }

    [Theory]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnStandard, "RULE_TWO_COLUMN_STANDARD")]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnNarrow, "RULE_TWO_COLUMN_NARROW")]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnWide, "RULE_TWO_COLUMN_WIDE")]
    public void SavedPagesAndSharedPackagesRetainTwoColumns(CanvasBackgroundFormat format, string sharedName)
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-columns-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new WorkspaceStore(root);
            var library = store.LoadLibrary();
            var notebook = library.ActiveNotebook;
            var page = notebook.SelectedPage()!;
            page.Appearance.Background = format;
            store.SaveLibrary(library);
            var restored = store.LoadLibrary();
            Assert.Equal(format, restored.ActiveNotebook.SelectedPage()!.Appearance.Background);
            var package = Path.Combine(root, "columns.mjn");
            NotebookPackageService.Export(package, restored.ActiveNotebook, store, page.Id);
            using var archive = ZipFile.OpenRead(package);
            using var reader = new StreamReader(archive.GetEntry("manifest.json")!.Open());
            var settings = JsonNode.Parse(reader.ReadToEnd())!["sections"]![0]!["pages"]![0]!["viewSettings"]!;
            Assert.Equal(sharedName, settings["backgroundFormat"]!.GetValue<string>());
            Assert.Equal(9, (int)CanvasBackgroundFormat.DotsWide);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnStandard, 32d)]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnNarrow, 24d)]
    [InlineData(CanvasBackgroundFormat.RuledTwoColumnWide, 48d)]
    public void RealCanvasCentersOnPaperAndKeepsGapAndPrintPolicy(CanvasBackgroundFormat format, double spacing)
    {
        OnSta(() =>
        {
            var canvas = new NoteCanvas
            {
                Document = new PageDocument("test", "page"),
                Appearance = new PageAppearance
                {
                    PaperKind = PaperKind.A4, Width = 200, Height = 112, SheetCount = 2,
                    Background = format, ShowTitle = false, Theme = PageTheme.Light, PageColor = "#FAFAFA",
                },
            };
            var extent = new Rect(0, 0, 200, 256);
            var before = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, extent), 200, 256);
            Assert.NotEqual(Pixel(before, 100, 10), Pixel(before, 105, 10));
            Assert.NotEqual(Pixel(before, 10, (int)spacing), Pixel(before, 10, (int)spacing + 5));
            Assert.Equal(Pixel(before, 100, 128), Pixel(before, 105, 128));
            Assert.NotEqual(Pixel(before, 100, 154), Pixel(before, 105, 154));

            canvas.Viewport.Restore(new PageViewport { Scale = 4, TranslationX = -70, TranslationY = -80 });
            var after = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, extent), 200, 256);
            Assert.Equal(Pixels(before), Pixels(after));
            var crop = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, new Rect(80, 0, 60, 80)), 60, 80, 80);
            Assert.NotEqual(Pixel(crop, 20, 10), Pixel(crop, 30, 10));

            var hidden = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, extent,
                new OutputAppearance(Printing: true, HideBackgroundFormat: true)), 200, 256);
            Assert.Equal(Pixel(hidden, 100, 10), Pixel(hidden, 105, 10));
            Assert.Equal(Pixel(hidden, 10, (int)spacing), Pixel(hidden, 10, (int)spacing + 5));
            Assert.Equal(format, canvas.Appearance.Background);

            canvas.Appearance.PaperKind = PaperKind.Infinite;
            canvas.Appearance.InfiniteWidth = 300;
            canvas.Appearance.InfiniteHeight = 256;
            var grown = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, new Rect(0, 0, 300, 256)), 300, 256);
            Assert.NotEqual(Pixel(grown, 150, 10), Pixel(grown, 155, 10));
            Assert.Equal(Pixel(grown, 100, 10), Pixel(grown, 105, 10));

            if (format == CanvasBackgroundFormat.RuledTwoColumnStandard)
            {
                var previewDir = Path.Combine(Path.GetTempPath(), ".agents", "mojian-two-column-preview");
                Directory.CreateDirectory(previewDir);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(before));
                using var stream = File.Create(Path.Combine(previewDir, "two-a4-sheets.png")); encoder.Save(stream);
            }
        });
    }

    private static void OnSta(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try { _ = Application.Current ?? new Application(); action(); }
            catch (Exception error) { failure = error; }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        Assert.Null(failure);
    }

    private static BitmapSource Raster(Drawing drawing, int width, int height, double left = 0)
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.PushTransform(new TranslateTransform(-left, 0)); dc.DrawDrawing(drawing); dc.Pop();
        }
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual); return bitmap;
    }
    private static byte[] Pixels(BitmapSource bitmap)
    {
        var bytes = new byte[bitmap.PixelWidth * bitmap.PixelHeight * 4];
        bitmap.CopyPixels(bytes, bitmap.PixelWidth * 4, 0); return bytes;
    }
    private static Color Pixel(BitmapSource bitmap, int x, int y)
    {
        var bytes = new byte[4]; bitmap.CopyPixels(new Int32Rect(x, y, 1, 1), bytes, 4, 0);
        return Color.FromArgb(bytes[3], bytes[2], bytes[1], bytes[0]);
    }
}
