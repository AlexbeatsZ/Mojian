using System.Runtime.CompilerServices;
using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

// A fountain-v2/pen-style outline is the union of one disc per modeled sample and a trapezoid
// between neighbours. WPF ellipse intersection with that stored path (tens of thousands of path
// characters) took about 8.5 ms per candidate; the same shape tested from its samples takes µs.
public static class StrokeEraserHitTest
{
    private sealed class Entry { public long Version = -1; public IReadOnlyList<FountainV2Model.Sample> Samples = []; }
    private static readonly ConditionalWeakTable<StrokeRecord, Entry> Cache = new();

    public static bool UsesModeledOutline(StrokeRecord stroke) =>
        stroke.Points.Count > 0 && stroke.BrushProfile is FountainV2Model.Profile or PenStyle.Profile;

    public static bool ModeledOutlineHitsCircle(StrokeRecord stroke, Point center, double radius)
    {
        var entry = Cache.GetOrCreateValue(stroke);
        if (entry.Version != stroke.RenderVersion)
        {
            entry.Samples = FountainV2Model.Model(stroke);
            entry.Version = stroke.RenderVersion;
        }
        return SamplesHitCircle(entry.Samples, center, radius);
    }

    public static bool SamplesHitCircle(IReadOnlyList<FountainV2Model.Sample> samples, Point center, double radius)
    {
        for (var i = 0; i < samples.Count; i++)
        {
            var sample = samples[i];
            var half = sample.Width / 2;
            if (half > 1e-8 && (center - sample.Position).Length <= radius + half) return true;
            if (i == 0) continue;
            var previous = samples[i - 1];
            var delta = sample.Position - previous.Position;
            var length = delta.Length;
            if (length <= 1e-8) continue;
            var offset = center - previous.Position;
            var t = (offset.X * delta.X + offset.Y * delta.Y) / (length * length);
            if (t is < 0 or > 1) continue;
            // The trapezoid's cross-section interpolates linearly between the two sample widths.
            var across = Math.Abs(offset.X * delta.Y - offset.Y * delta.X) / length;
            if (across <= radius + previous.Width / 2 + (half - previous.Width / 2) * t) return true;
        }
        return false;
    }
}
