using System.Runtime.CompilerServices;
using Notein_win.Models;

namespace Notein_win.Services;

// Only background save snapshots use this cache. These point lists are never handed to an
// editor; normal CopyForSave() still returns fully independent editable copies.
public sealed class StrokePointSnapshotCache
{
    private readonly ConditionalWeakTable<StrokeRecord, Entry> _entries = new();
    private sealed class Entry
    {
        public long Version = -1;
        public List<InkPoint>? Source;
        public List<InkPoint> Points = [];
    }

    internal List<InkPoint> Get(StrokeRecord stroke)
    {
        var entry = _entries.GetOrCreateValue(stroke);
        if (entry.Version != stroke.RenderVersion || !ReferenceEquals(entry.Source, stroke.Points) || entry.Points.Count != stroke.Points.Count)
        {
            entry.Points = stroke.Points.Select(p => new InkPoint
            {
                X = p.X, Y = p.Y, Pressure = p.Pressure, TiltX = p.TiltX, TiltY = p.TiltY, ElapsedMilliseconds = p.ElapsedMilliseconds,
                TiltRadians = p.TiltRadians, OrientationRadians = p.OrientationRadians,
                AdditionalData = p.AdditionalData?.ToDictionary(pair => pair.Key, pair => pair.Value.Clone()),
            }).ToList();
            entry.Source = stroke.Points;
            entry.Version = stroke.RenderVersion;
        }
        return entry.Points;
    }
}
