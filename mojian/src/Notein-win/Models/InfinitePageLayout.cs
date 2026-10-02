namespace Notein_win.Models;

/// <summary>A paper rectangle anchored at (0, 0), grown only by document content.</summary>
public static class InfinitePageLayout
{
    public const double UnitsPerMillimeter = 96d / 25.4d;
    public const double InitialWidth = 210d * UnitsPerMillimeter;
    public const double InitialHeight = 297d * UnitsPerMillimeter;
    public const double AutoGrowMargin = 24d * UnitsPerMillimeter;
    public const double GrowthStep = 100d * UnitsPerMillimeter;
    public const double ExportPadding = 6d * UnitsPerMillimeter;
    public const double AndroidUnitsPerDesktopUnit = 12.16225d / UnitsPerMillimeter;

    public static double RequiredExtent(double currentExtent, double contentEdge, double initialExtent)
    {
        var extent = double.IsFinite(currentExtent) ? Math.Max(initialExtent, currentExtent) : initialExtent;
        if (!double.IsFinite(contentEdge) || contentEdge < extent - AutoGrowMargin) return extent;
        return extent + (Math.Floor((contentEdge + AutoGrowMargin - extent) / GrowthStep) + 1d) * GrowthStep;
    }

    public static IEnumerable<System.Windows.Rect> ContentBounds(PageDocument document)
    {
        foreach (var stroke in document.Strokes) if (!stroke.Bounds.IsEmpty) yield return stroke.Bounds;
        foreach (var tag in document.Tags)
        {
            var halfSize = 18d * tag.Scale;
            yield return new(tag.DocumentX - halfSize, tag.DocumentY - halfSize, halfSize * 2, halfSize * 2);
        }
        foreach (var text in document.TextItems)
            if (!string.IsNullOrWhiteSpace(text.Text)) yield return new(text.X, text.Y, Math.Max(1, text.Width), Math.Max(1, text.Height));
        foreach (var item in document.Objects) yield return new(item.X, item.Y, Math.Max(1, item.Width), Math.Max(1, item.Height));
    }

    public static System.Windows.Rect ExportBounds(PageDocument document)
    {
        var result = System.Windows.Rect.Empty;
        foreach (var bounds in ContentBounds(document)) result.Union(bounds);
        if (result.IsEmpty) return new(0, 0, InitialWidth, InitialHeight);
        result.Inflate(ExportPadding, ExportPadding);
        return result;
    }

    public static bool EnsureCapacity(PageAppearance appearance, PageDocument document)
    {
        if (appearance.PaperKind != PaperKind.Infinite) return false;
        var right = 0d;
        var bottom = 0d;
        foreach (var bounds in ContentBounds(document))
        {
            // Content entirely above or left of the origin cannot grow the paper.
            if (bounds.IsEmpty || bounds.Right < 0 || bounds.Bottom < 0) continue;
            right = Math.Max(right, bounds.Right);
            bottom = Math.Max(bottom, bounds.Bottom);
        }
        var width = RequiredExtent(appearance.InfiniteWidth, right, InitialWidth);
        var height = RequiredExtent(appearance.InfiniteHeight, bottom, InitialHeight);
        if (width == appearance.InfiniteWidth && height == appearance.InfiniteHeight) return false;
        appearance.InfiniteWidth = width;
        appearance.InfiniteHeight = height;
        return true;
    }
}
