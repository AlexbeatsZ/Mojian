using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

// A gesture owns its index and the removed records. No page JSON or point copy is needed.
// Removed records cannot be edited while absent; restore their original list positions.
internal sealed class EraseGesture
{
    private const double CellSize = 64;
    private readonly Dictionary<(int X, int Y), List<StrokeRecord>> _cells = [];
    private readonly List<StrokeRecord> _large = [];
    private readonly Dictionary<StrokeRecord, int> _positions = [];
    private readonly Dictionary<CanvasTag, int> _tagPositions = [];
    private readonly HashSet<StrokeRecord> _removed = [];
    public List<(int Index, StrokeRecord Stroke)> Strokes { get; } = [];
    public List<(int Index, CanvasTag Tag)> Tags { get; } = [];
    public bool HasChanges => Strokes.Count != 0 || Tags.Count != 0;
    public bool HistoryPushed { get; set; }

    public EraseGesture(PageDocument document)
    {
        for (var i = 0; i < document.Strokes.Count; i++)
        {
            var stroke = document.Strokes[i];
            _positions.Add(stroke, i);
            var bounds = stroke.Bounds;
            if (bounds.IsEmpty) continue;
            var (left, top, right, bottom) = Cells(bounds);
            // Large imported contours use a bounded overflow list rather than thousands of cells.
            if ((long)(right - left + 1) * (bottom - top + 1) > 128) { _large.Add(stroke); continue; }
            for (var x = left; x <= right; x++)
            for (var y = top; y <= bottom; y++)
            {
                if (!_cells.TryGetValue((x, y), out var cell)) _cells.Add((x, y), cell = []);
                cell.Add(stroke);
            }
        }
        for (var i = 0; i < document.Tags.Count; i++) _tagPositions.Add(document.Tags[i], i);
    }

    private static (int Left, int Top, int Right, int Bottom) Cells(Rect bounds) =>
        ((int)Math.Floor(bounds.Left / CellSize), (int)Math.Floor(bounds.Top / CellSize),
         (int)Math.Floor(bounds.Right / CellSize), (int)Math.Floor(bounds.Bottom / CellSize));

    public IEnumerable<StrokeRecord> Candidates(Rect bounds)
    {
        var seen = new HashSet<StrokeRecord>();
        var (left, top, right, bottom) = Cells(bounds);
        for (var x = left; x <= right; x++)
        for (var y = top; y <= bottom; y++)
            if (_cells.TryGetValue((x, y), out var cell))
                foreach (var stroke in cell)
                    if (!_removed.Contains(stroke) && seen.Add(stroke)) yield return stroke;
        foreach (var stroke in _large) if (!_removed.Contains(stroke)) yield return stroke;
    }

    public void Remove(StrokeRecord stroke)
    {
        if (_removed.Add(stroke)) Strokes.Add((_positions[stroke], stroke));
    }

    public void Remove(CanvasTag tag) => Tags.Add((_tagPositions[tag], tag));

    public void Complete()
    {
        // History keeps only removed ink, never an index retaining the complete old page.
        _cells.Clear(); _cells.TrimExcess();
        _large.Clear(); _large.TrimExcess();
        _positions.Clear(); _positions.TrimExcess();
        _tagPositions.Clear(); _tagPositions.TrimExcess();
        _removed.Clear(); _removed.TrimExcess();
    }

    public void Restore(PageDocument document)
    {
        foreach (var (index, stroke) in Strokes.OrderBy(entry => entry.Index))
            document.Strokes.Insert(Math.Min(index, document.Strokes.Count), stroke);
        foreach (var (index, tag) in Tags.OrderBy(entry => entry.Index))
            document.Tags.Insert(Math.Min(index, document.Tags.Count), tag);
    }
}
