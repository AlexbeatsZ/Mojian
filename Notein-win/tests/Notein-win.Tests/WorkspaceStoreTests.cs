using System.IO.Compression;
using System.IO;
using System.Text.Json.Nodes;
using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class WorkspaceStoreTests
{
    [Fact]
    public void PageAndAssets_UseSyncthingFriendlyAndroidCompatibleLayout()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var document = new PageDocument("notebook-one", "page-one");
        document.Strokes.Add(new StrokeRecord { Points = new List<InkPoint> { new(1, 2), new(3, 4) } });
        store.SavePage(document);
        var asset = store.WriteAsset(document.NotebookId, document.PageId, ".bin", new byte[] { 1, 2, 3 });

        Assert.Equal(System.IO.Path.Combine(temp.Path, "notebooks", "notebook-one", "pages", "page-one.json"), store.GetPageDocumentPath(document.NotebookId, document.PageId));
        Assert.True(File.Exists(store.ResolveAsset(document.NotebookId, document.PageId, asset)));
        Assert.Single(store.LoadPage(document.NotebookId, document.PageId).Strokes);
    }

    [Fact]
    public void NotebookPackage_ContainsAndroidManifestAndWindowsEditableExtension()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(System.IO.Path.Combine(temp.Path, "workspace"));
        var notebook = NotebookWorkspace.Sample();
        var page = notebook.SelectedPage()!;
        store.SavePage(new PageDocument(notebook.Id, page.Id));
        var packagePath = System.IO.Path.Combine(temp.Path, "notebook.mjn");

        NotebookPackageService.Export(packagePath, notebook, store, page.Id);

        using var archive = ZipFile.OpenRead(packagePath);
        Assert.NotNull(archive.GetEntry("manifest.json"));
        Assert.NotNull(archive.GetEntry($"pages/{page.Id}.json"));
        Assert.NotNull(archive.GetEntry($"windows/pages/{page.Id}.json"));
        using var reader = new StreamReader(archive.GetEntry("manifest.json")!.Open());
        var manifest = JsonNode.Parse(reader.ReadToEnd())!;
        Assert.Equal("mojian-notebook", manifest["format"]!.GetValue<string>());
        Assert.Equal(1, manifest["version"]!.GetValue<int>());
    }

    [Fact]
    public void CopyAndDeletePageData_KeepDocumentAndAssetsTogether()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var source = new PageDocument("notebook-one", "page-source");
        source.TextItems.Add(new TextItem { Text = "copied content" });
        store.SavePage(source);
        var assetName = store.WriteAsset(source.NotebookId, source.PageId, ".bin", new byte[] { 7, 8, 9 });

        store.CopyPageData(source.NotebookId, source.PageId, source.NotebookId, "page-copy");

        var copy = store.LoadPage(source.NotebookId, "page-copy");
        Assert.Equal("page-copy", copy.PageId);
        Assert.Equal("copied content", Assert.Single(copy.TextItems).Text);
        Assert.Equal(new byte[] { 7, 8, 9 }, File.ReadAllBytes(store.ResolveAsset(source.NotebookId, copy.PageId, assetName)));

        store.DeletePageData(source.NotebookId, copy.PageId);
        Assert.False(File.Exists(store.GetPageDocumentPath(source.NotebookId, copy.PageId)));
        Assert.Empty(Directory.EnumerateFiles(store.GetAssetDirectory(source.NotebookId, copy.PageId)));
    }

    [Fact]
    public void SectionPackage_ContainsOnlySelectedSectionTree()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(System.IO.Path.Combine(temp.Path, "workspace"));
        var notebook = NotebookWorkspace.Sample();
        var selected = notebook.Sections[0];
        var sibling = new SectionNode("sibling", "Sibling");
        var siblingPage = new NotePage("sibling-page", "Sibling page");
        sibling.Pages.Add(siblingPage);
        notebook.Sections.Add(sibling);
        foreach (var page in notebook.FlattenAllSections().SelectMany(section => section.Pages))
            store.SavePage(new PageDocument(notebook.Id, page.Id));
        var packagePath = System.IO.Path.Combine(temp.Path, "section.mjn");

        NotebookPackageService.ExportSection(packagePath, notebook, selected, store);

        using var archive = ZipFile.OpenRead(packagePath);
        using var reader = new StreamReader(archive.GetEntry("manifest.json")!.Open());
        var manifest = JsonNode.Parse(reader.ReadToEnd())!;
        Assert.Equal("mojian-section", manifest["format"]!.GetValue<string>());
        Assert.Null(archive.GetEntry($"pages/{siblingPage.Id}.json"));
        Assert.NotNull(archive.GetEntry($"pages/{selected.Pages[0].Id}.json"));
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-win-tests", Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() { if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true); }
    }
}
