namespace Notein_win.Models;

public static class PageThemeAppearance
{
    private static readonly (string Light, string Dark)[] Colors =
    [
        ("#FAFAFA", "#1B1B1B"), ("#FFFFFF", "#1B1B1B"), ("#F3E9D2", "#302A21"), ("#E2EDF8", "#1D2B38"),
        ("#E0F1E7", "#1E3028"), ("#F4E2E8", "#352329"),
        // Earlier creation dialogs used these dark variants.
        ("#F3E9D2", "#2D2820"), ("#E2EDF8", "#1B2735"),
        ("#E0F1E7", "#1E2D24"), ("#F4E2E8", "#2B1D2E"),
    ];

    public static PageAppearance Apply(PageAppearance source, PageTheme theme)
    {
        var result = source.Clone();
        result.Theme = theme;
        foreach (var pair in Colors)
        {
            if (!string.Equals(source.PageColor, pair.Light, StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(source.PageColor, pair.Dark, StringComparison.OrdinalIgnoreCase)) continue;
            result.PageColor = theme == PageTheme.Dark ? pair.Dark : pair.Light;
            break;
        }
        return result;
    }
}
