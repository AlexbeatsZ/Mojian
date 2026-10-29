namespace Notein_win.Models;

// Image IDs belong to the current output scope; only reusable dialog choices are remembered.
public sealed record PrintPreferences(ExportScope Scope = ExportScope.Page, ExportPaper Paper = ExportPaper.Auto,
    bool Landscape = false, double MarginMm = 6, bool TrimTrailingBlank = true,
    string PageRange = "", bool HideBackgroundFormat = false)
{
    public ExportOptions Restore() => new(
        Scope: Enum.IsDefined(Scope) ? Scope : ExportScope.Page,
        Paper: Enum.IsDefined(Paper) ? Paper : ExportPaper.Auto,
        Landscape: Landscape, MarginMm: new[] { 0d, 6, 12, 20 }.Contains(MarginMm) ? MarginMm : 6,
        TrimTrailingBlank: TrimTrailingBlank, PageRange: PageRange ?? "", Printing: true,
        HideBackgroundFormat: HideBackgroundFormat);

    public static PrintPreferences From(ExportOptions options) => new(options.Scope, options.Paper,
        options.Landscape, options.MarginMm, options.TrimTrailingBlank, options.PageRange, options.HideBackgroundFormat);
}
