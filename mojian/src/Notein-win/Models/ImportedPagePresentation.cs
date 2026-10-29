using System.Windows;
using System.Windows.Media;

namespace Notein_win.Models;

public static class ImportedPagePresentation
{
    public static bool Applies(string? pageId) => pageId != null &&
        (pageId.StartsWith("page-notein-", StringComparison.Ordinal) || pageId.StartsWith("page-onenote-", StringComparison.Ordinal));

    public static Rect? PaperBounds(PageAppearance appearance, PageDocument? document)
    {
        if (document == null || !Applies(document.PageId) || appearance.PaperKind == PaperKind.Pdf) return null;
        var paper = appearance.PaperKind == PaperKind.Infinite
            ? new Rect(0, 0, appearance.InfiniteWidth, appearance.InfiniteHeight)
            : new Rect(0, 0, appearance.Width, appearance.Height * Math.Max(1, appearance.SheetCount) +
                A4PageLayout.PageGap * Math.Max(0, appearance.SheetCount - 1));
        var content = Rect.Empty;
        foreach (var bounds in InfinitePageLayout.ContentBounds(document)) content.Union(bounds);
        if (!content.IsEmpty)
        {
            content.Inflate(InfinitePageLayout.ExportPadding, InfinitePageLayout.ExportPadding);
            paper.Union(content);
        }
        return paper;
    }

    public static Color PaperColor(bool dark) => dark ? Color.FromRgb(27, 27, 27) : Color.FromRgb(250, 250, 250);

    public static bool InvertImages(string? pageId, PageAppearance appearance) => Applies(pageId) &&
        IsDark(appearance.SourcePageColor ?? appearance.PageColor) != (appearance.Theme == PageTheme.Dark);

    public static bool ImageInverted(string? pageId, PageAppearance appearance, PageObject item) =>
        item.ViewInverted ^ (IsImportedItem(item.Id) && InvertImages(pageId, appearance));

    public static bool IsImportedItem(string id) => id.Contains("-notein-", StringComparison.Ordinal) || id.Contains("-onenote-", StringComparison.Ordinal);

    public static bool IsDark(string color)
    {
        try { return Luminance((Color)ColorConverter.ConvertFromString(color)) < .5; }
        catch { return false; }
    }

    public static Color Ink(Color source, bool dark, bool inverted)
    {
        var neutral = Math.Max(source.R, Math.Max(source.G, source.B)) - Math.Min(source.R, Math.Min(source.G, source.B)) <= 24;
        if (neutral && inverted)
        {
            var value = 255 - (source.R + source.G + source.B) / 3;
            source = Color.FromArgb(source.A, (byte)value, (byte)value, (byte)value);
        }
        if (neutral && (dark ? Luminance(source) < .02 : Luminance(source) > .9))
            return Color.FromArgb(source.A, PaperColor(dark).R, PaperColor(dark).G, PaperColor(dark).B);
        var background = Luminance(PaperColor(dark));
        for (var step = 0; step <= 100; step++)
        {
            var amount = step / 100d;
            var target = dark ? 255 : 0;
            var candidate = Color.FromArgb(source.A, (byte)Math.Round(source.R + (target - source.R) * amount, MidpointRounding.AwayFromZero),
                (byte)Math.Round(source.G + (target - source.G) * amount, MidpointRounding.AwayFromZero),
                (byte)Math.Round(source.B + (target - source.B) * amount, MidpointRounding.AwayFromZero));
            var luminance = Luminance(candidate);
            if ((Math.Max(luminance, background) + .05) / (Math.Min(luminance, background) + .05) >= 4.5) return candidate;
        }
        return source;
    }

    private static double Luminance(Color color)
    {
        static double Linear(byte channel) => channel / 255d <= .04045 ? channel / 255d / 12.92 : Math.Pow((channel / 255d + .055) / 1.055, 2.4);
        return .2126 * Linear(color.R) + .7152 * Linear(color.G) + .0722 * Linear(color.B);
    }
}
