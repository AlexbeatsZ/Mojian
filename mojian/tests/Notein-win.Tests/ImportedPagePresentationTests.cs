using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class ImportedPagePresentationTests
{
    [Fact]
    public void BlueScreenshotBackgroundBecomesGrayAndBothBlackAndWhiteFormulaStayVisible()
    {
        var samples = Enumerable.Repeat(unchecked((int)0xFF0000B8), 80)
            .Concat(Enumerable.Repeat(unchecked((int)0xFFFFFFFF), 10))
            .Concat(Enumerable.Repeat(unchecked((int)0xFF000000), 10));
        var background = ImportedImageAppearance.Background(samples)!.Value;
        Assert.Equal(Color.FromRgb(27, 27, 27), ImportedImageAppearance.Pixel(Color.FromRgb(0, 0, 184), background, true));
        Assert.Equal(Colors.White, ImportedImageAppearance.Pixel(Colors.Black, background, true));
        Assert.Equal(Colors.White, ImportedImageAppearance.Pixel(Colors.White, background, true));
        var colors = Enumerable.Range(0, 256).Select(value => unchecked((int)0xFF000000) | value << 16 | (255 - value) << 8);
        Assert.Null(ImportedImageAppearance.Background(colors));
        var translucent = ImportedImageAppearance.Pixel(Color.FromArgb(64, 0, 0, 0), background, true);
        Assert.Equal(64, translucent.A);
    }

    [Theory]
    [InlineData(PaperKind.Infinite)]
    [InlineData(PaperKind.A4)]
    public void ImportedPaperContainsEveryEdgeWithoutChangingCoordinatesOrMetadata(PaperKind kind)
    {
        var document = new PageDocument("notebook", "page-notein-example");
        document.Objects.Add(new PageObject { X = -2400, Y = -74, Width = 4700, Height = 26000 });
        var appearance = new PageAppearance { PaperKind = kind, PageColor = "#FFFFEABD", Theme = PageTheme.Light };
        var before = JsonSerializer.Serialize(document);
        var appearanceBefore = JsonSerializer.Serialize(appearance);
        var bounds = ImportedPagePresentation.PaperBounds(appearance, document)!.Value;
        Assert.True(bounds.Contains(new Rect(-2400, -74, 4700, 26000)));
        Assert.Equal(before, JsonSerializer.Serialize(document));
        Assert.Equal(appearanceBefore, JsonSerializer.Serialize(appearance));
        var plan = ExportLayout.Plan(new NotePage { Appearance = appearance }, document, new ExportOptions());
        Assert.True(plan[0].Source.Left <= -2400);
        Assert.True(plan[0].Source.Top <= -74);
        Assert.True(plan[^1].Source.Bottom >= 25926 - .01);
        document.PageId = "page-native";
        Assert.Null(ImportedPagePresentation.PaperBounds(appearance, document));
        document.PageId = "page-notein-example";
        appearance.PaperKind = PaperKind.Pdf;
        Assert.Null(ImportedPagePresentation.PaperBounds(appearance, document));
    }

    [Theory]
    [InlineData("#FFFFEABD")]
    [InlineData("#FFFFFFFF")]
    [InlineData("#FF292829")]
    public void LegacyEightDigitPaperUsesGrayAndRetainsOriginalSource(string color)
    {
        var source = new PageAppearance { PageColor = color, Theme = PageTheme.Light };
        var display = PageThemeAppearance.Apply(source, PageTheme.Dark);
        Assert.Equal("#1B1B1B", display.PageColor);
        Assert.Equal(color, display.SourcePageColor);
        Assert.Equal(color, source.PageColor);
        Assert.DoesNotContain("SourcePageColor", JsonSerializer.Serialize(display));
        var image = new PageObject { Id = "object-notein-image-example" };
        Assert.Equal(!ImportedPagePresentation.IsDark(color), ImportedPagePresentation.ImageInverted("page-notein-example", display, image));
        image.Id = "object-native";
        Assert.False(ImportedPagePresentation.ImageInverted("page-notein-example", display, image));
    }

    [Fact]
    public void NegativeInkRendersInsideGrayPaperAndSourceWhitesRemainCoverInk()
    {
        WpfTestExecutor.Run(() =>
        {
            var document = new PageDocument("notebook", "page-notein-example");
            document.Strokes.Add(new StrokeRecord { Id = "stroke-notein-black", ColorHex = "#FF000000",
                VectorPath = "M -120,-20 L -80,-20 L -80,20 L -120,20 Z", OpacityPercent = 100 });
            document.Strokes.Add(new StrokeRecord { Id = "stroke-notein-white", ColorHex = "#FFFFFFFF",
                VectorPath = "M -60,-20 L -20,-20 L -20,20 L -60,20 Z", OpacityPercent = 100 });
            var source = JsonSerializer.Serialize(document);
            var canvas = new NoteCanvas { Document = document, Appearance = PageThemeAppearance.Apply(
                new PageAppearance { PageColor = "#FFFFEABD", Theme = PageTheme.Light }, PageTheme.Dark) };
            var drawing = canvas.RenderLayerDrawing(CanvasRenderLayer.Composite, new Rect(-150, -50, 160, 100), new OutputAppearance());
            var visual = new DrawingVisual();
            using (var context = visual.RenderOpen())
            {
                context.PushTransform(new TranslateTransform(150, 50));
                context.DrawDrawing(drawing);
            }
            var bitmap = new RenderTargetBitmap(160, 100, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            Assert.Equal(Color.FromRgb(27, 27, 27), Pixel(bitmap, 5, 5));
            Assert.Equal(Colors.White, Pixel(bitmap, 50, 50));
            Assert.Equal(Color.FromRgb(27, 27, 27), Pixel(bitmap, 110, 50));
            Assert.Equal(source, JsonSerializer.Serialize(document));
        });
    }

    [Fact]
    public void OptionalReadOnlyRealPagePreview()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_IMPORTED_PAGE_JSON");
        var output = Environment.GetEnvironmentVariable("MOJIAN_IMPORTED_PREVIEW");
        if (string.IsNullOrWhiteSpace(path) || string.IsNullOrWhiteSpace(output)) return;
        WpfTestExecutor.Run(() =>
        {
            var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            var document = JsonSerializer.Deserialize<PageDocument>(File.ReadAllText(path), options)!;
            var canvas = new NoteCanvas { Document = document, Appearance = PageThemeAppearance.Apply(
                new PageAppearance { PageColor = "#FFFFEABD", Theme = PageTheme.Light, ShowTitle = false, Background = CanvasBackgroundFormat.Blank }, PageTheme.Dark),
                ResolveAssetPath = asset => Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(path))!, "assets", document.PageId, asset) };
            var drawing = canvas.RenderLayerDrawing(CanvasRenderLayer.Composite,
                new Rect(-2450, -100, 1792 / .55, 1308 / .55), new OutputAppearance());
            var visual = new DrawingVisual();
            using (var context = visual.RenderOpen())
            {
                context.PushTransform(new MatrixTransform(.55, 0, 0, .55, 2450 * .55, 100 * .55));
                context.DrawDrawing(drawing);
            }
            var bitmap = new RenderTargetBitmap(1792, 1308, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var stream = File.Create(output);
            encoder.Save(stream);
        });
    }

    private static Color Pixel(BitmapSource bitmap, int horizontal, int vertical)
    {
        var bytes = new byte[4];
        bitmap.CopyPixels(new Int32Rect(horizontal, vertical, 1, 1), bytes, 4, 0);
        return Color.FromArgb(bytes[3], bytes[2], bytes[1], bytes[0]);
    }
}
