using System.Runtime.CompilerServices;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Services;

// Retain document-space drawings, not raster images, so zoom stays sharp.
public sealed class StrokeDrawingCache
{
    private readonly ConditionalWeakTable<StrokeRecord, Entry> _entries = new();
    public int BuildCount { get; private set; }
    private sealed record Signature(long Version, int Points, DrawingToolKind Kind, InkPalette Color,
        string ColorHex, float Width, int Pressure, int Flow, int Opacity, string Profile, bool Dark, bool Export);
    private sealed class Entry { public Signature? Signature; public DrawingGroup? Drawing; }

    public DrawingGroup Get(StrokeRecord stroke, bool dark, bool export, Action<DrawingContext> draw)
    {
        var signature = new Signature(stroke.RenderVersion, stroke.Points.Count, stroke.Kind, stroke.Color,
            stroke.ColorHex, stroke.WidthMm, stroke.PressurePercent, stroke.InkFlowPercent, stroke.OpacityPercent, stroke.BrushProfile, dark, export);
        var entry = _entries.GetOrCreateValue(stroke);
        if (entry.Signature == signature && entry.Drawing != null) return entry.Drawing;
        var drawing = new DrawingGroup();
        using (var context = drawing.Open()) draw(context);
        drawing.Freeze();
        entry.Signature = signature;
        entry.Drawing = drawing;
        BuildCount++;
        return drawing;
    }
}
