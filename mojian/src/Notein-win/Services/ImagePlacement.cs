namespace Notein_win.Services;

public readonly record struct ImageDocumentSize(double Width, double Height);

public static class ImagePlacement
{
    public static ImageDocumentSize FitPixels(int pixelWidth, int pixelHeight, double maximumWidth = 620)
    {
        var width = Math.Max(1, pixelWidth);
        var height = Math.Max(1, pixelHeight);
        var scale = Math.Min(1d, Math.Max(1d, maximumWidth) / width);
        return new ImageDocumentSize(width * scale, height * scale);
    }
}
