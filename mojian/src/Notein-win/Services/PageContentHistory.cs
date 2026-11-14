using System.Runtime.CompilerServices;
using Notein_win.Models;

namespace Notein_win.Services;

// History owns frozen content versions. Unchanged ink shares one immutable point snapshot
// across entries, and restores its existing editor object so render caches remain useful.
internal sealed class PageContentHistory
{
    internal sealed record StrokeVersion(StrokeRecord Live, StrokeRecord Frozen);
    internal sealed record Snapshot(List<StrokeVersion> Strokes, List<CanvasTag> Tags,
        List<TextItem> Text, List<PageObject> Objects, List<PageLayer> Layers);
    private sealed class Entry
    {
        public StrokeRecord? Frozen;
    }
    private readonly ConditionalWeakTable<StrokeRecord, Entry> _versions = new();
    private readonly StrokePointSnapshotCache _points = new();

    private StrokeRecord Freeze(StrokeRecord stroke)
    {
        var entry = _versions.GetOrCreateValue(stroke);
        var points = _points.Get(stroke);
        if (entry.Frozen == null || !StrokeJsonConverter.SameMetadata(entry.Frozen, stroke) || !ReferenceEquals(entry.Frozen.Points, points))
        {
            entry.Frozen = stroke.CopyForSave(_points);
        }
        return entry.Frozen;
    }

    public Snapshot Capture(PageDocument document) => new(
        document.Strokes.Select(stroke => new StrokeVersion(stroke, Freeze(stroke))).ToList(),
        document.Tags.Select(tag => tag.CopyForSave()).ToList(),
        document.TextItems.Select(text => text.CopyForSave()).ToList(),
        document.Objects.Select(item => item.CopyForSave()).ToList(),
        document.Layers.Select(layer => new PageLayer
        {
            Id = layer.Id, Name = layer.Name, Visible = layer.Visible, Order = layer.Order, AdditionalData = layer.AdditionalData,
        }).ToList());

    public void Restore(Snapshot snapshot, PageDocument document)
    {
        document.Strokes = snapshot.Strokes.Select(version =>
            ReferenceEquals(Freeze(version.Live), version.Frozen) ? version.Live : version.Frozen.CopyForSave()).ToList();
        document.Tags = snapshot.Tags.Select(tag => tag.CopyForSave()).ToList();
        document.TextItems = snapshot.Text.Select(text => text.CopyForSave()).ToList();
        document.Objects = snapshot.Objects.Select(item => item.CopyForSave()).ToList();
        document.Layers = snapshot.Layers.Select(layer => new PageLayer
        {
            Id = layer.Id, Name = layer.Name, Visible = layer.Visible, Order = layer.Order, AdditionalData = layer.AdditionalData,
        }).ToList();
    }
}
