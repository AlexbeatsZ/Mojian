using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

public static class HandwritingEngine
{
    private const double UnitsPerMillimeter = 96d / 25.4d;

    public static double SegmentWidth(StrokeRecord stroke, int segmentIndex)
    {
        if (stroke.Points.Count == 0) return Math.Max(0.35, stroke.WidthMm * UnitsPerMillimeter);
        var index = Math.Clamp(segmentIndex, 1, Math.Max(1, stroke.Points.Count - 1));
        var p0 = stroke.Points[Math.Max(0, index - 1)];
        var p1 = stroke.Points[Math.Min(index, stroke.Points.Count - 1)];
        var progress = index / (double)Math.Max(1, stroke.Points.Count - 1);
        var endTaper = stroke.Points.Count < 4 ? 1d : Math.Min(1, Math.Min(progress / 0.12, (1 - progress) / 0.16));
        var taper = 0.24 + 0.76 * Math.Clamp(endTaper, 0, 1);
        var pressureCorrection = Math.Max(0.2, stroke.PressurePercent / 100d);
        var pressureCurve = Math.Pow(Math.Clamp(p0.Pressure, 0.03, 1), 2d / pressureCorrection);
        var elapsed = Math.Max(1, p1.ElapsedMilliseconds - p0.ElapsedMilliseconds);
        var speed = Distance(p0, p1) / elapsed;
        var flow = 1d / (1d + speed * Math.Max(0, stroke.InkFlowPercent / 100d) * 0.14);
        return Math.Max(0.35, stroke.WidthMm * UnitsPerMillimeter * (0.38 + pressureCurve * 0.78) * (0.72 + flow * 0.28) * taper);
    }

    public static Point SmoothedPoint(IReadOnlyList<InkPoint> points, int index)
    {
        if (points.Count < 3 || index <= 0 || index >= points.Count - 1) return points[index].ToPoint();
        var previous = points[index - 1];
        var current = points[index];
        var next = points[index + 1];
        return new Point(
            previous.X * 0.2 + current.X * 0.6 + next.X * 0.2,
            previous.Y * 0.2 + current.Y * 0.6 + next.Y * 0.2);
    }

    public static Point? Predict(IReadOnlyList<InkPoint> points, double horizonMilliseconds = 10, double maxDistance = 18)
    {
        if (points.Count < 2) return null;
        var current = points[^1];
        var previous = points[^2];
        var elapsed = Math.Max(1, current.ElapsedMilliseconds - previous.ElapsedMilliseconds);
        double velocityX = (current.X - previous.X) / elapsed;
        double velocityY = (current.Y - previous.Y) / elapsed;
        if (points.Count >= 3)
        {
            var older = points[^3];
            var olderElapsed = Math.Max(1, previous.ElapsedMilliseconds - older.ElapsedMilliseconds);
            velocityX = velocityX * 0.7 + (previous.X - older.X) / olderElapsed * 0.3;
            velocityY = velocityY * 0.7 + (previous.Y - older.Y) / olderElapsed * 0.3;
        }
        var dx = velocityX * horizonMilliseconds;
        var dy = velocityY * horizonMilliseconds;
        var distance = Math.Sqrt(dx * dx + dy * dy);
        if (distance < 0.05) return null;
        if (distance > maxDistance)
        {
            var clamp = maxDistance / distance;
            dx *= clamp;
            dy *= clamp;
        }
        return new Point(current.X + dx, current.Y + dy);
    }

    private static double Distance(InkPoint left, InkPoint right)
    {
        var dx = right.X - left.X;
        var dy = right.Y - left.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }
}
