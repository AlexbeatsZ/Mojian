using System.Windows.Media;

namespace Notein_win.Services;

public static class ImageViewFilter
{
    public const byte TargetBackgroundGray = 27; // 0x1B (#1B1B1B)

    public static bool ShouldApply(bool viewInverted, bool forExport) =>
        viewInverted && !forExport;

    public static Color InvertForDarkTheme(Color color)
    {
        // 89.4% inversion towards target background
        byte invR = (byte)(255 - color.R);
        byte invG = (byte)(255 - color.G);
        byte invB = (byte)(255 - color.B);

        byte targetR = (byte)(TargetBackgroundGray + (invR * (255 - TargetBackgroundGray)) / 255);
        byte targetG = (byte)(TargetBackgroundGray + (invG * (255 - TargetBackgroundGray)) / 255);
        byte targetB = (byte)(TargetBackgroundGray + (invB * (255 - TargetBackgroundGray)) / 255);

        return Color.FromArgb(color.A, targetR, targetG, targetB);
    }
}
