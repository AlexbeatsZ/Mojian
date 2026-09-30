using System.Text.Json.Serialization;
using System.Windows;

namespace Notein_win.Models;

public sealed class InkPoint
{
    public float X { get; set; }
    public float Y { get; set; }
    public float Pressure { get; set; } = 0.5f;
    public float TiltX { get; set; }
    public float TiltY { get; set; }
    public long ElapsedMilliseconds { get; set; }

    public InkPoint() { }

    public InkPoint(float x, float y, float pressure = 0.5f, float tiltX = 0, float tiltY = 0, long elapsedMilliseconds = 0)
    {
        X = x;
        Y = y;
        Pressure = Math.Clamp(pressure, 0.01f, 1f);
        TiltX = tiltX;
        TiltY = tiltY;
        ElapsedMilliseconds = elapsedMilliseconds;
    }

    public Point ToPoint() => new(X, Y);
}

public sealed class StrokeRecord
{
    private float _widthMm = 0.4f;
    private List<InkPoint> _points = new();
    private Rect? _cachedBounds;

    public string Id { get; set; } = IdFactory.New("stroke");
    public DrawingToolKind Kind { get; set; } = DrawingToolKind.Pen;
    public InkPalette Color { get; set; } = InkPalette.Black;
    public float WidthMm
    {
        get => _widthMm;
        set
        {
            _widthMm = value;
            _cachedBounds = null;
        }
    }
    public int PressurePercent { get; set; } = 200;
    public int InkFlowPercent { get; set; } = 100;
    public int OpacityPercent { get; set; } = 100;
    public string BrushProfile { get; set; } = "fountain-v1";
    public List<InkPoint> Points
    {
        get => _points;
        set
        {
            _points = value ?? new List<InkPoint>();
            _cachedBounds = null;
        }
    }

    [JsonIgnore]
    public Rect Bounds
    {
        get
        {
            if (_cachedBounds is { } cached) return cached;
            if (Points.Count == 0) return Rect.Empty;
            var minX = float.PositiveInfinity;
            var minY = float.PositiveInfinity;
            var maxX = float.NegativeInfinity;
            var maxY = float.NegativeInfinity;
            foreach (var point in Points)
            {
                minX = Math.Min(minX, point.X);
                minY = Math.Min(minY, point.Y);
                maxX = Math.Max(maxX, point.X);
                maxY = Math.Max(maxY, point.Y);
            }
            var margin = Math.Max(1, WidthMm * 3.78);
            _cachedBounds = new Rect(minX - margin, minY - margin, Math.Max(1, maxX - minX + margin * 2), Math.Max(1, maxY - minY + margin * 2));
            return _cachedBounds.Value;
        }
    }

    public StrokeRecord() { }

    public StrokeRecord(string id, DrawingToolKind kind, InkPalette color, float widthMm, int opacityPercent = 100, IEnumerable<InkPoint>? points = null)
    {
        Id = id;
        Kind = kind;
        Color = color;
        WidthMm = widthMm;
        OpacityPercent = opacityPercent;
        Points = points?.ToList() ?? new List<InkPoint>();
    }

    public void ComputeBounds() => _ = Bounds;

    public void InvalidateBounds() => _cachedBounds = null;
}

public enum CanvasTagKind { Important, Question }

public static class CanvasTagKindExtensions
{
    public static string GetLabel(this CanvasTagKind kind) => kind == CanvasTagKind.Important ? "重要" : "问题";
}

public sealed class CanvasTag
{
    public string Id { get; set; } = IdFactory.New("tag");
    public CanvasTagKind Kind { get; set; }
    public float DocumentX { get; set; }
    public float DocumentY { get; set; }
    public float Scale { get; set; } = 1f;

    public CanvasTag() { }
    public CanvasTag(CanvasTagKind kind, float documentX, float documentY) { Kind = kind; DocumentX = documentX; DocumentY = documentY; }
}

public sealed class TextItem
{
    public string Id { get; set; } = IdFactory.New("text");
    public float X { get; set; }
    public float Y { get; set; }
    public float Width { get; set; } = 320;
    public float Height { get; set; } = 60;
    public string Text { get; set; } = string.Empty;
    public string FontFamily { get; set; } = "Segoe UI Variable, Microsoft YaHei UI";
    public double FontSize { get; set; } = 16;
    public bool Bold { get; set; }
    public bool Italic { get; set; }
    public bool Underline { get; set; }
    public bool Strikethrough { get; set; }
    public bool Subscript { get; set; }
    public InkPalette Color { get; set; } = InkPalette.Black;
    public string ForegroundHex { get; set; } = string.Empty;
    public string BackgroundHex { get; set; } = "Transparent";
    public int IndentLevel { get; set; }
    public string Alignment { get; set; } = "Left";
    public double LineSpacing { get; set; } = 1.0;
    public string StyleName { get; set; } = "正文";
}

public sealed class PageViewport
{
    public float Scale { get; set; } = 1f;
    public float TranslationX { get; set; }
    public float TranslationY { get; set; }
}

public sealed class PageDocument
{
    public int Version { get; set; } = 2;
    public string NotebookId { get; set; } = string.Empty;
    public string PageId { get; set; } = string.Empty;
    public long Revision { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public PageViewport Viewport { get; set; } = new();
    public List<StrokeRecord> Strokes { get; set; } = new();
    public List<CanvasTag> Tags { get; set; } = new();
    public List<TextItem> TextItems { get; set; } = new();
    public List<PageObject> Objects { get; set; } = new();

    public PageDocument() { }
    public PageDocument(string notebookId, string pageId) { NotebookId = notebookId; PageId = pageId; }
}
