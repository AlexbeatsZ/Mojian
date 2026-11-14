using System.IO;
using System.Text.Json;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public sealed class ArchitectureRepairTests
{
    private sealed class Workspace : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), ".agents", "mojian-architecture-" + Guid.NewGuid().ToString("N"));
        public Workspace() => Directory.CreateDirectory(Root);
        public MainViewModel Model() => new(Root, new PreferencesStore(Path.Combine(Root, "preferences.json")));
        public void Fixture()
        {
            File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures", "workspace-v2", "library.json"), Path.Combine(Root, "library.json"));
            var page = Path.Combine(Root, "notebooks", "book.dot", "pages", "page.dot.json");
            Directory.CreateDirectory(Path.GetDirectoryName(page)!);
            File.Copy(Path.Combine(AppContext.BaseDirectory, "Fixtures", "workspace-v2", "page.json"), page);
        }
        public void Dispose() => Directory.Delete(Root, recursive: true);
    }

    [Fact]
    public void NavigationAndContentAutosaveKeepTheSharedIndexByteIdentical()
    {
        using var temp = new Workspace();
        temp.Fixture();
        var model = temp.Model();
        var before = File.ReadAllBytes(model.Store.LibraryPath);
        model.ActiveWorkspace.FindSection("section.dot")!.Expanded = false;
        model.ToggleNotebookExpandCommand.Execute("book.dot");
        model.FocusedDocument!.TextItems.Add(new TextItem { Text = "local edit" });
        model.QueueSaveDocument(model.FocusedDocument);
        model.SaveAll();
        Assert.Equal(before, File.ReadAllBytes(model.Store.LibraryPath));
        var reopened = temp.Model();
        Assert.False(reopened.ActiveWorkspace.Expanded);
        Assert.False(reopened.ActiveWorkspace.FindSection("section.dot")!.Expanded);
        Assert.Contains(reopened.FocusedDocument!.TextItems, text => text.Text == "local edit");
    }

    [Fact]
    public void UntouchedLegacyStrokeKeepsRawFieldsAndEditedPointsRemainEditable()
    {
        using var temp = new Workspace(); temp.Fixture();
        var store = new WorkspaceStore(temp.Root);
        var path = store.GetPageDocumentPath("book.dot", "page.dot");
        using var original = JsonDocument.Parse(File.ReadAllText(path));
        var expected = original.RootElement.GetProperty("strokes")[0].GetRawText();
        var document = store.LoadPage("book.dot", "page.dot");
        document.TextItems.Add(new TextItem { Text = "another edit" });
        store.SavePage(document);
        using (var saved = JsonDocument.Parse(File.ReadAllText(path)))
            Assert.Equal(expected, saved.RootElement.GetProperty("strokes")[0].GetRawText());
        document.Strokes[0].Points[0].X = 99;
        store.SavePage(document);
        using var changed = JsonDocument.Parse(File.ReadAllText(path));
        var point = changed.RootElement.GetProperty("strokes")[0].GetProperty("points")[0];
        Assert.Equal(99, point.GetProperty("x").GetSingle());
        Assert.Equal("retained", point.GetProperty("futureSample").GetString());
    }

    [Fact]
    public void MissingIndexedPageCannotBeCreatedByReadNavigationOrClose()
    {
        using var temp = new Workspace(); temp.Fixture();
        var store = new WorkspaceStore(temp.Root);
        var path = store.GetPageDocumentPath("book.dot", "page.dot"); File.Delete(path);
        Assert.Throws<PageContentMissingException>(() => store.LoadPage("book.dot", "page.dot"));
        var model = temp.Model();
        Assert.Null(model.FocusedDocument);
        model.SaveAll();
        Assert.False(File.Exists(path));
        Assert.False(Directory.Exists(Path.GetDirectoryName(store.ResolveAsset("unused", "page", "image.png"))));
    }

    [Fact]
    public void CopyFailureKeepsThePublishedHierarchyAndOriginalData()
    {
        using var temp = new Workspace();
        var model = temp.Model(); var source = model.FocusedDocument!;
        source.Objects.Add(new PageObject { Kind = PageObjectKind.Attachment, AssetName = "missing.bin" });
        model.SaveDocument(source);
        var before = File.ReadAllBytes(model.Store.LibraryPath);
        var section = model.ActiveWorkspace.SelectedPageSectionId;
        var count = model.ActiveWorkspace.FindSection(section)!.Pages.Count;
        Assert.Throws<FileNotFoundException>(() => model.CopyPage(section, source.PageId, section));
        Assert.Equal(count, model.ActiveWorkspace.FindSection(section)!.Pages.Count);
        Assert.Equal(before, File.ReadAllBytes(model.Store.LibraryPath));
        Assert.True(File.Exists(model.Store.GetPageDocumentPath(source.NotebookId, source.PageId)));
    }

    [Fact]
    public void FailedExternalWriteCanBePreservedWithoutOverwritingTheRemotePage()
    {
        using var temp = new Workspace(); var model = temp.Model(); var live = model.FocusedDocument!;
        var other = new WorkspaceStore(temp.Root); var remote = other.LoadPage(live.NotebookId, live.PageId);
        remote.TextItems.Add(new TextItem { Text = "remote" }); other.SavePage(remote);
        var remoteBytes = File.ReadAllBytes(other.GetPageDocumentPath(live.NotebookId, live.PageId));
        live.TextItems.Add(new TextItem { Text = "unsaved local" }); live.HasPendingContentChanges = true;
        Assert.Throws<IOException>(model.SaveAll);
        var recovery = model.PreserveUnsavedChanges();
        Assert.Contains("unsaved local", File.ReadAllText(Path.Combine(recovery, "current", live.NotebookId, live.PageId + ".json")));
        Assert.Equal(remoteBytes, File.ReadAllBytes(other.GetPageDocumentPath(live.NotebookId, live.PageId)));
    }

    [Fact]
    public void RecoveryCanBindAnUndersizedInfinitePageWithoutSavingWhileLocked()
    {
        using var temp = new Workspace(); var model = temp.Model(); var live = model.FocusedDocument!;
        Assert.Throws<IOException>(() => model.TryApplyNativeSync(() => throw new IOException("partial"), () => true));
        Assert.True(model.NativeSyncRecoveryRequired);
        var remote = new WorkspaceStore(temp.Root); var incoming = remote.LoadPage(live.NotebookId, live.PageId);
        incoming.TextItems.Add(new TextItem { X = 10000, Y = 10000, Text = "far content" }); remote.SavePage(incoming);
        Assert.True(model.TryRecoverNativeSync(() => { }));
        Assert.False(model.NativeSyncRecoveryRequired);
        Assert.True(model.PrimaryPageAppearance.InfiniteWidth > 10000);
    }

    [Fact]
    public void HistorySharesUnchangedInkAndRestoresFrozenVersionsAfterRepeatedEdits()
    {
        var history = new PageContentHistory(); var document = new PageDocument("n", "p");
        var first = new StrokeRecord { Points = [new(1, 2), new(3, 4)] };
        var other = new StrokeRecord { Points = [new(10, 20), new(30, 40)] };
        document.Strokes.AddRange([first, other]);
        var before = history.Capture(document);
        document.TextItems.Add(new TextItem { Text = "new text" });
        var afterText = history.Capture(document);
        Assert.Same(before.Strokes[0].Frozen, afterText.Strokes[0].Frozen);
        first.Points[0].X = 100; first.InvalidateBounds();
        var afterMove = history.Capture(document);
        Assert.NotSame(before.Strokes[0].Frozen, afterMove.Strokes[0].Frozen);
        history.Restore(before, document);
        Assert.Equal(1, document.Strokes[0].Points[0].X);
        Assert.Same(other, document.Strokes[1]);
        Assert.Empty(document.TextItems);
        document.Strokes[0].Points[0].X = 200; document.Strokes[0].InvalidateBounds();
        history.Restore(afterMove, document);
        Assert.Equal(100, document.Strokes[0].Points[0].X);
        Assert.Equal("new text", Assert.Single(document.TextItems).Text);
    }

    [Theory]
    [InlineData("book.dot")]
    [InlineData("page-0123_ab")]
    public void LocalIdsAreLossless(string id) => Assert.Equal(id, WorkspaceStore.SafeId(id));

    [Theory]
    [InlineData(".PNG", ".png")]
    [InlineData("pdf", ".pdf")]
    [InlineData(".文档", "")]
    [InlineData(".tar~", "")]
    [InlineData("", "")]
    public void StoredAssetExtensionsAreSyncSafe(string extension, string expected) =>
        Assert.Equal(expected, WorkspaceStore.SafeExtension(extension));

    [Theory]
    [InlineData("中文笔记")]
    [InlineData(".hidden")]
    [InlineData("trailing.")]
    [InlineData("../page")]
    [InlineData("a/b")]
    [InlineData("a:b")]
    [InlineData("..")]
    public void InvalidIdsAreRefused(string id) => Assert.Throws<ArgumentException>(() => WorkspaceStore.SafeId(id));
}
