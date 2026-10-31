using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>Crop bounds use normalized, unrotated source pixels on both clients.</summary>
public static class ImageCrop
{
    public static int HitHandle(Point point, Rect bounds, double radius)
    {
        var nearest = SelectionGeometry.SelectionHandles(bounds).Select((p, i) => (Distance: (p - point).Length, Index: i)).MinBy(p => p.Distance);
        if (nearest.Distance <= radius) return nearest.Index;
        var expanded = bounds; expanded.Inflate(radius, radius);
        if (!expanded.Contains(point)) return -1;
        var edges = new[] { (Math.Abs(point.Y - bounds.Top), 1), (Math.Abs(point.X - bounds.Right), 3),
            (Math.Abs(point.Y - bounds.Bottom), 5), (Math.Abs(point.X - bounds.Left), 7) };
        var edge = edges.MinBy(p => p.Item1);
        return edge.Item1 <= radius ? edge.Item2 : -1;
    }

    public static Rect ResizeBounds(Rect start, int handle, Vector delta, double minimum)
    {
        var left = start.Left; var top = start.Top; var right = start.Right; var bottom = start.Bottom;
        var minX = Math.Min(start.Width, minimum); var minY = Math.Min(start.Height, minimum);
        if (handle is 0 or 6 or 7) left = Math.Clamp(left + delta.X, start.Left, right - minX);
        if (handle is 2 or 3 or 4) right = Math.Clamp(right + delta.X, left + minX, start.Right);
        if (handle is 0 or 1 or 2) top = Math.Clamp(top + delta.Y, start.Top, bottom - minY);
        if (handle is 4 or 5 or 6) bottom = Math.Clamp(bottom + delta.Y, top + minY, start.Bottom);
        return new Rect(left, top, right - left, bottom - top);
    }

    public static Rect FromDocumentBounds(PageObject image, Rect bounds)
    {
        var current = Display(image);
        return new Rect(current.Left + (bounds.Left - image.X) / image.Width * current.Width,
            current.Top + (bounds.Top - image.Y) / image.Height * current.Height,
            bounds.Width / image.Width * current.Width, bounds.Height / image.Height * current.Height);
    }
    public static Rect Full => new(0, 0, 1, 1);
    public static Rect Source(PageObject image) => Normalize(image.CropLeft, image.CropTop, image.CropRight, image.CropBottom);

    public static Rect Normalize(double left, double top, double right, double bottom)
    {
        if (!double.IsFinite(left) || !double.IsFinite(top) || !double.IsFinite(right) || !double.IsFinite(bottom))
            return Full;
        left = Math.Clamp(left, 0, 1); top = Math.Clamp(top, 0, 1);
        right = Math.Clamp(right, 0, 1); bottom = Math.Clamp(bottom, 0, 1);
        return right - left < 0.000001 || bottom - top < 0.000001 ? Full : new Rect(left, top, right - left, bottom - top);
    }

    public static Rect Display(PageObject image) => Transform(Source(image), image, inverse: false);

    public static Rect Transform(Rect rect, PageObject image, bool inverse)
    {
        Point Map(Point point)
        {
            var x = point.X; var y = point.Y;
            var turns = ((image.RotationQuarterTurns % 4) + 4) % 4;
            if (inverse)
            {
                if (image.FlipHorizontal) x = 1 - x;
                if (image.FlipVertical) y = 1 - y;
                turns = (4 - turns) % 4;
            }
            for (var i = 0; i < turns; i++) (x, y) = (1 - y, x);
            if (!inverse)
            {
                if (image.FlipHorizontal) x = 1 - x;
                if (image.FlipVertical) y = 1 - y;
            }
            return new Point(x, y);
        }
        var points = new[] { Map(rect.TopLeft), Map(rect.TopRight), Map(rect.BottomLeft), Map(rect.BottomRight) };
        return Normalize(points.Min(p => p.X), points.Min(p => p.Y), points.Max(p => p.X), points.Max(p => p.Y));
    }

    public static bool Apply(PageObject image, Rect displayCrop)
    {
        if (displayCrop.IsEmpty) return false;
        displayCrop = Normalize(displayCrop.Left, displayCrop.Top, displayCrop.Right, displayCrop.Bottom);
        var previous = Display(image);
        if (Math.Abs(previous.Left - displayCrop.Left) + Math.Abs(previous.Top - displayCrop.Top) +
            Math.Abs(previous.Width - displayCrop.Width) + Math.Abs(previous.Height - displayCrop.Height) < 0.000001)
            return false;
        var fullWidth = image.Width / previous.Width; var fullHeight = image.Height / previous.Height;
        image.X += (float)((displayCrop.Left - previous.Left) * fullWidth);
        image.Y += (float)((displayCrop.Top - previous.Top) * fullHeight);
        image.Width = (float)(fullWidth * displayCrop.Width);
        image.Height = (float)(fullHeight * displayCrop.Height);
        var source = Transform(displayCrop, image, inverse: true);
        image.CropLeft = source.Left; image.CropTop = source.Top;
        image.CropRight = source.Right; image.CropBottom = source.Bottom;
        return true;
    }
}
