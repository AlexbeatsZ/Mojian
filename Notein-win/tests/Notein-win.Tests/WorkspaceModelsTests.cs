using Notein_win.Models;
using Xunit;

namespace Notein_win.Tests;

public class WorkspaceModelsTests
{
    private static NotebookWorkspace NestedWorkspace()
    {
        var engine = new SectionNode("section-engine", "笔迹引擎", new[] { new NotePage("page-engine", "压感曲线") });
        var compatibility = new SectionNode("section-compat", "跨端兼容", new[] { new NotePage("page-format", "格式研究") }, new[] { engine });
        var product = new SectionNode("section-product", "产品设计", new[] { new NotePage("page-ui", "界面"), new NotePage("page-input", "交互") }, new[] { compatibility });
        return new NotebookWorkspace("notebook", "墨笺", new[] { product });
    }

    [Fact]
    public void FlattenSections_TraversesArbitraryDepthTree()
    {
        var flattened = NestedWorkspace().FlattenSections();
        Assert.Contains(flattened, row => row.Section.Title == "产品设计" && row.Depth == 0);
        Assert.Contains(flattened, row => row.Section.Title == "跨端兼容" && row.Depth == 1);
        Assert.Contains(flattened, row => row.Section.Title == "笔迹引擎" && row.Depth == 2);
    }

    [Fact]
    public void PagePaneRows_CurrentSectionMode_ReturnsOnlyDirectPages()
    {
        var workspace = NestedWorkspace();
        workspace.SelectSection("section-product", PagePaneMode.CurrentSection);
        var rows = workspace.GetPagePaneRows(PagePaneMode.CurrentSection);
        Assert.Equal(2, rows.Count);
        Assert.All(rows, row => Assert.False(row.IsHeader));
    }

    [Fact]
    public void PagePaneRows_RecursiveMode_IncludesChildSectionsAndPages()
    {
        var workspace = NestedWorkspace();
        workspace.SelectSection("section-product", PagePaneMode.Recursive);
        var rows = workspace.GetPagePaneRows(PagePaneMode.Recursive);
        Assert.Contains(rows, row => row.IsHeader && row.Title == "跨端兼容");
        Assert.Contains(rows, row => !row.IsHeader && row.Page?.Title == "格式研究");
        Assert.Contains(rows, row => row.IsHeader && row.Title == "笔迹引擎");
    }

    [Fact]
    public void Breadcrumb_ReturnsCorrectHierarchyPath()
    {
        var breadcrumb = NestedWorkspace().Breadcrumb("section-engine");
        Assert.Equal(new[] { "产品设计", "跨端兼容", "笔迹引擎" }, breadcrumb.Select(section => section.Title));
    }

    [Fact]
    public void AddSection_UnderFocusedSection_CreatesChildSection()
    {
        var workspace = NestedWorkspace();
        workspace.SelectSection("section-product");
        var child = workspace.AddSectionAtFocus();
        Assert.Contains(child, workspace.FindSection("section-product")!.Children);
        Assert.Equal(child.Id, workspace.FocusedSectionId);
    }

    [Fact]
    public void NotebookRoot_RecursiveMode_ShowsWholeNotebook_ButDirectModeIsEmpty()
    {
        var workspace = NestedWorkspace();
        workspace.FocusNotebook(PagePaneMode.CurrentSection);
        Assert.Empty(workspace.GetPagePaneRows(PagePaneMode.CurrentSection));
        workspace.FocusNotebook(PagePaneMode.Recursive);
        Assert.Contains(workspace.GetPagePaneRows(PagePaneMode.Recursive), row => row.Page?.Id == "page-engine");
    }

    [Fact]
    public void SectionCopyAndMerge_PreserveHierarchyAndSelectedPageOwner()
    {
        var workspace = NestedWorkspace();
        var target = workspace.AddSection("目标");

        var copy = workspace.CopySectionToParent("section-compat", target.Id, out var copiedPages);
        Assert.NotNull(copy);
        Assert.Equal("格式研究 - 副本", Assert.Single(copy!.Pages).Title);
        Assert.True(copiedPages.ContainsKey("page-format"));
        Assert.Equal("笔迹引擎 - 副本", Assert.Single(copy.Children).Title);

        workspace.SelectPage("section-product", "page-ui", PagePaneMode.CurrentSection);
        Assert.True(workspace.MergeSectionInto("section-product", target.Id));
        Assert.Equal(target.Id, workspace.SelectedPageSectionId);
        Assert.Contains(target.Pages, page => page.Id == "page-ui");
        Assert.Null(workspace.FindSection("section-product"));
    }

    [Fact]
    public void PageMoveCopyAndDelete_UpdateOrderAndSelection()
    {
        var workspace = NestedWorkspace();
        var target = workspace.AddSection("目标");

        var copy = workspace.CopyPage("section-product", "page-ui", target.Id);
        Assert.NotNull(copy);
        Assert.Equal(target.Id, workspace.SelectedPageSectionId);
        Assert.Equal(copy!.Id, workspace.SelectedPageId);

        Assert.True(workspace.MovePageAdjacent("section-product", "page-input", target.Id, copy.Id, after: false));
        Assert.Equal(new[] { "page-input", copy.Id }, target.Pages.Select(page => page.Id));
        Assert.NotNull(workspace.DeletePage(target.Id, copy.Id));
        Assert.DoesNotContain(target.Pages, page => page.Id == copy.Id);
    }
}
