namespace Notein_win.Models;

public static class A4PageLayout
{
    public static double PageGap => BackgroundPatternCatalog.Shared.A4PageGap;
    public const double AutoGrowMargin = 24d * 96d / 25.4d;

    public static int RequiredSheetCount(
        double contentBottom,
        int currentSheetCount,
        double pageHeight)
    {
        var count = Math.Max(1, currentSheetCount);
        var height = Math.Max(1d, pageHeight);
        var observedBottom = double.IsFinite(contentBottom) ? contentBottom : 0d;
        while (observedBottom >= (count - 1) * (height + PageGap) + height - AutoGrowMargin)
            count++;
        return count;
    }
}
