using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class ImageCropTests
{
    [Fact]
    public void CropUsesDisplayAxesForEveryRotationAndFlipAndRestoresFullGeometry()
    {
        for (var turns = 0; turns < 4; turns++)
        foreach (var horizontal in new[] { false, true })
        foreach (var vertical in new[] { false, true })
        {
            var item = new PageObject { X = 100, Y = 200, Width = 400, Height = 240,
                RotationQuarterTurns = turns, FlipHorizontal = horizontal, FlipVertical = vertical };
            var selected = new Rect(.125, .25, .625, .625);
            Assert.True(ImageCrop.Apply(item, selected));
            Assert.Equal(150, item.X); Assert.Equal(260, item.Y);
            Assert.Equal(250, item.Width); Assert.Equal(150, item.Height);
            AssertRect(selected, ImageCrop.Display(item));
            var clone = item.CloneWithNewId(0);
            AssertRect(ImageCrop.Source(item), ImageCrop.Source(clone));
            Assert.True(ImageCrop.Apply(item, new Rect(.25, .375, .25, .25)));
            Assert.Equal(200, item.X); Assert.Equal(290, item.Y);
            Assert.True(ImageCrop.Apply(item, ImageCrop.Full));
            Assert.Equal(100, item.X); Assert.Equal(200, item.Y);
            Assert.Equal(400, item.Width); Assert.Equal(240, item.Height);
        }
        var rotated = new PageObject { RotationQuarterTurns = 1 };
        ImageCrop.Apply(rotated, new Rect(0, 0, .5, 1));
        AssertRect(new Rect(0, .5, 1, .5), ImageCrop.Source(rotated));
    }

    [Fact]
    public void InvalidOrLegacyCropFallsBackAndCanonicalSaveReloadRetainsCrop()
    {
        Assert.Equal(ImageCrop.Full, ImageCrop.Normalize(double.NaN, 0, 1, 1));
        Assert.Equal(ImageCrop.Full, ImageCrop.Normalize(.8, 0, .2, 1));
        Assert.Equal(ImageCrop.Full, ImageCrop.Source(JsonSerializer.Deserialize<PageObject>("{}")!));
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-crop-save-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new WorkspaceStore(root);
            var page = new PageDocument("book", "page");
            var image = new PageObject { Kind = PageObjectKind.Image, AssetName = "original.png", SourceAssetName = "original.pdf", SourcePageNumber = 2 };
            ImageCrop.Apply(image, new Rect(.25, .125, .5, .75)); page.Objects.Add(image);
            store.SavePage(page);
            var loaded = new WorkspaceStore(root).LoadPage("book", "page").Objects.Single();
            AssertRect(ImageCrop.Source(image), ImageCrop.Source(loaded));
            Assert.Equal("original.png", loaded.AssetName); Assert.Equal("original.pdf", loaded.SourceAssetName);
            var method = typeof(NotebookPackageService).GetMethod("AndroidObjectJson", BindingFlags.NonPublic | BindingFlags.Static)!;
            var json = method.Invoke(null, [loaded])!.ToString()!;
            Assert.Contains("cropLeft", json); Assert.Contains("cropBottom", json);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Fact]
    public void NativeCanvasCropRendersCorrectPixelsAndUndoRedoKeepOriginal()
    {
        WpfTestExecutor.Run(() =>
        {
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-crop-render-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            try
            {
                var asset = Path.Combine(root, "quadrants.png");
                File.WriteAllBytes(asset, SelectionImageExport.PngBytes(Quadrants()));
                var originalBytes = File.ReadAllBytes(asset);
                for (var turns = 0; turns < 4; turns++)
                foreach (var flipX in new[] { false, true })
                foreach (var flipY in new[] { false, true })
                {
                    var item = new PageObject { Kind = PageObjectKind.Image, AssetName = "quadrants.png",
                        Width = 80, Height = 80, RotationQuarterTurns = turns, FlipHorizontal = flipX, FlipVertical = flipY };
                    var canvas = new NoteCanvas { Document = new PageDocument("book", "page") { Objects = [item] }, ResolveAssetPath = _ => asset };
                    var area = new Rect(0, 0, 80, 80);
                    BitmapSource Render() => SelectionImageExport.PngBitmap(canvas.RenderLayerDrawing(CanvasRenderLayer.Inserts, area), area);
                    var before = Render();
                    Assert.True(canvas.CropImageObject(item, new Rect(0, 0, .5, .5)));
                    var after = Render();
                    Assert.Equal(Pixel(before, 20, 20), Pixel(after, 20, 20));
                    Assert.Equal(0, Pixel(after, 120, 120).A);
                    Assert.False(canvas.CropImageObject(item, ImageCrop.Display(item)));
                    canvas.Undo();
                    Assert.Equal(ImageCrop.Full, ImageCrop.Source(canvas.Document!.Objects.Single()));
                    canvas.Redo();
                    Assert.Equal(40, canvas.Document!.Objects.Single().Width);
                    var svg = SelectionImageExport.SvgText(canvas.RenderLayerDrawing(CanvasRenderLayer.Inserts, area), area);
                    var embedded = XDocument.Parse(svg).Descendants().Single(e => e.Name.LocalName == "image");
                    var bytes = Convert.FromBase64String(embedded.Attributes().Single(a => a.Name.LocalName == "href").Value.Split(',')[1]);
                    using var stream = new MemoryStream(bytes);
                    var cropped = BitmapFrame.Create(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
                    Assert.Equal(40, cropped.PixelWidth); Assert.Equal(20, cropped.PixelHeight);
                }
                var liveItem = new PageObject { Kind = PageObjectKind.Image, AssetName = "quadrants.png", X = 100, Y = 100, Width = 400, Height = 200 };
                var liveCanvas = new NoteCanvas { Document = new PageDocument("book", "crop-gesture") { Objects = [liveItem] }, ResolveAssetPath = _ => asset };
                var window = new Window { Content = liveCanvas, Width = 820, Height = 600 };
                window.Show(); window.UpdateLayout();
                void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(liveCanvas, args);
                Point View(double x, double y) => liveCanvas.Viewport.DocumentToView(x, y);
                Assert.True(liveCanvas.BeginImageCrop(liveItem));
                Call("BeginDirectPointer", View(100, 100), false);
                Call("ContinueDirectPointer", View(150, 130));
                Assert.Equal(ImageCrop.Full, ImageCrop.Source(liveItem));
                Assert.Equal(400, liveItem.Width);
                var output = Environment.GetEnvironmentVariable("MOJIAN_CROP_QA_DIR");
                if (!string.IsNullOrEmpty(output))
                {
                    Directory.CreateDirectory(output);
                    liveCanvas.InvalidateVisual();
                    liveCanvas.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                    var bitmap = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                    bitmap.Render(window); File.WriteAllBytes(Path.Combine(output, "crop-in-canvas.png"), SelectionImageExport.PngBytes(bitmap));
                }
                Call("EndDirectPointer", View(150, 130));
                Assert.Equal(150, liveItem.X); Assert.Equal(130, liveItem.Y);
                Assert.Equal(350, liveItem.Width); Assert.Equal(170, liveItem.Height);
                Call("BeginDirectPointer", View(500, 300), false);
                Call("ContinueDirectPointer", View(450, 270));
                Call("CancelDirectPointer");
                Assert.Equal(350, liveItem.Width);
                liveCanvas.Undo();
                Assert.Equal(ImageCrop.Full, ImageCrop.Source(liveCanvas.Document!.Objects.Single()));
                Assert.Equal(400, liveCanvas.Document.Objects.Single().Width);
                liveCanvas.Redo();
                Assert.Equal(350, liveCanvas.Document.Objects.Single().Width);
                window.Close();
                Assert.Equal(originalBytes, File.ReadAllBytes(asset));
            }
            finally { Directory.Delete(root, true); }
        });
    }

    [Fact]
    public async Task CroppedPdfRendersInIndependentReader()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-crop-pdf-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var pdf = Path.Combine(root, "crop.pdf");
            WpfTestExecutor.Run(() =>
            {
                var asset = Path.Combine(root, "quadrants.png");
                File.WriteAllBytes(asset, SelectionImageExport.PngBytes(Quadrants()));
                var item = new PageObject { Kind = PageObjectKind.Image, AssetName = "quadrants.png", Width = 80, Height = 40 };
                var canvas = new NoteCanvas { Document = new PageDocument("book", "page") { Objects = [item] }, ResolveAssetPath = _ => asset };
                Assert.True(canvas.CropImageObject(item, new Rect(.5, 0, .5, .5)));
                var bounds = new Rect(40, 0, 40, 20);
                VectorPdfExportService.Write(pdf, [new PdfVectorPage(bounds, [new PdfVectorLayer("image", canvas.RenderLayerDrawing(CanvasRenderLayer.Inserts, bounds))])]);
            });
            var rendered = await PdfImportService.RenderPageAsync(pdf, 1, 160);
            using var stream = new MemoryStream(rendered.PngBytes);
            var bitmap = new FormatConvertedBitmap(BitmapFrame.Create(stream), PixelFormats.Bgra32, null, 0);
            Assert.Equal(Colors.Blue, Pixel(bitmap, bitmap.PixelWidth / 2, bitmap.PixelHeight / 2));
        }
        finally { Directory.Delete(root, true); }
    }

    private static BitmapSource Quadrants()
    {
        var pixels = new byte[80 * 40 * 4];
        for (var y = 0; y < 40; y++) for (var x = 0; x < 80; x++)
        {
            var c = y < 20 ? x < 40 ? Colors.Red : Colors.Blue : x < 40 ? Colors.Lime : Colors.Yellow;
            var i = (y * 80 + x) * 4; pixels[i] = c.B; pixels[i + 1] = c.G; pixels[i + 2] = c.R; pixels[i + 3] = c.A;
        }
        return BitmapSource.Create(80, 40, 96, 96, PixelFormats.Bgra32, null, pixels, 80 * 4);
    }
    private static Color Pixel(BitmapSource bitmap, int x, int y)
    {
        var converted = new FormatConvertedBitmap(bitmap, PixelFormats.Bgra32, null, 0);
        var pixel = new byte[4]; converted.CopyPixels(new Int32Rect(x, y, 1, 1), pixel, 4, 0);
        return Color.FromArgb(pixel[3], pixel[2], pixel[1], pixel[0]);
    }
    private static void AssertRect(Rect expected, Rect actual)
    {
        Assert.Equal(expected.X, actual.X, 6); Assert.Equal(expected.Y, actual.Y, 6);
        Assert.Equal(expected.Width, actual.Width, 6); Assert.Equal(expected.Height, actual.Height, 6);
    }
}
