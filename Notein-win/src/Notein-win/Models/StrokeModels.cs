using System.Windows;
using System.Windows.Media;

namespace Notein_win.Models;

public record struct DocumentPoint(float X, float Y)
{
    public static implicit operator Point(DocumentPoint p) => new(p.X, p.Y);
    public static implicit operator DocumentPoint(Point p) => new((float)p.X, (float)p.Y);
}

public class InkPoint
{
    public float X { get; set; }
    public float Y { get; set; }
    public float Pressure { get; set; } = 0.5f;
    public float TiltX { get; set; }
    public float TiltY { get; set; }
    public long TimestampMs { get; set; }

    public InkPoint(float x, float y, float pressure = 0.5f, float tiltX = 0f, float tiltY = 0f, long timestampMs = 0L)
    {
        X = x;
        Y = y;
        Pressure = pressure;
        TiltX = tiltX;
        TiltY = tiltY;
        TimestampMs = timestampMs;
    }

    public Point ToPoint() => new(X, Y);
    public static implicit operator Point(InkPoint pt) => new(pt.X, pt.Y);
}

public class CanvasTag
{
    public CanvasTagKind Kind { get; set; }
    public float DocumentX { get; set; }
    public float DocumentY { get; set; }

    public CanvasTag(CanvasTagKind kind, float documentX, float documentY)
    {
        Kind = kind;
        DocumentX = documentX;
        DocumentY = documentY;
    }
}

public class StrokeRecord
{
    public string Id { get; set; }
    public DrawingToolKind Kind { get; set; }
    public InkPalette Color { get; set; }
    public float WidthMm { get; set; }
    public int OpacityPercent { get; set; }
    public List<InkPoint> Points { get; set; } = new();
    public Rect Bounds { get; set; }
    public bool IsDeleted { get; set; }

    public StrokeRecord(
        string id,
        DrawingToolKind kind,
        InkPalette color,
        float widthMm,
        int opacityPercent = 100,
        IEnumerable<InkPoint>? points = null)
    {
        Id = id;
        Kind = kind;
        Color = color;
        WidthMm = widthMm;
        OpacityPercent = opacityPercent;
        if (points != null)
        {
            Points = points.ToList();
            ComputeBounds();
        }
    }

    public void ComputeBounds()
    {
        if (Points.Count == 0)
        {
            Bounds = Rect.Empty;
            return;
        }

        float minX = float.MaxValue, minY = float.MaxValue;
        float maxX = float.MinValue, maxY = float.MinValue;

        foreach (var pt in Points)
        {
            if (pt.X < minX) minX = pt.X;
            if (pt.Y < minY) minY = pt.Y;
            if (pt.X > maxX) maxX = pt.X;
            if (pt.Y > maxY) maxY = pt.Y;
        }

        var margin = WidthMm * 2f;
        Bounds = new Rect(minX - margin, minY - margin, Math.Max(1f, maxX - minX + margin * 2), Math.Max(1f, maxY - minY + margin * 2));
    }
}
