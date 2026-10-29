using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Models;

namespace Notein_win.Services;

public static class ImportedImageAppearance
{
    public static int? Background(IEnumerable<int> samples)
    {
        var histogram = new Dictionary<int, int>();
        var count = 0;
        foreach (var color in samples)
        {
            if ((uint)color >> 24 < 230) continue;
            var key = color & 0xF8F8F8;
            histogram[key] = histogram.GetValueOrDefault(key) + 1;
            count++;
        }
        if (count == 0) return null;
        var primary = histogram.MaxBy(entry => entry.Value);
        return primary.Value >= count * .3 ? primary.Key | 0x040404 : null;
    }

    public static Color Pixel(Color source, int background, bool dark)
    {
        var red = source.R - (background >> 16 & 255);
        var green = source.G - (background >> 8 & 255);
        var blue = source.B - (background & 255);
        if (red * red + green * green + blue * blue <= 2025)
        {
            var paper = ImportedPagePresentation.PaperColor(dark);
            return Color.FromArgb(source.A, paper.R, paper.G, paper.B);
        }
        var neutral = Math.Max(source.R, Math.Max(source.G, source.B)) - Math.Min(source.R, Math.Min(source.G, source.B)) <= 24;
        var invert = neutral && (dark ? source.R + source.G + source.B < 384 : source.R + source.G + source.B >= 384);
        return ImportedPagePresentation.Ink(source, dark, invert);
    }

    public static BitmapSource Adapt(BitmapSource source, bool dark)
    {
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var stride = converted.PixelWidth * 4;
        var pixels = new int[converted.PixelWidth * converted.PixelHeight];
        converted.CopyPixels(pixels, stride, 0);
        var step = Math.Max(1, pixels.Length / 16384);
        var primary = Background(Enumerable.Range(0, (pixels.Length + step - 1) / step).Select(index => pixels[index * step]));
        if (primary == null) return source;
        var palette = new Dictionary<int, int>();
        for (var index = 0; index < pixels.Length; index++)
        {
            var original = pixels[index];
            if (!palette.TryGetValue(original, out var mapped))
            {
                var color = Pixel(Color.FromArgb((byte)((uint)original >> 24), (byte)(original >> 16), (byte)(original >> 8), (byte)original), primary.Value, dark);
                mapped = unchecked((int)((uint)color.A << 24 | (uint)color.R << 16 | (uint)color.G << 8 | color.B));
                if (palette.Count < 4096) palette[original] = mapped;
            }
            pixels[index] = mapped;
        }
        var result = BitmapSource.Create(source.PixelWidth, source.PixelHeight, source.DpiX, source.DpiY, PixelFormats.Bgra32, null, pixels, stride);
        result.Freeze();
        return result;
    }
}
