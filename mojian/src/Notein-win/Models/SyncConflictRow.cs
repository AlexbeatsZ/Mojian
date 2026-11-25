using System.Windows.Media;
using Notein_win.Services;

namespace Notein_win.Models;

/// <summary>A conflicted file as navigation and the review bar show it.</summary>
public sealed class SyncConflictRow
{
    public SyncConflictRow(NativeSyncConflictItem item, string label, bool isSelected = false)
    {
        Item = item;
        Label = label;
        IsSelected = isSelected;
        Mark = SyncConflictReview.MarkOf(item.Action);
        if (SyncConflictReview.PageOf(item.Path) is { } page) { NotebookId = page.NotebookId; PageId = page.PageId; }
    }

    public NativeSyncConflictItem Item { get; }
    public string Path => Item.Path;
    public string Label { get; }
    public bool IsSelected { get; }
    public string? NotebookId { get; }
    public string? PageId { get; }
    public ConflictMark Mark { get; }
    public string Letter => SyncConflictReview.Letter(Mark);
    public Brush MarkBrush => ConflictBrushes.For(Mark);
    public bool IsPage => SyncConflictReview.IsPageFile(Item.Path);
    /// <summary>"两边都要" needs both JSON versions (the engine refuses it otherwise).</summary>
    public bool CanKeepBoth => Item.Path.EndsWith(".json", StringComparison.Ordinal) && Item.LocalFile != null && Item.RemoteFile != null;
    public string Summary => SyncConflictReview.SummaryText(Item.LocalOnly, Item.RemoteOnly, Item.Changed);
    public SyncConflictRow WithSelection(bool selected) => new(Item, Label, selected);
}

public static class ConflictBrushes
{
    private static readonly Dictionary<ConflictMark, Brush> Cache = Enum.GetValues<ConflictMark>().ToDictionary(mark => mark, mark =>
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(SyncConflictReview.ColorHex(mark)));
        brush.Freeze();
        return (Brush)brush;
    });
    public static Brush For(ConflictMark mark) => Cache[mark];
}
