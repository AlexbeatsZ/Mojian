using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

public enum ShapeKind { Line, Arrow, Circle, Ellipse, Rectangle, Triangle, Diamond }

public static class ShapeGeometry
{
    public static string Label(ShapeKind kind) => kind switch
    {
        ShapeKind.Line => "直线", ShapeKind.Arrow => "箭头", ShapeKind.Circle => "圆形",
        ShapeKind.Ellipse => "椭圆", ShapeKind.Rectangle => "矩形", ShapeKind.Triangle => "三角形",
        _ => "菱形"
    };

    public static List<InkPoint> Points(ShapeKind kind, Point start, Point end)
    {
        var x = Math.Min(start.X, end.X); var y = Math.Min(start.Y, end.Y);
        var w = Math.Abs(end.X - start.X); var h = Math.Abs(end.Y - start.Y);
        var points = new List<Point>();
        switch (kind)
        {
            case ShapeKind.Line: points.AddRange([start, end]); break;
            case ShapeKind.Arrow:
                var angle = Math.Atan2(end.Y - start.Y, end.X - start.X);
                var head = Math.Min(18, (end - start).Length * .25);
                points.AddRange([start, end,
                    new(end.X - head * Math.Cos(angle - Math.PI / 6), end.Y - head * Math.Sin(angle - Math.PI / 6)), end,
                    new(end.X - head * Math.Cos(angle + Math.PI / 6), end.Y - head * Math.Sin(angle + Math.PI / 6))]);
                break;
            case ShapeKind.Circle:
            case ShapeKind.Ellipse:
                if (kind == ShapeKind.Circle)
                {
                    w = h = Math.Max(w, h);
                    x = end.X >= start.X ? start.X : start.X - w;
                    y = end.Y >= start.Y ? start.Y : start.Y - h;
                }
                for (var i = 0; i <= 128; i++)
                {
                    var a = i * 2 * Math.PI / 128;
                    points.Add(new(x + w / 2 + w / 2 * Math.Cos(a), y + h / 2 + h / 2 * Math.Sin(a)));
                }
                break;
            case ShapeKind.Rectangle: points.AddRange([new(x,y), new(x+w,y), new(x+w,y+h), new(x,y+h), new(x,y)]); break;
            case ShapeKind.Triangle: points.AddRange([new(x+w/2,y), new(x+w,y+h), new(x,y+h), new(x+w/2,y)]); break;
            case ShapeKind.Diamond: points.AddRange([new(x+w/2,y), new(x+w,y+h/2), new(x+w/2,y+h), new(x,y+h/2), new(x+w/2,y)]); break;
        }
        return points.Select((p,i) => new InkPoint((float)p.X, (float)p.Y, .5f, elapsedMilliseconds: i * 8)).ToList();
    }
}

/// Screen-space stationarity, independent of zoom and event frequency. A timer must call TrySnap
/// even if the digitizer stops delivering MOVE events. Once snapped, moving adjusts the endpoint.
public sealed class HoldToStraighten
{
    private Point _start, _anchor, _last;
    private long _since;
    private double _length;
    public bool Active { get; private set; }
    public bool Snapped { get; private set; }
    public void Begin(Point point, long now) { _start = _anchor = _last = point; _since = now; _length = 0; Active = true; Snapped = false; }
    public void Move(Point point, long now, double slop)
    {
        if (!Active) return;
        _last = point;
        if ((point - _anchor).Length > slop) { _length += (point - _anchor).Length; _anchor = point; _since = now; }
    }
    public bool TrySnap(long now, int delay)
    {
        var chord = (_last - _start).Length;
        if (!Active || Snapped || now - _since < Math.Clamp(delay, 200, 2000) || chord < 8 || chord < _length * .65) return false;
        return Snapped = true;
    }
    public void Cancel() { Active = Snapped = false; }
}
