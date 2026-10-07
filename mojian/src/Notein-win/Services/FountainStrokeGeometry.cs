using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Services;

// One nonzero-filled union of the same round-ended, pressure-sized segments.
// Inner overlaps are filled once instead of submitting one stroked path per sample.
public static class FountainStrokeGeometry
{
    public static StreamGeometry Create(StrokeRecord stroke)
    {
        var geometry = new StreamGeometry { FillRule = FillRule.Nonzero };
        using (var context = geometry.Open())
        {
            for (var i = 1; i < stroke.Points.Count; i++)
            {
                var start = HandwritingEngine.SmoothedPoint(stroke.Points, i - 1);
                var end = HandwritingEngine.SmoothedPoint(stroke.Points, i);
                var radius = HandwritingEngine.SegmentWidth(stroke, i) / 2;
                var delta = end - start;
                var normal = delta.Length > 1e-8 ? new Vector(delta.Y, -delta.X) / delta.Length * radius : new Vector(0, -radius);
                var arc = new Size(radius, radius);
                context.BeginFigure(start + normal, true, true);
                context.LineTo(end + normal, true, false);
                context.ArcTo(end - normal, arc, 0, false, SweepDirection.Clockwise, true, false);
                context.LineTo(start - normal, true, false);
                context.ArcTo(start + normal, arc, 0, false, SweepDirection.Clockwise, true, false);
            }
        }
        geometry.Freeze();
        return geometry;
    }
}
