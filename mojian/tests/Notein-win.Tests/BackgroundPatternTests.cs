using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;

namespace Notein_win.Tests;

public class BackgroundPatternTests
{
    [Fact]
    public void SharedCatalogCoversEverySavedFormatWithoutChangingNumericIds()
    {
        var catalog = BackgroundPatternCatalog.Shared;
        Assert.Equal(Enum.GetValues<CanvasBackgroundFormat>().Length, catalog.Formats.Count);
        foreach (var format in Enum.GetValues<CanvasBackgroundFormat>())
        {
            Assert.True(catalog.For(format).Spacing > 0);
            Assert.Equal(catalog.For(format).Label, format.GetLabel());
        }
        Assert.Equal(9, (int)CanvasBackgroundFormat.DotsWide);
        Assert.Equal(13, (int)CanvasBackgroundFormat.RuledClean);
        Assert.Equal(32, A4PageLayout.PageGap);
        Assert.Equal(24, catalog.For(CanvasBackgroundFormat.GridDense).Spacing);
        Assert.Equal(48, catalog.For(CanvasBackgroundFormat.RuledWide).Spacing);
        Assert.Equal("#2D3238", catalog.Dark.Line);
        Assert.Equal(catalog.Dark, catalog.Light);
        Assert.Equal(1, catalog.DotRadius);
        Assert.Equal(0, catalog.MajorEvery);
        Assert.True(catalog.ScreenLineWidth);
    }

    [Fact]
    public void NativeRulesGridDotsAndThemesKeepDocumentGeometryThroughZoomCropAndPrint()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var output = Path.Combine(Path.GetTempPath(), ".agents", "mojian-background-parity");
                Directory.CreateDirectory(output);
                foreach (var theme in Enum.GetValues<PageTheme>())
                foreach (var format in Enum.GetValues<CanvasBackgroundFormat>())
                {
                    var canvas = new NoteCanvas
                    {
                        Document = new PageDocument("background", "parity"),
                        Appearance = new PageAppearance { Background = format, Theme = theme, ShowTitle = false,
                            PageColor = theme == PageTheme.Dark ? "#1B1B1B" : "#FAFAFA", InfiniteWidth = 320, InfiniteHeight = 240 },
                    };
                    var bounds = new Rect(0, 0, 320, 240);
                    var original = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, bounds));
                    canvas.Viewport.Restore(new PageViewport { Scale = 4, TranslationX = -23, TranslationY = -51 });
                    Assert.Equal(original, Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, bounds)));
                    var hidden = Raster(canvas.RenderLayerDrawing(CanvasRenderLayer.Background, bounds,
                        new OutputAppearance(Printing: true, HideBackgroundFormat: true)));
                    Assert.Equal(Pixel(hidden, 40, 20), Pixel(hidden, 64, 32));
                    if (format != CanvasBackgroundFormat.Blank)
                    {
                        var spacing = BackgroundPatternCatalog.Shared.For(format).Spacing;
                        // The next canonical rule/dot stays exactly at its stored document coordinate.
                        Assert.NotEqual(Pixel(original, (int)Math.Round(spacing), (int)Math.Round(spacing)), Pixel(original, 7, 7));
                        var crop = canvas.RenderLayerDrawing(CanvasRenderLayer.Background, new Rect(17, 19, 210, 150));
                        var cropped = Raster(crop);
                        Assert.Equal(Pixel(original, (int)Math.Round(spacing * 2), (int)Math.Round(spacing * 2)),
                            Pixel(cropped, (int)Math.Round(spacing * 2), (int)Math.Round(spacing * 2)));
                    }
                    var bitmap = BitmapSource.Create(320, 240, 96, 96, PixelFormats.Pbgra32, null, original, 320 * 4);
                    var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                    using var stream = File.Create(Path.Combine(output, $"{theme}-{format}.png")); encoder.Save(stream);
                }
                var ruled = BackgroundPatternCatalog.Shared.For(CanvasBackgroundFormat.RuledStandard);
                Assert.False(ruled.Margin);
                Assert.False(BackgroundPatternCatalog.Shared.For(CanvasBackgroundFormat.RuledClean).Margin);
            }
            catch (Exception error) { failure = error; }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        Assert.Null(failure);
    }

    private static byte[] Raster(Drawing drawing)
    {
        var visual = new DrawingVisual(); using (var dc = visual.RenderOpen()) dc.DrawDrawing(drawing);
        var bitmap = new RenderTargetBitmap(320, 240, 96, 96, PixelFormats.Pbgra32); bitmap.Render(visual);
        var result = new byte[320 * 240 * 4]; bitmap.CopyPixels(result, 320 * 4, 0); return result;
    }

    private static uint Pixel(byte[] bytes, int x, int y) => BitConverter.ToUInt32(bytes, (y * 320 + x) * 4);
}
