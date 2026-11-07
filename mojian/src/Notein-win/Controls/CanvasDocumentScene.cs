using System.Windows;
using System.Windows.Media;

namespace Notein_win.Controls;

// One retained visual per piece of content gives WPF the bounds of the changed ink.
// Re-recording a single page-sized visual makes even a tiny erasure dirty the page.
public sealed class CanvasDocumentScene : FrameworkElement
{
    internal NoteCanvas? Owner { get; set; }
    private readonly ContainerVisual _root = new();
    private readonly Dictionary<object, Part> _parts = new(ReferenceEqualityComparer.Instance);
    private readonly List<DrawingVisual> _order = [];
    private readonly HashSet<object> _seen = new(ReferenceEqualityComparer.Instance);
    internal int PartRecordingCount { get; private set; }
    private sealed record Part(DrawingVisual Visual) { public Drawing? Drawing; public object? Signature; }

    public CanvasDocumentScene() => AddVisualChild(_root);
    protected override int VisualChildrenCount => 1;
    protected override Visual GetVisualChild(int index) => index == 0 ? _root : throw new ArgumentOutOfRangeException(nameof(index));
    protected override void OnRender(DrawingContext context) => Owner?.DrawScreenScene(context);

    internal void BeginScene(Matrix matrix, Rect? clip)
    {
        if (_root.Transform is not MatrixTransform current || current.Matrix != matrix)
            _root.Transform = new MatrixTransform(matrix);
        _root.Clip = clip is { } bounds ? new RectangleGeometry(bounds) : null;
        _seen.Clear(); _order.Clear();
    }

    internal void DrawPart(object key, Action<DrawingContext> draw) => DrawPart(key, null, draw);

    internal void DrawPart(object key, object? signature, Action<DrawingContext> draw)
    {
        if (signature != null && _parts.TryGetValue(key, out var existing) && Equals(existing.Signature, signature))
        {
            _seen.Add(key); _order.Add(existing.Visual);
            return;
        }
        var drawing = new DrawingGroup();
        using (var context = drawing.Open()) draw(context);
        drawing.Freeze();
        DrawPart(key, drawing);
        _parts[key].Signature = signature;
    }

    internal void DrawPart(object key, Drawing drawing)
    {
        if (!_parts.TryGetValue(key, out var part)) _parts.Add(key, part = new Part(new DrawingVisual()));
        Update(part, drawing);
        _seen.Add(key); _order.Add(part.Visual);
    }

    private void Update(Part part, Drawing drawing)
    {
        if (ReferenceEquals(part.Drawing, drawing)) return;
        using (var context = part.Visual.RenderOpen()) context.DrawDrawing(drawing);
        part.Drawing = drawing;
        part.Signature = null;
        PartRecordingCount++;
    }

    internal bool UpdatePart(object key, Drawing drawing)
    {
        if (!_parts.TryGetValue(key, out var part)) return false;
        Update(part, drawing);
        return true;
    }

    internal void RemovePart(object key)
    {
        if (!_parts.Remove(key, out var part)) return;
        _root.Children.Remove(part.Visual);
    }

    internal void EndScene()
    {
        foreach (var key in _parts.Keys.Where(key => !_seen.Contains(key)).ToArray()) RemovePart(key);
        // The common append/delete case preserves every existing visual and its order.
        for (var i = 0; i < _order.Count; i++)
        {
            if (i < _root.Children.Count && ReferenceEquals(_root.Children[i], _order[i])) continue;
            if (VisualTreeHelper.GetParent(_order[i]) == _root)
            {
                // An explicit stacking change is uncommon; rebuild only that ordering.
                _root.Children.Clear();
                foreach (var visual in _order) _root.Children.Add(visual);
                break;
            }
            _root.Children.Insert(i, _order[i]);
        }
    }

    internal void Clear()
    {
        _root.Children.Clear(); _parts.Clear(); _order.Clear(); _seen.Clear();
    }
}
