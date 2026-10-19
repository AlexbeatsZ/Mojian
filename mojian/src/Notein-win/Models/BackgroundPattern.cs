using System.IO;
using System.Text.Json;

namespace Notein_win.Models;

// All lengths in this contract are 96-DPI document units, never screen pixels.
public sealed record BackgroundPattern(
    string Id, string WindowsName, string Label, string Category, double Spacing,
    bool Margin = false, bool Divider = false);

public sealed record BackgroundPalette(string Line, string Major, string Dot, string Margin);

public sealed class BackgroundPatternCatalog
{
    public double BaseSpacing { get; init; }
    public double LineWidth { get; init; }
    public bool ScreenLineWidth { get; init; }
    public double MarginWidth { get; init; }
    public double MarginX { get; init; }
    public double DotRadius { get; init; }
    public int MajorEvery { get; init; }
    public double A4PageGap { get; init; }
    public BackgroundPalette Dark { get; init; } = null!;
    public BackgroundPalette Light { get; init; } = null!;
    public List<BackgroundPattern> Formats { get; init; } = [];

    public static BackgroundPatternCatalog Shared { get; } = Load();
    private static BackgroundPatternCatalog Load()
    {
        using var stream = typeof(BackgroundPatternCatalog).Assembly.GetManifestResourceStream("Mojian.PageBackgrounds.json")
            ?? throw new InvalidDataException("Missing shared page background contract.");
        return JsonSerializer.Deserialize<BackgroundPatternCatalog>(stream,
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
    }

    public BackgroundPattern For(CanvasBackgroundFormat format) => Formats.Single(item => item.WindowsName == format.ToString());
}
