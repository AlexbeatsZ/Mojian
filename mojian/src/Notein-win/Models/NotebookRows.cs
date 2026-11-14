using System.Windows;

namespace Notein_win.Models;

public interface INotebookTreeRow;

public sealed class SectionRow : INotebookTreeRow
{
    private static readonly string[] SectionColors = ["#7719AA", "#185ABD", "#C239B3", "#CA5010"];

    public SectionNode Section { get; }
    public int Depth { get; }
    public Thickness Indent => new(14 + Depth * 15, 0, 7, 0);
    public bool HasChildren => Section.Children.Count > 0;
    public bool IsExpanded => HasChildren && Section.Expanded;
    public bool IsSelected { get; }
    public string SectionColorHex => SectionColors[Math.Abs(Depth + 1) % SectionColors.Length];
    public System.Windows.Media.Brush SectionColorBrush => new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(SectionColorHex));

    public SectionRow(SectionNode section, int depth, bool isSelected = false)
    {
        Section = section;
        Depth = depth;
        IsSelected = isSelected;
    }
}

public sealed class NotebookPaneRow : INotebookTreeRow
{
    private static readonly string[] SectionColors = ["#7719AA", "#185ABD", "#C239B3", "#CA5010"];

    public NotebookWorkspace Notebook { get; }
    public string Id => Notebook.Id;
    public string Title => Notebook.Title;
    public bool IsExpanded => Notebook.Expanded;
    public bool IsActive { get; }
    public bool IsFocused { get; }
    public string AccentHex { get; }
    public System.Windows.Media.Brush AccentBrush { get; }

    public NotebookPaneRow(NotebookWorkspace notebook, int index, bool isActive)
    {
        Notebook = notebook;
        IsActive = isActive;
        IsFocused = isActive && notebook.FocusedSectionId == null;
        AccentHex = SectionColors[Math.Abs(index) % SectionColors.Length];
        AccentBrush = new System.Windows.Media.SolidColorBrush(
            (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(AccentHex));
    }
}

public sealed class PagePaneRow
{
    private static readonly string[] SectionColors = ["#7719AA", "#185ABD", "#C239B3", "#CA5010"];

    public bool IsHeader { get; init; }
    public string Title { get; init; } = string.Empty;
    public string Subtitle { get; init; } = string.Empty;
    public string SectionId { get; init; } = string.Empty;
    public NotePage? Page { get; init; }
    public int Depth { get; init; }
    public Thickness Indent => IsHeader
        ? new Thickness(9 + Depth * 13, 0, 8, 0)
        : new Thickness(13 + Depth * 13, 7, 10, 6);
    public Thickness Margin => IsHeader ? new Thickness(6, 5, 6, 0) : new Thickness(6, 1, 6, 1);
    public bool IsSelected { get; init; }
    public bool IsPdf => Page?.IsPdfPage == true;
    public string SectionColorHex => SectionColors[Math.Abs(Depth + 1) % SectionColors.Length];
    public System.Windows.Media.Brush SectionColorBrush => new System.Windows.Media.SolidColorBrush(
        (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(SectionColorHex));
}

