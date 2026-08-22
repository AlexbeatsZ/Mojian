using Notein_win.Models;
using Xunit;

namespace Notein_win.Tests;

public class WorkspaceModelsTests
{
    [Fact]
    public void FlattenSections_TraversesArbitraryDepthTree()
    {
        var workspace = NotebookWorkspace.Sample();
        var flattened = workspace.FlattenSections();

        Assert.NotEmpty(flattened);
        Assert.Contains(flattened, r => r.Section.Title == "产品设计" && r.Depth == 0);
        Assert.Contains(flattened, r => r.Section.Title == "Notein 兼容" && r.Depth == 1);
        Assert.Contains(flattened, r => r.Section.Title == "笔迹引擎" && r.Depth == 2);
    }

    [Fact]
    public void PagePaneRows_CurrentSectionMode_ReturnsOnlyDirectPages()
    {
        var workspace = NotebookWorkspace.Sample();
        workspace.SetPagePaneMode(PagePaneMode.CurrentSection);
        workspace.SelectSection("section-product");

        var rows = workspace.GetPagePaneRows();
        Assert.Equal(2, rows.Count);
        Assert.All(rows, r => Assert.IsType<PagePaneRow.PageItemRow>(r));
    }

    [Fact]
    public void PagePaneRows_RecursiveMode_IncludesChildSectionsAndPages()
    {
        var workspace = NotebookWorkspace.Sample();
        workspace.SetPagePaneMode(PagePaneMode.Recursive);
        workspace.SelectSection("section-product");

        var rows = workspace.GetPagePaneRows();
        Assert.True(rows.Count >= 5);
        Assert.Contains(rows, r => r is PagePaneRow.SectionHeaderRow sh && sh.Section.Title == "Notein 兼容");
        Assert.Contains(rows, r => r is PagePaneRow.PageItemRow pi && pi.Page.Title == "格式研究");
    }

    [Fact]
    public void Breadcrumb_ReturnsCorrectHierarchyPath()
    {
        var workspace = NotebookWorkspace.Sample();
        var breadcrumb = workspace.Breadcrumb("section-ink");

        Assert.Equal(3, breadcrumb.Count);
        Assert.Equal("产品设计", breadcrumb[0].Title);
        Assert.Equal("Notein 兼容", breadcrumb[1].Title);
        Assert.Equal("笔迹引擎", breadcrumb[2].Title);
    }

    [Fact]
    public void AddSection_UnderFocusedSection_CreatesChildSection()
    {
        var workspace = NotebookWorkspace.Sample();
        workspace.SelectSection("section-product");
        var child = workspace.AddSectionAtFocus();

        var product = workspace.FindSection("section-product");
        Assert.NotNull(product);
        Assert.Contains(child, product.Children);
        Assert.Equal(workspace.FocusedSectionId, child.Id);
    }

    [Fact]
    public void AddNotebook_AddsToLibraryAndFocuses()
    {
        var library = NotebookLibrary.Sample();
        var newNb = library.AddNotebook("测试笔记本");

        Assert.Equal(library.ActiveNotebookId, newNb.Id);
        Assert.Equal("测试笔记本", library.ActiveNotebook.Title);
    }
}
