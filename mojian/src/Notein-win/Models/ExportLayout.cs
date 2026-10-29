using System.Windows;

namespace Notein_win.Models;

public enum ExportScope { Page, Section, Notebook }
public enum ExportFormat { Pdf, Package, PngZip, Xps }
public enum ExportPaper { Auto, A4, A3, Letter }
public sealed record ExportOptions(ExportScope Scope = ExportScope.Page, ExportFormat Format = ExportFormat.Pdf,
    ExportPaper Paper = ExportPaper.Auto, bool Landscape = false, double MarginMm = 6,
    bool TrimTrailingBlank = true, string PageRange = "", bool Printing = false,
    IReadOnlySet<string>? InvertedImageIds = null, bool HideBackgroundFormat = false);
public sealed record ExportImageChoice(string Id, string Label, bool DisplayInverted);
public sealed record ExportSheet(Rect Source, double Width, double Height, double Scale, double Margin)
{
    public Rect Output => new(0, 0, Width, Height);
}

/// <summary>Physical sheets, independent of viewport and historically grown empty canvas.</summary>
public static class ExportLayout
{
    public static IReadOnlyList<ExportSheet> Plan(NotePage page, PageDocument document, ExportOptions options)
    {
        var content = Rect.Empty;
        foreach (var bounds in InfinitePageLayout.ContentBounds(document)) content.Union(bounds);
        var appearance = page.Appearance;
        if (ImportedPagePresentation.Applies(document.PageId) && appearance.PaperKind != PaperKind.Pdf)
        {
            appearance = appearance.Clone();
            appearance.PaperKind = PaperKind.Infinite;
        }
        if (appearance.PaperKind == PaperKind.Pdf && options.Paper == ExportPaper.Auto)
        {
            var source = new Rect(0, 0, appearance.Width, appearance.Height);
            return [new(source, (page.PdfWidthPoints ?? source.Width * .75) / .75,
                (page.PdfHeightPoints ?? source.Height * .75) / .75, 1, 0)];
        }
        var (w, h) = options.Paper switch { ExportPaper.A3 => (297d, 420d), ExportPaper.Letter => (215.9, 279.4), _ => (210d, 297d) };
        if (options.Landscape) (w, h) = (h, w);
        var unit = InfinitePageLayout.UnitsPerMillimeter;
        w *= unit; h *= unit;
        var margin = Math.Clamp(options.MarginMm, 0, 40) * unit;
        var sheets = new List<ExportSheet>();
        if (appearance.PaperKind == PaperKind.Pdf)
        {
            var source = new Rect(0, 0, appearance.Width, appearance.Height);
            return [new(source, w, h, Math.Min((w - 2 * margin) / source.Width, (h - 2 * margin) / source.Height), margin)];
        }
        if (appearance.PaperKind == PaperKind.A4)
        {
            var stride = appearance.Height + A4PageLayout.PageGap;
            var count = options.TrimTrailingBlank
                ? Math.Max(1, (int)Math.Ceiling((content.IsEmpty ? 0 : content.Bottom) / stride))
                : Math.Max(appearance.SheetCount, content.IsEmpty ? 1 : (int)Math.Ceiling(content.Bottom / stride));
            if (count > 10000) throw new InvalidOperationException("分页超过 10000 页，请缩小导出范围。");
            for (var i = 0; i < count; i++)
            {
                var top = i == 0 ? Math.Min(0, content.IsEmpty ? 0 : content.Top) : i * stride;
                var bottom = i * stride + appearance.Height;
                // Ink in a workspace gap belongs to the preceding sheet; never silently drop it.
                if (!content.IsEmpty && InfinitePageLayout.ContentBounds(document).Any(b => b.Bottom > bottom && b.Top < (i + 1) * stride))
                    bottom = (i + 1) * stride;
                var left = Math.Min(0, content.IsEmpty ? 0 : content.Left);
                var right = Math.Max(appearance.Width, content.IsEmpty ? 0 : content.Right);
                var source = new Rect(left, top, right - left, bottom - top);
                var scale = Math.Min((w - 2 * margin) / source.Width, (h - 2 * margin) / source.Height);
                sheets.Add(new(source, w, h, scale, margin));
            }
        }
        else
        {
            var source = appearance.PaperKind == PaperKind.Pdf ? new Rect(0, 0, appearance.Width, appearance.Height)
                : content.IsEmpty ? new Rect(0, 0, w - 2 * margin, h - 2 * margin) : content;
            var scale = Math.Min(1, (w - 2 * margin) / source.Width);
            var sliceHeight = (h - 2 * margin) / scale;
            var count = Math.Max(1, (int)Math.Ceiling(source.Height / sliceHeight - 1e-9));
            if (count > 10000) throw new InvalidOperationException("分页超过 10000 页，请缩小导出范围。");
            for (var i = 0; i < count; i++)
                sheets.Add(new(new Rect(source.Left, source.Top + i * sliceHeight, source.Width,
                    Math.Min(sliceHeight, source.Bottom - source.Top - i * sliceHeight)), w, h, scale, margin));
        }
        return sheets;
    }

    public static IReadOnlyList<int> SelectPages(string range, int count)
    {
        if (string.IsNullOrWhiteSpace(range)) return Enumerable.Range(0, count).ToArray();
        var result = new SortedSet<int>();
        foreach (var part in range.Replace('，', ',').Split(','))
        {
            var ends = part.Trim().Split('-');
            if (ends.Length > 2 || !int.TryParse(ends[0], out var start) ||
                !int.TryParse(ends[^1], out var end) || start < 1 || end < start || end > count)
                throw new ArgumentException($"页码范围无效。请输入 1–{count} 内的页码，例如 1-3,5。");
            for (var i = start; i <= end; i++) result.Add(i - 1);
        }
        return result.ToArray();
    }
}
