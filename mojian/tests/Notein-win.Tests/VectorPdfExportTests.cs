using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class VectorPdfExportTests
{
    [Fact]
    public async Task NativeInkAndTextExportContainsPathsWithoutImagesAndRendersInIndependentPdfReader()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-vector-pdf-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var pdf = Path.Combine(root, "ink.pdf");
        try
        {
            Sta(() =>
            {
                _ = Application.Current ?? new Application();
                var document = new PageDocument
                {
                    Strokes = [new StrokeRecord { Color = InkPalette.Black, Points = [new InkPoint(20, 30, .2f), new InkPoint(100, 30, .9f)], WidthMm = 2 },
                        new StrokeRecord { Color = InkPalette.Red, BrushProfile = "shape-v1", Points = [new InkPoint(20, 60), new InkPoint(100, 60)], WidthMm = 2, OpacityPercent = 50 }],
                    TextItems = [new TextItem { Text = "墨笺", X = 20, Y = 85, Width = 120, Height = 40, FontSize = 20 }],
                };
                var canvas = new NoteCanvas { Document = document, Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
                var bounds = new Rect(0, 0, 150, 140);
                var previous = CultureInfo.CurrentCulture;
                try
                {
                    CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("fr-FR");
                    VectorPdfExportService.Write(pdf, [new PdfVectorPage(bounds,
                        [new PdfVectorLayer("页面背景", canvas.RenderLayerDrawing(CanvasRenderLayer.Background, bounds, new OutputAppearance(true))),
                         new PdfVectorLayer("墨迹与文字", canvas.RenderLayerDrawing(CanvasRenderLayer.InkAndText, bounds, new OutputAppearance(true)))])]);
                }
                finally { CultureInfo.CurrentCulture = previous; }
            });
            var source = Encoding.ASCII.GetString(File.ReadAllBytes(pdf));
            Assert.DoesNotContain("/Subtype /Image", source);
            Assert.Contains("/MediaBox [0 0 112.5 105]", source);
            Assert.Contains("/OCProperties", source);
            var rendered = await PdfImportService.RenderPageAsync(pdf, 1, 600);
            using var stream = new MemoryStream(rendered.PngBytes);
            var frame = new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
            var converted = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
            var pixels = new byte[converted.PixelWidth * converted.PixelHeight * 4];
            converted.CopyPixels(pixels, converted.PixelWidth * 4, 0);
            Assert.Contains(Enumerable.Range(0, pixels.Length / 4), i => pixels[i * 4] < 10 && pixels[i * 4 + 1] < 10 && pixels[i * 4 + 2] < 10);
            Assert.Contains(Enumerable.Range(0, pixels.Length / 4), i => pixels[i * 4 + 2] > 220 && pixels[i * 4 + 1] is > 120 and < 180 && pixels[i * 4] is > 120 and < 180);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task ActualImageKeepsItsOwnPixelsOrientationAndTransparencyInsideVectorPage()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-vector-image-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var pdf = Path.Combine(root, "image.pdf");
        try
        {
            Sta(() =>
            {
                var bitmap = BitmapSource.Create(1, 2, 96, 96, PixelFormats.Bgra32, null, new byte[] { 0, 0, 255, 255, 255, 0, 0, 128 }, 4);
                var drawing = new DrawingGroup();
                using (var dc = drawing.Open()) { dc.DrawRectangle(Brushes.White, null, new Rect(0, 0, 100, 100)); dc.DrawImage(bitmap, new Rect(20, 20, 40, 60)); }
                VectorPdfExportService.Write(pdf, [new PdfVectorPage(new Rect(0, 0, 100, 100), [new PdfVectorLayer("图片", drawing)])]);
            });
            var rendered = await PdfImportService.RenderPageAsync(pdf, 1, 400);
            using var stream = new MemoryStream(rendered.PngBytes);
            var bitmap = new FormatConvertedBitmap(new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0], PixelFormats.Bgra32, null, 0);
            byte[] Pixel(double x, double y) { var value = new byte[4]; bitmap.CopyPixels(new Int32Rect((int)(x * bitmap.PixelWidth / 100), (int)(y * bitmap.PixelHeight / 100), 1, 1), value, 4, 0); return value; }
            var top = Pixel(40, 30); Assert.Equal(255, top[2]); Assert.Equal(0, top[0]);
            var bottom = Pixel(40, 70); Assert.Equal(255, bottom[0]); Assert.InRange(bottom[2], 125, 130);
        }
        finally { Directory.Delete(root, true); }
    }

    private static void Sta(Action action)
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() => { try { action(); } catch (Exception error) { failure = error; } });
        Assert.Null(failure);
    }
}
