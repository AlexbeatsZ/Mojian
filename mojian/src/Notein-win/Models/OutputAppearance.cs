using System.Windows.Media;

namespace Notein_win.Models;

/// <summary>Output-only appearance; never changes a page, image flag or original asset.</summary>
public sealed record OutputAppearance(bool Printing = false, IReadOnlySet<string>? InvertedImageIds = null, bool HideBackgroundFormat = false)
{
    public bool PrintsDark(PageTheme theme) => Printing && theme == PageTheme.Dark;

    // Selection toggles the displayed image. By default, restore already inverted images for print.
    public bool ImageInverted(string id, bool displayedInverted, PageTheme theme) =>
        displayedInverted ^ (PrintsDark(theme) && (InvertedImageIds?.Contains(id) ?? displayedInverted));

    public static Color PrintInk(Color color) =>
        color.R == color.G && color.G == color.B && color.R is 239 or 244 or 255
            ? Color.FromArgb(color.A, 0, 0, 0) : color;

    public static Color PrintPaper(Color color) =>
        color.R == color.G && color.G == color.B && color.R <= 32
            ? Color.FromArgb(color.A, 255, 255, 255) : color;
}

public static class ImageImportPolicy
{
    public static bool? Resolve(ImageImportDisplayMode mode, bool darkPage) => !darkPage ? false : mode switch
    {
        ImageImportDisplayMode.Invert => true,
        ImageImportDisplayMode.Original => false,
        _ => null,
    };
}
