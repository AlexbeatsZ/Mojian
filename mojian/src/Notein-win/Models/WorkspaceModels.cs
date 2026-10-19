using System.Text.Json.Serialization;
using System.Windows;

namespace Notein_win.Models;

public static class IdFactory
{
    public static string New(string prefix) => $"{prefix}-{Guid.NewGuid():N}";
}

public enum PagePaneMode { CurrentSection, Recursive }
public enum PaperKind { Infinite, A4, Pdf }
public enum PageTheme { Dark, Light }

public enum CanvasBackgroundFormat
{
    Blank,
    RuledNarrow,
    RuledStandard,
    RuledWide,
    GridDense,
    GridStandard,
    GridWide,
    DotsDense,
    DotsStandard,
    DotsWide,
    RuledTwoColumnStandard,
    RuledTwoColumnNarrow,
    RuledTwoColumnWide,
    RuledClean,
}

public static class CanvasBackgroundFormatExtensions
{
    public static string GetLabel(this CanvasBackgroundFormat value) => BackgroundPatternCatalog.Shared.For(value).Label;
}

public sealed class PageAppearance
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public PaperKind PaperKind { get; set; } = PaperKind.Infinite;
    public PageTheme Theme { get; set; } = PageTheme.Dark;
    public CanvasBackgroundFormat Background { get; set; } = CanvasBackgroundFormat.GridStandard;
    public string PageColor { get; set; } = "#1B1B1B";
    public bool ShowTitle { get; set; } = true;
    public int SheetCount { get; set; } = 1;
    public double Width { get; set; } = 793.7;
    public double Height { get; set; } = 1122.5;
    public double InfiniteWidth { get; set; } = InfinitePageLayout.InitialWidth;
    public double InfiniteHeight { get; set; } = InfinitePageLayout.InitialHeight;

    public PageAppearance Clone() => new()
    {
        AdditionalData = AdditionalData,
        PaperKind = PaperKind,
        Theme = Theme,
        Background = Background,
        PageColor = PageColor,
        ShowTitle = ShowTitle,
        SheetCount = Math.Max(1, SheetCount),
        Width = Width,
        Height = Height,
        InfiniteWidth = InfiniteWidth,
        InfiniteHeight = InfiniteHeight,
    };
}

public sealed class NotePage
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public NotePage CopyForSave()
    {
        var copy = (NotePage)MemberwiseClone();
        copy.Appearance = Appearance.Clone();
        return copy;
    }
    public string Id { get; set; } = IdFactory.New("page");
    public string Title { get; set; } = "无标题页";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public PageAppearance Appearance { get; set; } = new();
    public bool IsPdfPage { get; set; }
    public int? PdfPageNumber { get; set; }
    public int? PdfPageCount { get; set; }
    public double? PdfWidthPoints { get; set; }
    public double? PdfHeightPoints { get; set; }
    public string? PdfSourceAssetName { get; set; }
    public string? PdfSourceDocumentName { get; set; }

    public NotePage() { }

    public NotePage(string id, string title, string subtitle = "刚刚")
    {
        Id = id;
        Title = title;
    }

    [JsonIgnore]
    public string Subtitle => UpdatedAt.ToLocalTime().Date == DateTime.Today
        ? UpdatedAt.ToLocalTime().ToString("HH:mm")
        : UpdatedAt.ToLocalTime().ToString("yyyy-MM-dd");
}

public sealed class SectionNode
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public SectionNode CopyForSave()
    {
        var copy = (SectionNode)MemberwiseClone();
        copy.Pages = Pages.Select(page => page.CopyForSave()).ToList();
        copy.Children = Children.Select(child => child.CopyForSave()).ToList();
        return copy;
    }
    public string Id { get; set; } = IdFactory.New("section");
    public string Title { get; set; } = "新分区";
    public List<NotePage> Pages { get; set; } = new();
    public List<SectionNode> Children { get; set; } = new();
    public bool Expanded { get; set; } = true;

    public SectionNode() { }
    public SectionNode(string id, string title) { Id = id; Title = title; }

    public SectionNode(string id, string title, IEnumerable<NotePage> pages, IEnumerable<SectionNode>? children = null)
    {
        Id = id;
        Title = title;
        Pages = pages.ToList();
        Children = children?.ToList() ?? new List<SectionNode>();
    }
}

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

public sealed class NotebookWorkspace
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public NotebookWorkspace CopyForSave()
    {
        var copy = (NotebookWorkspace)MemberwiseClone();
        copy.Sections = Sections.Select(section => section.CopyForSave()).ToList();
        return copy;
    }
    public string Id { get; set; } = IdFactory.New("notebook");
    public string Title { get; set; } = "新笔记本";
    public List<SectionNode> Sections { get; set; } = new();
    public bool Expanded { get; set; } = true;
    public string? FocusedSectionId { get; set; }
    public string SelectedPageSectionId { get; set; } = string.Empty;
    public string SelectedPageId { get; set; } = string.Empty;
    [JsonIgnore]
    public string SelectedSectionId => FocusedSectionId ?? string.Empty;

    public NotebookWorkspace() { }

    public NotebookWorkspace(string id, string title, IEnumerable<SectionNode>? sections = null)
    {
        Id = id;
        Title = title;
        Sections = sections?.ToList() ?? new List<SectionNode>();
        FocusedSectionId = FlattenAllSections().FirstOrDefault()?.Id;
        SelectFirstPage(PagePaneMode.CurrentSection);
    }

    public IEnumerable<SectionNode> FlattenAllSections()
    {
        foreach (var root in Sections)
        {
            foreach (var section in Flatten(root)) yield return section;
        }

        static IEnumerable<SectionNode> Flatten(SectionNode node)
        {
            yield return node;
            foreach (var child in node.Children)
            {
                foreach (var descendant in Flatten(child)) yield return descendant;
            }
        }
    }

    public List<SectionRow> FlattenSections()
    {
        var result = new List<SectionRow>();
        void Visit(IEnumerable<SectionNode> nodes, int depth)
        {
            foreach (var node in nodes)
            {
                result.Add(new SectionRow(node, depth, node.Id == FocusedSectionId));
                if (node.Expanded) Visit(node.Children, depth + 1);
            }
        }
        Visit(Sections, 0);
        return result;
    }

    public SectionNode? FindSection(string? id) => string.IsNullOrWhiteSpace(id)
        ? null
        : FlattenAllSections().FirstOrDefault(section => section.Id == id);

    public NotePage? FindPage(string? pageId, out SectionNode? owner)
    {
        owner = null;
        if (string.IsNullOrWhiteSpace(pageId)) return null;
        foreach (var section in FlattenAllSections())
        {
            var page = section.Pages.FirstOrDefault(item => item.Id == pageId);
            if (page == null) continue;
            owner = section;
            return page;
        }
        return null;
    }

    public SectionNode? SelectedSection() => FindSection(FocusedSectionId);
    public bool HasSelectedSection() => SelectedSection() != null;
    public NotePage? SelectedPage() => FindPage(SelectedPageId, out _);

    public bool SelectSection(string id, PagePaneMode mode = PagePaneMode.CurrentSection)
    {
        if (FindSection(id) == null) return false;
        FocusedSectionId = id;
        SelectFirstPage(mode);
        return true;
    }

    public void FocusNotebook(PagePaneMode mode = PagePaneMode.CurrentSection)
    {
        FocusedSectionId = null;
        SelectFirstPage(mode);
    }

    public bool SelectPage(string sectionId, string pageId, PagePaneMode mode)
    {
        var section = FindSection(sectionId);
        if (section?.Pages.All(page => page.Id != pageId) != false) return false;
        if (FocusedSectionId == null && mode == PagePaneMode.CurrentSection) return false;
        if (FocusedSectionId != null && mode == PagePaneMode.CurrentSection && section.Id != FocusedSectionId) return false;
        if (FocusedSectionId != null && mode == PagePaneMode.Recursive)
        {
            var root = FindSection(FocusedSectionId);
            if (root == null || !Contains(root, section.Id)) return false;
        }
        SelectedPageSectionId = section.Id;
        SelectedPageId = pageId;
        return true;
    }

    public void SelectFirstPage(PagePaneMode mode)
    {
        (SectionNode Section, NotePage Page)? first = null;
        if (FocusedSectionId == null)
        {
            if (mode == PagePaneMode.Recursive) first = FirstPage(Sections);
        }
        else if (FindSection(FocusedSectionId) is { } root)
        {
            first = mode == PagePaneMode.CurrentSection
                ? root.Pages.FirstOrDefault() is { } direct ? (root, direct) : null
                : FirstPage(new[] { root });
        }
        SelectedPageSectionId = first?.Section.Id ?? string.Empty;
        SelectedPageId = first?.Page.Id ?? string.Empty;
    }

    public List<PagePaneRow> GetPagePaneRows(PagePaneMode mode)
    {
        var result = new List<PagePaneRow>();
        if (FocusedSectionId == null)
        {
            if (mode == PagePaneMode.CurrentSection) return result;
            foreach (var root in Sections) AppendSection(result, root, 0, true);
            return result;
        }

        var focused = FindSection(FocusedSectionId);
        if (focused == null) return result;
        if (mode == PagePaneMode.CurrentSection)
        {
            result.AddRange(focused.Pages.Select(page => PageRow(page, focused.Id, 0)));
            return result;
        }

        result.AddRange(focused.Pages.Select(page => PageRow(page, focused.Id, 0)));
        foreach (var child in focused.Children) AppendSection(result, child, 0, true);
        return result;
    }

    public bool ToggleExpanded(string id)
    {
        var section = FindSection(id);
        if (section == null || section.Children.Count == 0) return false;
        section.Expanded = !section.Expanded;
        return true;
    }

    public SectionNode AddSection(string title, string? parentId = null)
    {
        var section = new SectionNode(IdFactory.New("section"), string.IsNullOrWhiteSpace(title) ? "新分区" : title.Trim());
        if (string.IsNullOrWhiteSpace(parentId)) Sections.Add(section);
        else
        {
            var parent = FindSection(parentId) ?? throw new InvalidOperationException("找不到父分区。");
            parent.Children.Add(section);
            parent.Expanded = true;
        }
        FocusedSectionId = section.Id;
        SelectedPageId = string.Empty;
        SelectedPageSectionId = section.Id;
        return section;
    }

    public SectionNode AddSectionAtFocus(string title = "新分区") => AddSection(title, FocusedSectionId);

    public NotePage AddPage(string title = "无标题页", PageAppearance? appearance = null)
    {
        var section = SelectedSection() ?? throw new InvalidOperationException("请先选择一个分区。");
        var page = new NotePage(IdFactory.New("page"), string.IsNullOrWhiteSpace(title) ? "无标题页" : title.Trim())
        {
            Appearance = appearance?.Clone() ?? new PageAppearance(),
        };
        section.Pages.Add(page);
        SelectedPageSectionId = section.Id;
        SelectedPageId = page.Id;
        return page;
    }

    public bool RenameSection(string sectionId, string title)
    {
        var section = FindSection(sectionId);
        var normalized = title?.Trim();
        if (section == null || string.IsNullOrWhiteSpace(normalized)) return false;
        section.Title = normalized;
        return true;
    }

    public SectionNode? DeleteSection(string sectionId)
    {
        var position = FindSectionPosition(sectionId);
        if (position == null) return null;
        var removed = position.Value.Siblings[position.Value.Index];
        position.Value.Siblings.RemoveAt(position.Value.Index);
        if (Contains(removed, FocusedSectionId ?? string.Empty))
        {
            FocusedSectionId = Sections.FirstOrDefault()?.Id;
            SelectFirstPage(PagePaneMode.CurrentSection);
        }
        return removed;
    }

    public bool MoveSectionToParent(string sectionId, string? targetParentId)
    {
        var source = FindSection(sectionId);
        if (source == null || source.Id == targetParentId || Contains(source, targetParentId ?? string.Empty)) return false;
        var sourcePosition = FindSectionPosition(sectionId);
        if (sourcePosition == null) return false;
        var destination = string.IsNullOrWhiteSpace(targetParentId) ? Sections : FindSection(targetParentId)?.Children;
        if (destination == null) return false;
        sourcePosition.Value.Siblings.RemoveAt(sourcePosition.Value.Index);
        destination.Add(source);
        if (!string.IsNullOrWhiteSpace(targetParentId)) FindSection(targetParentId)!.Expanded = true;
        return true;
    }

    public bool MoveSectionAdjacent(string sectionId, string targetSectionId, bool after)
    {
        if (sectionId == targetSectionId) return false;
        var source = FindSection(sectionId);
        var target = FindSection(targetSectionId);
        if (source == null || target == null || Contains(source, targetSectionId)) return false;
        var sourcePosition = FindSectionPosition(sectionId);
        var targetPosition = FindSectionPosition(targetSectionId);
        if (sourcePosition == null || targetPosition == null) return false;
        sourcePosition.Value.Siblings.RemoveAt(sourcePosition.Value.Index);
        targetPosition = FindSectionPosition(targetSectionId);
        if (targetPosition == null) return false;
        targetPosition.Value.Siblings.Insert(targetPosition.Value.Index + (after ? 1 : 0), source);
        return true;
    }

    public SectionNode? CopySectionToParent(string sectionId, string? targetParentId, out Dictionary<string, string> pageIds)
    {
        pageIds = new Dictionary<string, string>();
        var source = FindSection(sectionId);
        if (source == null || source.Id == targetParentId || Contains(source, targetParentId ?? string.Empty)) return null;
        var destination = string.IsNullOrWhiteSpace(targetParentId) ? Sections : FindSection(targetParentId)?.Children;
        if (destination == null) return null;
        var copy = CloneSection(source, pageIds);
        destination.Add(copy);
        if (!string.IsNullOrWhiteSpace(targetParentId)) FindSection(targetParentId)!.Expanded = true;
        return copy;
    }

    public bool MergeSectionInto(string sourceSectionId, string targetSectionId)
    {
        if (sourceSectionId == targetSectionId) return false;
        var source = FindSection(sourceSectionId);
        var target = FindSection(targetSectionId);
        var sourcePosition = FindSectionPosition(sourceSectionId);
        if (source == null || target == null || sourcePosition == null || Contains(source, targetSectionId)) return false;
        target.Pages.AddRange(source.Pages);
        target.Children.AddRange(source.Children);
        target.Expanded = true;
        sourcePosition.Value.Siblings.RemoveAt(sourcePosition.Value.Index);
        if (FocusedSectionId == sourceSectionId) FocusedSectionId = targetSectionId;
        if (SelectedPageSectionId == sourceSectionId) SelectedPageSectionId = targetSectionId;
        return true;
    }

    public bool RenamePage(string sectionId, string pageId, string title)
    {
        var page = FindSection(sectionId)?.Pages.FirstOrDefault(item => item.Id == pageId);
        var normalized = title?.Trim();
        if (page == null || string.IsNullOrWhiteSpace(normalized)) return false;
        page.Title = normalized;
        page.UpdatedAt = DateTime.UtcNow;
        return true;
    }

    public NotePage? DeletePage(string sectionId, string pageId)
    {
        var section = FindSection(sectionId);
        var index = section?.Pages.FindIndex(page => page.Id == pageId) ?? -1;
        if (section == null || index < 0) return null;
        var removed = section.Pages[index];
        section.Pages.RemoveAt(index);
        if (SelectedPageId == pageId) SelectFirstPage(PagePaneMode.CurrentSection);
        return removed;
    }

    public bool MovePage(string sourceSectionId, string pageId, string targetSectionId, int? targetIndex = null)
    {
        var source = FindSection(sourceSectionId);
        var target = FindSection(targetSectionId);
        var index = source?.Pages.FindIndex(page => page.Id == pageId) ?? -1;
        if (source == null || target == null || index < 0) return false;
        var page = source.Pages[index];
        source.Pages.RemoveAt(index);
        var destinationIndex = Math.Clamp(targetIndex ?? target.Pages.Count, 0, target.Pages.Count);
        target.Pages.Insert(destinationIndex, page);
        SelectedPageSectionId = target.Id;
        SelectedPageId = page.Id;
        return true;
    }

    public bool MovePageAdjacent(string sourceSectionId, string pageId, string targetSectionId, string targetPageId, bool after)
    {
        var target = FindSection(targetSectionId);
        var targetIndex = target?.Pages.FindIndex(page => page.Id == targetPageId) ?? -1;
        if (target == null || targetIndex < 0) return false;
        if (sourceSectionId == targetSectionId)
        {
            var sourceIndex = target.Pages.FindIndex(page => page.Id == pageId);
            if (sourceIndex < 0 || sourceIndex == targetIndex) return false;
            var page = target.Pages[sourceIndex];
            target.Pages.RemoveAt(sourceIndex);
            targetIndex = target.Pages.FindIndex(item => item.Id == targetPageId);
            target.Pages.Insert(targetIndex + (after ? 1 : 0), page);
            SelectedPageSectionId = targetSectionId;
            SelectedPageId = pageId;
            return true;
        }
        return MovePage(sourceSectionId, pageId, targetSectionId, targetIndex + (after ? 1 : 0));
    }

    public NotePage? CopyPage(string sourceSectionId, string pageId, string targetSectionId, int? targetIndex = null)
    {
        var source = FindSection(sourceSectionId)?.Pages.FirstOrDefault(page => page.Id == pageId);
        var target = FindSection(targetSectionId);
        if (source == null || target == null) return null;
        var copy = ClonePage(source);
        target.Pages.Insert(Math.Clamp(targetIndex ?? target.Pages.Count, 0, target.Pages.Count), copy);
        SelectedPageSectionId = target.Id;
        SelectedPageId = copy.Id;
        return copy;
    }

    public List<SectionNode> Breadcrumb(string? sectionId = null)
    {
        var target = sectionId ?? SelectedPageSectionId;
        var path = new List<SectionNode>();
        bool Visit(IEnumerable<SectionNode> nodes)
        {
            foreach (var node in nodes)
            {
                path.Add(node);
                if (node.Id == target || Visit(node.Children)) return true;
                path.RemoveAt(path.Count - 1);
            }
            return false;
        }
        return Visit(Sections) ? path : new List<SectionNode>();
    }

    private static bool Contains(SectionNode root, string id) => root.Id == id || root.Children.Any(child => Contains(child, id));

    private (List<SectionNode> Siblings, int Index)? FindSectionPosition(string id)
    {
        (List<SectionNode>, int)? Visit(List<SectionNode> nodes)
        {
            for (var index = 0; index < nodes.Count; index++)
            {
                if (nodes[index].Id == id) return (nodes, index);
                if (Visit(nodes[index].Children) is { } nested) return nested;
            }
            return null;
        }
        return Visit(Sections);
    }

    private static SectionNode CloneSection(SectionNode source, Dictionary<string, string> pageIds)
    {
        var copy = new SectionNode(IdFactory.New("section"), $"{source.Title} - 副本") { Expanded = source.Expanded };
        foreach (var page in source.Pages)
        {
            var pageCopy = ClonePage(page);
            copy.Pages.Add(pageCopy);
            pageIds[page.Id] = pageCopy.Id;
        }
        foreach (var child in source.Children) copy.Children.Add(CloneSection(child, pageIds));
        return copy;
    }

    private static NotePage ClonePage(NotePage source) => new(IdFactory.New("page"), $"{source.Title} - 副本")
    {
        CreatedAt = DateTime.UtcNow,
        UpdatedAt = DateTime.UtcNow,
        Appearance = source.Appearance.Clone(),
        IsPdfPage = source.IsPdfPage,
        PdfPageNumber = source.PdfPageNumber,
        PdfPageCount = source.PdfPageCount,
        PdfWidthPoints = source.PdfWidthPoints,
        PdfHeightPoints = source.PdfHeightPoints,
        PdfSourceAssetName = source.PdfSourceAssetName,
        PdfSourceDocumentName = source.PdfSourceDocumentName,
    };

    private static (SectionNode Section, NotePage Page)? FirstPage(IEnumerable<SectionNode> roots)
    {
        foreach (var root in roots)
        {
            if (root.Pages.FirstOrDefault() is { } page) return (root, page);
            if (FirstPage(root.Children) is { } childPage) return childPage;
        }
        return null;
    }

    private PagePaneRow PageRow(NotePage page, string sectionId, int depth) => new()
    {
        Page = page,
        SectionId = sectionId,
        Title = page.Title,
        Subtitle = page.Subtitle,
        Depth = depth,
        IsSelected = page.Id == SelectedPageId,
    };

    private void AppendSection(List<PagePaneRow> rows, SectionNode section, int depth, bool includeHeader)
    {
        if (includeHeader) rows.Add(new PagePaneRow { IsHeader = true, Title = section.Title, SectionId = section.Id, Depth = depth });
        rows.AddRange(section.Pages.Select(page => PageRow(page, section.Id, depth + 1)));
        foreach (var child in section.Children) AppendSection(rows, child, depth + 1, true);
    }

    public static NotebookWorkspace Sample()
    {
        var page = new NotePage("page-welcome", "欢迎使用");
        var section = new SectionNode("section-inbox", "未归档", new[] { page });
        return new NotebookWorkspace("notebook-personal", "我的笔记本", new[] { section });
    }
}

public sealed class NotebookLibrary
{
    [JsonExtensionData]
    public Dictionary<string, System.Text.Json.JsonElement>? AdditionalData { get; set; }
    public NotebookLibrary CopyForSave() => new()
    {
        Version = Version, ActiveNotebookId = ActiveNotebookId, AdditionalData = AdditionalData,
        Notebooks = Notebooks.Select(notebook => notebook.CopyForSave()).ToList(),
    };
    public int Version { get; set; } = 2;
    public List<NotebookWorkspace> Notebooks { get; set; } = new();
    public string ActiveNotebookId { get; set; } = string.Empty;

    public NotebookLibrary() { }

    public NotebookLibrary(IEnumerable<NotebookWorkspace> notebooks)
    {
        Notebooks = notebooks.ToList();
        if (Notebooks.Count == 0) throw new InvalidOperationException("笔记库不能为空。");
        ActiveNotebookId = Notebooks[0].Id;
    }

    [JsonIgnore]
    public NotebookWorkspace ActiveNotebook => Notebooks.FirstOrDefault(notebook => notebook.Id == ActiveNotebookId) ?? Notebooks[0];

    public bool SelectNotebook(string id)
    {
        if (Notebooks.All(notebook => notebook.Id != id)) return false;
        ActiveNotebookId = id;
        return true;
    }

    public NotebookWorkspace AddNotebook(string title = "新笔记本")
    {
        var notebook = new NotebookWorkspace(IdFactory.New("notebook"), string.IsNullOrWhiteSpace(title) ? "新笔记本" : title.Trim());
        Notebooks.Add(notebook);
        ActiveNotebookId = notebook.Id;
        return notebook;
    }

    public bool RenameNotebook(string id, string title)
    {
        var notebook = Notebooks.FirstOrDefault(item => item.Id == id);
        if (notebook == null || string.IsNullOrWhiteSpace(title)) return false;
        notebook.Title = title.Trim();
        return true;
    }

    public bool DeleteNotebook(string id)
    {
        var index = Notebooks.FindIndex(item => item.Id == id);
        if (index < 0) return false;
        Notebooks.RemoveAt(index);
        if (Notebooks.Count == 0) AddNotebook();
        else if (ActiveNotebookId == id) ActiveNotebookId = Notebooks[Math.Min(index, Notebooks.Count - 1)].Id;
        return true;
    }

    public static NotebookLibrary Sample() => new(new[] { NotebookWorkspace.Sample() });
}
