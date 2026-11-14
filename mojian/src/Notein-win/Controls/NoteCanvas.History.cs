using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Controls;

public partial class NoteCanvas
{
    private void PushUndo()
    {
        if (Document == null) return;
        PushHistory(new UndoEntry(Content: Snapshot(Document)));
    }

    private void PushInkUndo(string strokeId) => PushHistory(new UndoEntry(AddedStrokeId: strokeId));

    private void PushHistory(UndoEntry entry)
    {
        _undo.Push(entry);
        while (_undo.Count > 80)
        {
            var keep = _undo.Take(80).Reverse().ToArray();
            _undo.Clear(); foreach (var item in keep) _undo.Push(item);
        }
        _redo.Clear();
    }

    private PageContentHistory.Snapshot Snapshot(PageDocument document) => _history.Capture(document);

    private UndoEntry InvertHistory(UndoEntry entry)
    {
        if (entry.Content != null) return new UndoEntry(Content: Snapshot(Document!));
        if (entry.Erased != null) return entry with { ReapplyErase = !entry.ReapplyErase };
        if (entry.RestoreStroke is { } restored) return new UndoEntry(AddedStrokeId: restored.Stroke.Id);
        var index = Document!.Strokes.FindIndex(stroke => stroke.Id == entry.AddedStrokeId);
        return index < 0 ? new UndoEntry() : new UndoEntry(RestoreStroke: (index, Document.Strokes[index]));
    }

    private void RestoreSnapshot(UndoEntry entry)
    {
        if (entry.Content != null) { RestoreSnapshot(entry.Content); return; }
        if (Document == null) return;
        if (entry.Erased != null)
        {
            if (entry.ReapplyErase) entry.Erased.Reapply(Document);
            else entry.Erased.Restore(Document);
        }
        else if (entry.RestoreStroke is { } restored)
            Document.Strokes.Insert(Math.Min(restored.Index, Document.Strokes.Count), restored.Stroke);
        else Document.Strokes.RemoveAll(stroke => stroke.Id == entry.AddedStrokeId);
        ClearSelection();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    private void RestoreSnapshot(PageContentHistory.Snapshot snapshot)
    {
        if (Document == null) return;
        _history.Restore(snapshot, Document);
        ClearSelection();
        RebuildTextEditors();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

}
