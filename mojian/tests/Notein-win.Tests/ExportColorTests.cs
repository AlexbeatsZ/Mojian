using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;

namespace Notein_win.Tests;

public class ExportColorTests
{
    [Fact]
    public void DarkPageExportHasWhitePaperBlackLogicalInkAndOriginalColoredInk()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                _ = Application.Current ?? new Application();
                var document = new PageDocument();
                for (var index = 0; index < 4; index++)
                {
                    var x = index * 40;
                    document.Strokes.Add(new StrokeRecord
                    {
                        Color = (InkPalette)Math.Min(index, 2),
                        ColorHex = index == 3 ? "#FFFFFF" : string.Empty,
                        VectorPath = $"M{x},0 L{x + 20},0 {x + 20},20 {x},20 Z",
                        VectorBounds = [x, 0, x + 20, 20],
                        OpacityPercent = 100,
                    });
                }
                var canvas = new NoteCanvas
                {
                    Document = document,
                    Appearance = new PageAppearance { Theme = PageTheme.Dark, Background = CanvasBackgroundFormat.Blank, ShowTitle = false },
                };
                var bounds = InfinitePageLayout.ExportBounds(document);
                var ink = canvas.RenderLayerBitmap(CanvasRenderLayer.InkAndText);
                var paper = canvas.RenderLayerBitmap(CanvasRenderLayer.Background);
                Assert.Equal(Colors.White, Pixel(paper, 0, 0));
                var y = (int)Math.Round(10 - bounds.Y);
                var x0 = (int)Math.Round(10 - bounds.X);
                Assert.Equal(Colors.Black, Pixel(ink, x0, y));
                Assert.Equal(Color.FromRgb(70, 153, 255), Pixel(ink, x0 + 40, y));
                Assert.Equal(Color.FromRgb(232, 49, 57), Pixel(ink, x0 + 80, y));
                // Imported white can be cover ink; retain its source color.
                Assert.Equal(Colors.White, Pixel(ink, x0 + 120, y));
            }
            catch (Exception error) { failure = error; }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        Assert.Null(failure);
    }

    private static Color Pixel(BitmapSource source, int x, int y)
    {
        var pixels = new byte[4];
        source.CopyPixels(new Int32Rect(x, y, 1, 1), pixels, 4, 0);
        return Color.FromArgb(pixels[3], pixels[2], pixels[1], pixels[0]);
    }
}
