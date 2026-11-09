using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class PdfDocumentImportTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OnePdfCreatesOneNoteWithOrderedBackgroundsAndOneOriginalAsset(bool inverted)
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var template = vm.LastPageAppearance;
        var count = vm.ActiveWorkspace.SelectedSection()!.Pages.Count;
        var original = "%PDF-original"u8.ToArray();
        var source = Path.Combine(temp.Path, "讲义.pdf");
        File.WriteAllBytes(source, original);
        var document = vm.AddPdfDocument("讲义", [new(1, 2, 612, 792, [1]), new(2, 2, 842, 595, [2])], source, inverted)!;
        var page = vm.ActiveWorkspace.SelectedPage()!;
        Assert.Equal(count + 1, vm.ActiveWorkspace.SelectedSection()!.Pages.Count);
        Assert.Equal("讲义", page.Title);
        Assert.Equal(2, page.PdfPageCount);
        Assert.Equal(template.PaperKind, vm.LastPageAppearance.PaperKind);
        Assert.Equal(842 * 96d / 72, page.Appearance.Width, 6);
        Assert.Equal((792 + 595) * 96d / 72, page.Appearance.Height, 6);
        var loaded = vm.Store.LoadPage(document.NotebookId, document.PageId);
        Assert.Equal(new int?[] { 1, 2 }, loaded.Objects.Select(item => item.SourcePageNumber));
        Assert.All(loaded.Objects, item => { Assert.True(item.IsPageBackground); Assert.Equal(inverted, item.ViewInverted); });
        Assert.Equal(loaded.Objects[0].Height, loaded.Objects[1].Y);
        var assets = Directory.GetFiles(vm.Store.GetAssetDirectory(document.NotebookId, document.PageId));
        Assert.Equal(3, assets.Length);
        Assert.Equal(original, File.ReadAllBytes(Assert.Single(assets, path => path.EndsWith(".pdf"))));
        var sheets = ExportLayout.Plan(page, loaded, new ExportOptions());
        Assert.Equal(2, sheets.Count);
        Assert.Equal(612, sheets[0].Width * .75, 3); Assert.Equal(792, sheets[0].Height * .75, 3);
        Assert.Equal(842, sheets[1].Width * .75, 3); Assert.Equal(595, sheets[1].Height * .75, 3);
        Assert.Equal(sheets[0].Source.Bottom, sheets[1].Source.Top);
        var explicitSheets = ExportLayout.Plan(page, loaded, new ExportOptions(Paper: ExportPaper.Letter, Landscape: true));
        Assert.Equal(2, explicitSheets.Count);
        Assert.Equal(sheets[1].Source, explicitSheets[1].Source);
        Assert.All(explicitSheets, sheet => { Assert.Equal(792, sheet.Width * .75, 6); Assert.Equal(612, sheet.Height * .75, 6); });
        var package = Path.Combine(temp.Path, "lecture.mjn");
        NotebookPackageService.ExportPage(package, vm.ActiveWorkspace, page, vm.Store);
        using var archive = ZipFile.OpenRead(package);
        using var manifest = JsonDocument.Parse(archive.GetEntry("manifest.json")!.Open());
        Assert.Equal(1, manifest.RootElement.GetProperty("sections")[0].GetProperty("pages").GetArrayLength());
        Assert.Equal(1, archive.Entries.Count(entry => entry.FullName.EndsWith(".pdf")));
        Assert.Equal(original, File.ReadAllBytes(source));
    }

    [Fact]
    public void FailedImportLeavesSelectionIndexAndExistingNotesUntouched()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var before = File.ReadAllText(Path.Combine(vm.WorkspaceRoot, "library.json"));
        var selected = vm.FocusedDocument;
        Assert.Throws<FileNotFoundException>(() => { vm.AddPdfDocument("missing", [new(1, 1, 612, 792, [1])], Path.Combine(temp.Path, "missing.pdf"), false); });
        Assert.Equal(before, File.ReadAllText(Path.Combine(vm.WorkspaceRoot, "library.json")));
        Assert.Same(selected, vm.FocusedDocument);
        Assert.Throws<InvalidDataException>(() => vm.AddPdfDocument("empty", [], "unused", false));
    }

    [Fact]
    public async Task NativePdfImportAndExportKeepBothBackgroundsAndOriginalMediaBoxes()
    {
        using var temp = new TemporaryDirectory();
        var source = Path.Combine(temp.Path, "source.pdf");
        var output = Path.Combine(temp.Path, "output.pdf");
        WpfTestExecutor.Run(() =>
        {
            PdfVectorPage Sheet(double width, double height, Brush color)
            {
                var drawing = new DrawingGroup();
                using (var dc = drawing.Open()) dc.DrawRectangle(color, null, new Rect(0, 0, width, height));
                drawing.Freeze();
                return new(new Rect(0, 0, width, height), [new("source", drawing)]);
            }
            VectorPdfExportService.Write(source, [Sheet(200, 300, Brushes.Red), Sheet(300, 200, Brushes.Blue)]);
        });
        var rendered = await PdfImportService.RenderAsync(source, 128);
        Assert.Equal(2, rendered.Count);
        WpfTestExecutor.Run(() =>
        {
            var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
            var document = vm.AddPdfDocument("source", rendered, source, false)!;
            var page = vm.ActiveWorkspace.SelectedPage()!;
            var top = document.Objects[1].Y;
            document.Strokes.Add(new StrokeRecord { ColorHex = "#00FF00", VectorPath = $"M20,{top + 20} L60,{top + 20} 60,{top + 60} 20,{top + 60} Z",
                VectorBounds = [20, top + 20, 60, top + 60] });
            vm.SaveDocument(document);
            document = vm.Store.LoadPage(document.NotebookId, document.PageId);
            Assert.Single(document.Strokes);
            var canvas = new NoteCanvas { Document = document, Appearance = page.Appearance,
                ResolveAssetPath = name => vm.Store.ResolveAsset(document.NotebookId, document.PageId, name) };
            var pages = PaginatedExportService.Capture(canvas, page, new ExportOptions());
            Assert.Equal(2, pages.Count);
            Assert.Equal(2, PaginatedExportService.FixedDocument(pages).Pages.Count);
            VectorPdfExportService.Write(output, pages);
        });
        var exported = await PdfImportService.RenderAsync(output, 128);
        Assert.Equal(2, exported.Count);
        Assert.Equal(150, exported[0].WidthPoints, 3); Assert.Equal(225, exported[0].HeightPoints, 3);
        Assert.Equal(225, exported[1].WidthPoints, 3); Assert.Equal(150, exported[1].HeightPoints, 3);
        byte[] Pixel(RenderedPdfPage page, double x = .5, double y = .5)
        {
            using var stream = new MemoryStream(page.PngBytes);
            var png = new PngBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad).Frames[0];
            var bitmap = new FormatConvertedBitmap(png, PixelFormats.Bgra32, null, 0);
            var pixel = new byte[4];
            bitmap.CopyPixels(new Int32Rect((int)(bitmap.PixelWidth * x), (int)(bitmap.PixelHeight * y), 1, 1), pixel, 4, 0);
            return pixel;
        }
        var red = Pixel(exported[0]); var blue = Pixel(exported[1]);
        Assert.True(red[2] > 240 && red[0] < 15);
        Assert.True(blue[0] > 240 && blue[2] < 15);
        var annotation = Pixel(exported[1], 40d / 300, 40d / 200);
        Assert.True(annotation[1] > 240 && annotation[0] < 15 && annotation[2] < 15);
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-pdf-import-tests", Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, recursive: true);
    }
}
