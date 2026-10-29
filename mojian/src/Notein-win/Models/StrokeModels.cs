using System.Text.Json.Serialization;
using System.Windows;
using System.Windows.Media;

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
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
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
            InvalidateBounds();
        }
    }
    public int PressurePercent { get; set; } = 200;
    public int InkFlowPercent { get; set; } = 100;
    public int OpacityPercent { get; set; } = 100;
    public string BrushProfile { get; set; } = "fountain-v1";
    public string ColorHex { get; set; } = string.Empty;
    public string LayerId { get; set; } = string.Empty;
    public long ZOrder { get; set; }
    // A filled vector outline, independent of Mojian's authoring brush. The affine
    // transform lets move/resize retain the original pressure-shaped outline.
    public string VectorPath { get; set; } = string.Empty;
    public double[]? VectorBounds { get; set; }
    public double[] VectorTransform { get; set; } = [1, 0, 0, 1, 0, 0];
    public System.Text.Json.JsonElement? SourceBrush { get; set; }

    [JsonIgnore] private Geometry? _vectorGeometry;
    [JsonIgnore] private string? _cachedVectorPath;
    [JsonIgnore] private Geometry? _transformedVectorGeometry;
    [JsonIgnore] private Matrix _cachedVectorMatrix;
    [JsonIgnore] public Geometry? VectorGeometry
    {
        get
        {
            if (string.IsNullOrEmpty(VectorPath)) return null;
            if (_cachedVectorPath != VectorPath)
            {
                // The shared outline format follows Android/SVG nonzero winding. WPF defaults
                // to EvenOdd, which punches holes wherever unprefixed ink outlines overlap.
                // Explicit F0/F1 remains authoritative for deliberately hollow source geometry.
                var source = VectorPath.AsSpan().TrimStart();
                _vectorGeometry = Geometry.Parse(source.StartsWith("F", StringComparison.Ordinal) ? VectorPath : "F1 " + VectorPath);
                _vectorGeometry.Freeze();
                _cachedVectorPath = VectorPath;
                _transformedVectorGeometry = null;
            }
            var m = VectorTransform;
            var matrix = m.Length == 6 ? new Matrix(m[0], m[1], m[2], m[3], m[4], m[5]) : Matrix.Identity;
            if (_transformedVectorGeometry != null && matrix == _cachedVectorMatrix) return _transformedVectorGeometry;
            var transformed = _vectorGeometry!.Clone();
            transformed.Transform = new MatrixTransform(matrix);
            transformed.Freeze();
            _cachedVectorMatrix = matrix;
            return _transformedVectorGeometry = transformed;
        }
    }

    public void TransformVector(Matrix matrix)
    {
        if (string.IsNullOrEmpty(VectorPath)) return;
        var m = VectorTransform;
        var current = m.Length == 6 ? new Matrix(m[0], m[1], m[2], m[3], m[4], m[5]) : Matrix.Identity;
        current.Append(matrix);
        VectorTransform = [current.M11, current.M12, current.M21, current.M22, current.OffsetX, current.OffsetY];
        InvalidateBounds();
    }
    public List<InkPoint> Points
    {
        get => _points;
        set
        {
            _points = value ?? new List<InkPoint>();
            InvalidateBounds();
        }
    }

    [JsonIgnore]
    public Rect Bounds
    {
        get
        {
            if (_cachedBounds is { } cached) return cached;
            if (VectorBounds is { Length: 4 } b)
            {
                var rect = new Rect(b[0], b[1], Math.Max(0, b[2] - b[0]), Math.Max(0, b[3] - b[1]));
                var m = VectorTransform;
                if (m.Length == 6) rect = new MatrixTransform(m[0], m[1], m[2], m[3], m[4], m[5]).TransformBounds(rect);
                return (_cachedBounds = rect).Value;
            }
            if (VectorGeometry is { } geometry) return (_cachedBounds = geometry.Bounds).Value;
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

    [JsonIgnore] public long RenderVersion { get; private set; }

    public void InvalidateBounds() { _cachedBounds = null; RenderVersion++; }

    public StrokeRecord CopyForSave()
    {
        var copy = (StrokeRecord)MemberwiseClone();
        copy.Points = Points.Select(point => new InkPoint { X = point.X, Y = point.Y, Pressure = point.Pressure, TiltX = point.TiltX, TiltY = point.TiltY, ElapsedMilliseconds = point.ElapsedMilliseconds }).ToList();
        copy.VectorBounds = VectorBounds?.ToArray();
        copy.VectorTransform = VectorTransform.ToArray();
        // UI-owned geometry is never carried into a background writer.
        copy._vectorGeometry = copy._transformedVectorGeometry = null;
        copy._cachedVectorPath = null;
        return copy;
    }
}

public enum CanvasTagKind { Important, Question }

public static class CanvasTagKindExtensions
{
    public static string GetLabel(this CanvasTagKind kind) => kind == CanvasTagKind.Important ? "重要" : "问题";
}

public sealed class CanvasTag
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public string Id { get; set; } = IdFactory.New("tag");
    public CanvasTagKind Kind { get; set; }
    public float DocumentX { get; set; }
    public float DocumentY { get; set; }
    public float Scale { get; set; } = 1f;

    public CanvasTag() { }
    public CanvasTag CopyForSave() => (CanvasTag)MemberwiseClone();
    public CanvasTag(CanvasTagKind kind, float documentX, float documentY) { Kind = kind; DocumentX = documentX; DocumentY = documentY; }
}

public sealed class TextItem
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public TextItem CopyForSave() => (TextItem)MemberwiseClone();
    public string LayerId { get; set; } = string.Empty;
    public long ZOrder { get; set; }
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
    [JsonIgnore]
    public bool HasPendingContentChanges { get; set; }
    [JsonIgnore]
    public string? DiskStamp { get; set; }
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public int Version { get; set; } = 2;
    public string NotebookId { get; set; } = string.Empty;
    public string PageId { get; set; } = string.Empty;
    public long Revision { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    [JsonIgnore]
    public PageViewport Viewport { get; set; } = new();
    // Preserve old on-disk viewport data for compatibility; current browsing is device-local.
    [JsonPropertyName("viewport")]
    public PageViewport LegacyViewport { get; set; } = new();
    public List<StrokeRecord> Strokes { get; set; } = new();
    public List<CanvasTag> Tags { get; set; } = new();
    public List<TextItem> TextItems { get; set; } = new();
    public List<PageObject> Objects { get; set; } = new();
    public List<PageLayer> Layers { get; set; } = new();

    public PageDocument() { }
    public PageDocument(string notebookId, string pageId) { NotebookId = notebookId; PageId = pageId; }

    public PageDocument CopyForSave() => new(NotebookId, PageId)
    {
        Version = Version, Revision = Revision, UpdatedAt = UpdatedAt, AdditionalData = AdditionalData, DiskStamp = DiskStamp,
        Viewport = new PageViewport { Scale = Viewport.Scale, TranslationX = Viewport.TranslationX, TranslationY = Viewport.TranslationY },
        LegacyViewport = new PageViewport { Scale = LegacyViewport.Scale, TranslationX = LegacyViewport.TranslationX, TranslationY = LegacyViewport.TranslationY },
        Strokes = Strokes.Select(stroke => stroke.CopyForSave()).ToList(),
        Tags = Tags.Select(tag => tag.CopyForSave()).ToList(),
        TextItems = TextItems.Select(text => text.CopyForSave()).ToList(),
        Objects = Objects.Select(item => item.CopyForSave()).ToList(),
        Layers = Layers.Select(layer => new PageLayer { Id = layer.Id, Name = layer.Name, Visible = layer.Visible, Order = layer.Order, AdditionalData = layer.AdditionalData }).ToList(),
    };
}

public sealed class PageLayer
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public string Id { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public bool Visible { get; set; } = true;
    public int Order { get; set; }
}
