using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class InkFillRuleTests
{
    private const string Crossing = "M10,30 L90,30 90,50 10,50 Z M40,10 L60,10 60,90 40,90 Z";

    private static NoteCanvas Canvas(string prefix, int opacity) => new()
    {
        Document = new PageDocument("ink", "crossing")
        {
            Strokes = [new StrokeRecord { VectorPath = prefix + Crossing, ColorHex = "#FFE02020", OpacityPercent = opacity }],
        },
        Appearance = new PageAppearance { ShowTitle = false, Theme = PageTheme.Light, Background = CanvasBackgroundFormat.Blank },
    };

    [Theory]
    [InlineData("", true)]
    [InlineData("F1 ", true)]
    [InlineData("F0 ", false)]
    public void CrossingUsesSourceFillRuleWithoutHolesOrDoubledOpacityInCanvasPngAndSvg(string prefix, bool filled)
    {
        WpfTestExecutor.Run(() =>
        {
            var canvas = Canvas(prefix, 50);
            var stroke = canvas.Document!.Strokes[0];
            var source = stroke.VectorPath;
            Assert.Equal(filled, stroke.VectorGeometry!.FillContains(new Point(50, 40)));
            var bounds = new Rect(0, 0, 100, 100);
            var drawing = canvas.RenderLayerDrawing(CanvasRenderLayer.InkAndText, bounds);
            var bitmap = SelectionImageExport.PngBitmap(drawing, bounds);
            byte Alpha(int x, int y)
            {
                var value = new byte[4]; bitmap.CopyPixels(new Int32Rect(x * 2, y * 2, 1, 1), value, 4, 0); return value[3];
            }
            Assert.InRange(Alpha(20, 40), 126, 129);
            if (filled) Assert.Equal(Alpha(20, 40), Alpha(50, 40));
            else Assert.Equal(0, Alpha(50, 40));
            var svg = XDocument.Parse(SelectionImageExport.SvgText(drawing, bounds));
            var path = Assert.Single(svg.Descendants(), element => element.Name.LocalName == "path");
            var rule = (string?)path.Attribute("fill-rule") == "evenodd" ? "F0 " : "F1 ";
            Assert.Equal(filled, Geometry.Parse(rule + (string?)path.Attribute("d")).FillContains(new Point(50, 40)));
            Assert.Equal(source, stroke.VectorPath);
            Assert.Equal(50, stroke.OpacityPercent);
        });
    }

    [Theory]
    [InlineData("", true)]
    [InlineData("F1 ", true)]
    [InlineData("F0 ", false)]
    public async Task PdfCrossingMatchesCanvasInIndependentReader(string prefix, bool filled)
    {
        var directory = Path.Combine(Path.GetTempPath(), ".agents", "mojian-ink-fill-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var path = Path.Combine(directory, "crossing.pdf");
            WpfTestExecutor.Run(() =>
            {
                var canvas = Canvas(prefix, 100);
                var bounds = new Rect(0, 0, 100, 100);
                var background = new GeometryDrawing(Brushes.White, null, new RectangleGeometry(bounds));
                VectorPdfExportService.Write(path, [new PdfVectorPage(bounds, [new PdfVectorLayer("paper", background),
                    new PdfVectorLayer("ink", canvas.RenderLayerDrawing(CanvasRenderLayer.InkAndText, bounds, new OutputAppearance(true)))])]);
            });
            var rendered = await PdfImportService.RenderPageAsync(path, 1, 400);
            using var stream = new MemoryStream(rendered.PngBytes);
            var frame = new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
            var bitmap = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
            var pixel = new byte[4];
            bitmap.CopyPixels(new Int32Rect(bitmap.PixelWidth / 2, bitmap.PixelHeight * 40 / 100, 1, 1), pixel, 4, 0);
            Assert.True(pixel[2] > 200);
            if (filled) Assert.True(pixel[1] < 60 && pixel[0] < 60);
            else Assert.True(pixel[1] > 240 && pixel[0] > 240);
        }
        finally { Directory.Delete(directory, true); }
    }
}
