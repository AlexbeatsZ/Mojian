using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;

namespace Notein_win.Tests;

public class OutputAppearanceTests
{
    [Fact]
    public void ImportDefaultsUpgradeOnceAndLightPagesNeverAsk()
    {
        foreach (var mode in Enum.GetValues<ImageImportDisplayMode>()) Assert.Equal(false, ImageImportPolicy.Resolve(mode, false));
        Assert.Null(ImageImportPolicy.Resolve(ImageImportDisplayMode.Ask, true));
        var settings = new WorkspaceSettings { ImageDisplayMode = ImageImportDisplayMode.Ask, PdfDisplayMode = ImageImportDisplayMode.Original };
        settings.UpgradeDarkImageImportPolicy();
        Assert.Equal(ImageImportDisplayMode.Invert, settings.ImageDisplayMode);
        Assert.Equal(ImageImportDisplayMode.Original, settings.PdfDisplayMode);
        settings.ImageDisplayMode = ImageImportDisplayMode.Ask;
        settings.UpgradeDarkImageImportPolicy();
        Assert.Equal(ImageImportDisplayMode.Ask, settings.ImageDisplayMode);
    }

    [Fact]
    public void PrintTogglesOnlyChosenImagesAndPreservesIntentionalColors()
    {
        var output = new OutputAppearance(true, new HashSet<string> { "a", "b" });
        Assert.False(output.ImageInverted("a", true, PageTheme.Dark));
        Assert.True(output.ImageInverted("b", false, PageTheme.Dark));
        Assert.True(output.ImageInverted("c", true, PageTheme.Dark));
        Assert.True(output.ImageInverted("a", true, PageTheme.Light));
        Assert.False(output.ImageInverted("b", false, PageTheme.Light));
        Assert.Equal(Colors.Black, OutputAppearance.PrintInk(Colors.White));
        Assert.Equal(Color.FromArgb(128, 0, 0, 0), OutputAppearance.PrintInk(Color.FromArgb(128, 239, 239, 239)));
        Assert.Equal(Colors.Red, OutputAppearance.PrintInk(Colors.Red));
        Assert.Equal(Color.FromRgb(231, 231, 231), OutputAppearance.PrintInk(Color.FromRgb(231, 231, 231)));
    }

    [Fact]
    public void RealCanvasSeparatesDisplayedExportFromPrintAndHidesOnlyPrintedPatterns()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            var temp = Path.Combine(Path.GetTempPath(), ".agents", "mojian-output-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temp);
            try
            {
                _ = Application.Current ?? new Application();
                var asset = Path.Combine(temp, "white.png");
                var bitmap = BitmapSource.Create(2, 2, 96, 96, PixelFormats.Bgra32, null, Enumerable.Repeat((byte)255, 16).ToArray(), 8);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using (var stream = File.Create(asset)) encoder.Save(stream);
                var document = new PageDocument();
                document.Strokes.Add(new StrokeRecord { Color = InkPalette.Black, VectorPath = "M10,10 L30,10 30,30 10,30 Z", VectorBounds = [10,10,30,30] });
                document.Strokes.Add(new StrokeRecord { Color = InkPalette.Red, VectorPath = "M40,10 L60,10 60,30 40,30 Z", VectorBounds = [40,10,60,30] });
                document.Strokes.Add(new StrokeRecord { ColorHex = "#FFFFFF", VectorPath = "M70,10 L90,10 90,30 70,30 Z", VectorBounds = [70,10,90,30] });
                var image = new PageObject { Id = "image", Kind = PageObjectKind.Image, AssetName = "white.png", X = 100, Y = 10, Width = 20, Height = 20, ViewInverted = true };
                document.Objects.Add(image);
                var canvas = new NoteCanvas { Document = document, ResolveAssetPath = _ => asset,
                    Appearance = new PageAppearance { Theme = PageTheme.Dark, PageColor = "#1B1B1B", ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
                var area = new Rect(0,0,160,80);
                var exported = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Composite, area));
                var printed = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Composite, area, new OutputAppearance(true)));
                Assert.Equal(Color.FromRgb(27,27,27), Pixel(exported, 5,5));
                Assert.Equal(Color.FromRgb(239,239,239), Pixel(exported,20,20));
                Assert.Equal(Colors.White, Pixel(printed,5,5));
                Assert.Equal(Colors.Black, Pixel(printed,20,20));
                Assert.Equal(Pixel(exported,50,20), Pixel(printed,50,20));
                Assert.Equal(Colors.White, Pixel(exported,80,20));
                Assert.Equal(Colors.Black, Pixel(printed,80,20));
                Assert.Equal(Color.FromRgb(27,27,27), Pixel(exported,110,20));
                Assert.Equal(Colors.White, Pixel(printed,110,20));
                Assert.True(image.ViewInverted);
                var retained = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Composite, area, new OutputAppearance(true, new HashSet<string>())));
                Assert.Equal(Pixel(exported,110,20), Pixel(retained,110,20));
                Assert.True(canvas.ToggleImageInversion(image));
                Assert.False(image.ViewInverted);
                canvas.Undo();
                Assert.True(canvas.Document!.Objects.Single().ViewInverted);
                canvas.Appearance.Background = CanvasBackgroundFormat.GridStandard;
                var pattern = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, area, new OutputAppearance(true)));
                var hidden = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, area, new OutputAppearance(true, HideBackgroundFormat: true)));
                Assert.NotEqual(Pixel(pattern,32,40), Pixel(hidden,32,40));
                Assert.Equal(Colors.White, Pixel(hidden,32,40));
                Assert.Equal(CanvasBackgroundFormat.GridStandard, canvas.Appearance.Background);
                canvas.Appearance.Theme = PageTheme.Light; canvas.Appearance.PageColor = "#FAFAFA";
                var light = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.InkAndText, area, new OutputAppearance(true)));
                Assert.Equal(Colors.White, Pixel(light,80,20));
            }
            catch (Exception error) { failure = error; }
            finally { Directory.Delete(temp, true); }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        Assert.Null(failure);
    }

    private static BitmapSource Raster(Drawing drawing)
    {
        var visual = new DrawingVisual(); using (var dc = visual.RenderOpen()) dc.DrawDrawing(drawing);
        var bitmap = new RenderTargetBitmap(160,80,96,96,PixelFormats.Pbgra32); bitmap.Render(visual); return bitmap;
    }
    private static Color Pixel(BitmapSource source, int x, int y)
    {
        var bytes = new byte[4]; source.CopyPixels(new Int32Rect(x,y,1,1),bytes,4,0);
        return Color.FromArgb(bytes[3],bytes[2],bytes[1],bytes[0]);
    }
}
