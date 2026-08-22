namespace Notein_win.Models;

public class NotePage
{
    public string Id { get; set; }
    public string Title { get; set; }
    public string Subtitle { get; set; }
    public DateTime UpdatedAt { get; set; }

    public NotePage(string id, string title, string subtitle = "刚刚")
    {
        Id = id;
        Title = title;
        Subtitle = subtitle;
        UpdatedAt = DateTime.Now;
    }
}

public class SectionNode
{
    public string Id { get; set; }
    public string Title { get; set; }
    public List<NotePage> Pages { get; set; } = new();
    public List<SectionNode> Children { get; set; } = new();
    public bool Expanded { get; set; } = true;

    public SectionNode(string id, string title)
    {
        Id = id;
        Title = title;
    }

    public SectionNode(string id, string title, IEnumerable<NotePage> pages, IEnumerable<SectionNode>? children = null)
    {
        Id = id;
        Title = title;
        Pages = pages.ToList();
        if (children != null)
        {
            Children = children.ToList();
        }
    }
}

public record SectionRow(SectionNode Section, int Depth);

public enum PagePaneMode
{
    CurrentSection,
    Recursive,
}

public abstract record PagePaneRow(int Depth)
{
    public record SectionHeaderRow(SectionNode Section, int Depth) : PagePaneRow(Depth);
    public record PageItemRow(NotePage Page, string SectionId, int Depth) : PagePaneRow(Depth);
}

public class NotebookWorkspace
{
    private int _sequence = 100;

    public string Id { get; set; }
    public string Title { get; set; }
    public List<SectionNode> Sections { get; set; } = new();
    public bool Expanded { get; set; } = true;

    public string? FocusedSectionId { get; private set; }
    public string SelectedSectionId => FocusedSectionId ?? string.Empty;
    public PagePaneMode PagePaneMode { get; private set; } = PagePaneMode.CurrentSection;
    public string SelectedPageSectionId { get; private set; } = string.Empty;
    public string SelectedPageId { get; private set; } = string.Empty;

    public NotebookWorkspace(string id, string title, IEnumerable<SectionNode>? sections = null)
    {
        Id = id;
        Title = title;
        if (sections != null)
        {
            Sections = sections.ToList();
        }
        FocusedSectionId = FlattenSections().FirstOrDefault()?.Section.Id;
        SelectFirstPageForMode();
    }

    public List<SectionRow> FlattenSections()
    {
        var rows = new List<SectionRow>();
        void Visit(IEnumerable<SectionNode> nodes, int depth)
        {
            foreach (var node in nodes)
            {
                rows.Add(new SectionRow(node, depth));
                if (node.Expanded)
                {
                    Visit(node.Children, depth + 1);
                }
            }
        }
        Visit(Sections, 0);
        return rows;
    }

    public SectionNode? SelectedSection() =>
        FocusedSectionId != null ? FindSection(FocusedSectionId) : null;

    public bool HasSelectedSection() =>
        FocusedSectionId != null && FindSection(FocusedSectionId) != null;

    public NotePage? SelectedPage() =>
        FindSection(SelectedPageSectionId)?.Pages.FirstOrDefault(p => p.Id == SelectedPageId);

    public List<PagePaneRow> GetPagePaneRows()
    {
        if (FocusedSectionId == null)
        {
            if (PagePaneMode == PagePaneMode.CurrentSection)
            {
                return new List<PagePaneRow>();
            }

            var rows = new List<PagePaneRow>();
            void VisitAll(SectionNode section, int depth)
            {
                rows.Add(new PagePaneRow.SectionHeaderRow(section, depth));
                rows.AddRange(section.Pages.Select(p => new PagePaneRow.PageItemRow(p, section.Id, depth + 1)));
                foreach (var child in section.Children)
                {
                    VisitAll(child, depth + 1);
                }
            }
            foreach (var section in Sections)
            {
                VisitAll(section, 0);
            }
            return rows;
        }

        var root = FindSection(FocusedSectionId);
        if (root == null) return new List<PagePaneRow>();

        if (PagePaneMode == PagePaneMode.CurrentSection)
        {
            return root.Pages.Select(p => (PagePaneRow)new PagePaneRow.PageItemRow(p, root.Id, 0)).ToList();
        }

        var recursiveRows = new List<PagePaneRow>();
        recursiveRows.AddRange(root.Pages.Select(p => new PagePaneRow.PageItemRow(p, root.Id, 0)));

        void VisitChildren(SectionNode section, int depth)
        {
            recursiveRows.Add(new PagePaneRow.SectionHeaderRow(section, depth));
            recursiveRows.AddRange(section.Pages.Select(p => new PagePaneRow.PageItemRow(p, section.Id, depth + 1)));
            foreach (var child in section.Children)
            {
                VisitChildren(child, depth + 1);
            }
        }

        foreach (var child in root.Children)
        {
            VisitChildren(child, 0);
        }

        return recursiveRows;
    }

    public bool SelectSection(string id)
    {
        var section = FindSection(id);
        if (section == null) return false;
        FocusedSectionId = section.Id;
        SelectFirstPageForMode();
        return true;
    }

    public void ClearSectionFocus()
    {
        FocusedSectionId = null;
        SelectFirstPageForMode();
    }

    public void FocusNotebook()
    {
        FocusedSectionId = null;
        SelectFirstPageForMode();
    }

    public bool SelectPage(string id) =>
        SelectPage(string.IsNullOrEmpty(SelectedPageSectionId) ? (FocusedSectionId ?? string.Empty) : SelectedPageSectionId, id);

    public bool SelectPage(string sectionId, string id)
    {
        var section = FindSection(sectionId);
        if (section == null) return false;
        var page = section.Pages.FirstOrDefault(p => p.Id == id);
        if (page == null) return false;

        if (FocusedSectionId == null)
        {
            if (PagePaneMode == PagePaneMode.CurrentSection) return false;
        }
        else
        {
            if (PagePaneMode == PagePaneMode.CurrentSection && section.Id != FocusedSectionId) return false;
            var selectedRoot = FindSection(FocusedSectionId);
            if (selectedRoot == null) return false;
            if (PagePaneMode == PagePaneMode.Recursive && !IsDescendantOrSame(selectedRoot, section.Id)) return false;
        }

        SelectedPageSectionId = section.Id;
        SelectedPageId = page.Id;
        return true;
    }

    public void SetPagePaneMode(PagePaneMode mode)
    {
        if (PagePaneMode == mode) return;
        PagePaneMode = mode;
        if (mode == PagePaneMode.CurrentSection || SelectedPage() == null)
        {
            SelectFirstPageForMode();
        }
    }

    private void SelectFirstPageForMode()
    {
        if (FocusedSectionId == null)
        {
            if (PagePaneMode == PagePaneMode.CurrentSection)
            {
                SelectedPageSectionId = string.Empty;
                SelectedPageId = string.Empty;
            }
            else
            {
                var first = FirstPageInNotebook();
                SelectedPageSectionId = first?.SectionId ?? string.Empty;
                SelectedPageId = first?.Page.Id ?? string.Empty;
            }
            return;
        }

        var root = FindSection(FocusedSectionId);
        if (root == null)
        {
            SelectedPageSectionId = string.Empty;
            SelectedPageId = string.Empty;
            return;
        }

        var firstInTree = PagePaneMode == PagePaneMode.CurrentSection
            ? (root.Pages.FirstOrDefault() is { } p ? (SectionId: FocusedSectionId, Page: p) : null)
            : FirstPageInTree(root);

        SelectedPageSectionId = firstInTree?.SectionId ?? FocusedSectionId;
        SelectedPageId = firstInTree?.Page.Id ?? string.Empty;
    }

    private (string SectionId, NotePage Page)? FirstPageInNotebook()
    {
        foreach (var section in Sections)
        {
            var first = FirstPageInTree(section);
            if (first != null) return first;
        }
        return null;
    }

    private (string SectionId, NotePage Page)? FirstPageInTree(SectionNode section)
    {
        if (section.Pages.FirstOrDefault() is { } page)
        {
            return (section.Id, page);
        }
        foreach (var child in section.Children)
        {
            var first = FirstPageInTree(child);
            if (first != null) return first;
        }
        return null;
    }

    private bool IsDescendantOrSame(SectionNode root, string candidateId)
    {
        if (root.Id == candidateId) return true;
        return root.Children.Any(c => IsDescendantOrSame(c, candidateId));
    }

    public bool ToggleExpanded(string id)
    {
        var section = FindSection(id);
        if (section == null || section.Children.Count == 0) return false;
        section.Expanded = !section.Expanded;
        return true;
    }

    public SectionNode AddSection(string? parentId = null)
    {
        var node = new SectionNode(NextId("section"), parentId == null ? "新分区" : "新子分区");
        if (parentId == null)
        {
            Sections.Add(node);
        }
        else
        {
            var parent = FindSection(parentId) ?? throw new InvalidOperationException($"Parent section not found: {parentId}");
            parent.Children.Add(node);
            parent.Expanded = true;
        }
        SelectSection(node.Id);
        return node;
    }

    public SectionNode AddSectionAtFocus() => AddSection(FocusedSectionId);

    public NotePage AddPage(string title = "无标题页")
    {
        var section = SelectedSection() ?? throw new InvalidOperationException("Cannot add page without a selected section");
        var page = new NotePage(NextId("page"), title);
        section.Pages.Add(page);
        SelectedPageSectionId = section.Id;
        SelectedPageId = page.Id;
        return page;
    }

    public List<SectionNode> Breadcrumb(string? sectionId = null)
    {
        var targetId = sectionId ?? FocusedSectionId;
        if (targetId == null) return new List<SectionNode>();

        List<SectionNode>? Descend(IEnumerable<SectionNode> nodes, List<SectionNode> path)
        {
            foreach (var node in nodes)
            {
                path.Add(node);
                if (node.Id == targetId) return new List<SectionNode>(path);
                var result = Descend(node.Children, path);
                if (result != null) return result;
                path.RemoveAt(path.Count - 1);
            }
            return null;
        }

        return Descend(Sections, new List<SectionNode>()) ?? new List<SectionNode>();
    }

    public SectionNode? FindSection(string id)
    {
        SectionNode? Search(IEnumerable<SectionNode> nodes)
        {
            foreach (var node in nodes)
            {
                if (node.Id == id) return node;
                var found = Search(node.Children);
                if (found != null) return found;
            }
            return null;
        }
        return Search(Sections);
    }

    private string NextId(string prefix) => $"{prefix}-{_sequence++}";

    public static NotebookWorkspace Sample() =>
        new(
            id: "notebook-personal",
            title: "我的笔记本",
            sections: new List<SectionNode>
            {
                new(
                    id: "section-product",
                    title: "产品设计",
                    pages: new List<NotePage>
                    {
                        new("page-overview", "界面与交互"),
                        new("page-flow", "操作流程")
                    },
                    children: new List<SectionNode>
                    {
                        new(
                            id: "section-notein",
                            title: "Notein 兼容",
                            pages: new List<NotePage> { new("page-format", "格式研究") },
                            children: new List<SectionNode>
                            {
                                new(
                                    id: "section-ink",
                                    title: "笔迹引擎",
                                    pages: new List<NotePage>
                                    {
                                        new("page-brush", "钢笔手感"),
                                        new("page-latency", "延迟观察")
                                    }
                                )
                            }
                        ),
                        new(
                            id: "section-ideas",
                            title: "灵感",
                            pages: new List<NotePage> { new("page-ideas", "随手记录") }
                        )
                    }
                ),
                new(
                    id: "section-study",
                    title: "学习",
                    pages: new List<NotePage> { new("page-reading", "阅读笔记") }
                )
            }
        );
}

public class NotebookLibrary
{
    private int _sequence;

    public List<NotebookWorkspace> Notebooks { get; set; } = new();
    public string ActiveNotebookId { get; private set; }

    public NotebookLibrary(IEnumerable<NotebookWorkspace> notebooks)
    {
        Notebooks = notebooks.ToList();
        if (Notebooks.Count == 0)
        {
            throw new InvalidOperationException("Notebook library cannot be empty");
        }
        _sequence = Notebooks.Count + 1;
        ActiveNotebookId = Notebooks[0].Id;
    }

    public NotebookWorkspace ActiveNotebook =>
        Notebooks.FirstOrDefault(n => n.Id == ActiveNotebookId) ?? Notebooks[0];

    public bool SelectNotebook(string id)
    {
        if (Notebooks.All(n => n.Id != id)) return false;
        ActiveNotebookId = id;
        return true;
    }

    public NotebookWorkspace AddNotebook(string title = "新笔记本")
    {
        var inheritedMode = ActiveNotebook.PagePaneMode;
        var notebook = new NotebookWorkspace($"notebook-{_sequence++}", title, new List<SectionNode>());
        notebook.SetPagePaneMode(inheritedMode);
        Notebooks.Add(notebook);
        ActiveNotebookId = notebook.Id;
        notebook.FocusNotebook();
        return notebook;
    }

    public void SetPagePaneMode(PagePaneMode mode)
    {
        foreach (var notebook in Notebooks)
        {
            notebook.SetPagePaneMode(mode);
        }
    }

    public static NotebookLibrary Sample() => new(new[] { NotebookWorkspace.Sample() });
}
