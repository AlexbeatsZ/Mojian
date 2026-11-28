using System.IO.Compression;
using System.IO;
using System.Text.Json.Nodes;
using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class WorkspaceStoreTests
{
    private static string FileName(string path) => System.IO.Path.GetFileName(path);

    private static (WorkspaceStore Store, NotebookLibrary Library) Seeded(string root)
    {
        var store = new WorkspaceStore(root);
        return (store, store.LoadLibrary());
    }

    [Fact]
    public void LoadLibrary_SeedsOnlyNewEmptyWorkspaceAsRealFolders()
    {
        using var temp = new TemporaryDirectory();
        Assert.True(WorkspaceStore.IsNewWorkspace(temp.Path));
        var (store, library) = Seeded(temp.Path);
        var notebook = library.ActiveNotebook;
        var section = notebook.Sections[0];
        var page = section.Pages[0];

        Assert.True(Directory.Exists(System.IO.Path.Combine(temp.Path, notebook.Title, section.Title)));
        Assert.Equal(System.IO.Path.Combine(temp.Path, notebook.Title, section.Title, page.Title + ".mojian"), store.GetPageDocumentPath(notebook.Id, page.Id));
        Assert.True(File.Exists(System.IO.Path.Combine(temp.Path, notebook.Title, ".mojian-folder.json")));
        Assert.False(WorkspaceStore.IsNewWorkspace(temp.Path));
        Assert.NotEmpty(store.LoadPage(notebook.Id, page.Id).TextItems);
        Assert.Empty(Directory.EnumerateFiles(temp.Path, "*.previous", SearchOption.AllDirectories).Where(path => !path.Contains(".mojian" + System.IO.Path.DirectorySeparatorChar)));
    }

    [Fact]
    public void LoadLibrary_RefusesToSeedEmptyNativeSyncClone()
    {
        using var temp = new TemporaryDirectory();
        File.WriteAllText(System.IO.Path.Combine(temp.Path, ".mojian-native-workspace"), "clone");

        Assert.Throws<WorkspaceLibraryMissingException>(() => new WorkspaceStore(temp.Path).LoadLibrary());
        Assert.Empty(Directory.EnumerateDirectories(temp.Path).Where(path => !FileName(path).StartsWith('.')));
    }

    [Fact]
    public void RenamesMovesAndReordersBecomeFolderOperations()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var notebook = library.ActiveNotebook;
        var first = notebook.Sections[0];
        var second = new SectionNode("section-second", "第二/分区");
        notebook.Sections.Add(second);
        var page = first.Pages[0];
        store.SaveLibrary(library);
        Assert.True(Directory.Exists(System.IO.Path.Combine(temp.Path, notebook.Title, "第二\uFF0F分区")));

        first.Title = "改名后的分区";
        first.Pages.Remove(page);
        second.Pages.Add(page);
        page.Title = "移动后的页";
        notebook.Sections.Reverse();
        store.SaveLibrary(library);

        Assert.Equal(System.IO.Path.Combine(temp.Path, notebook.Title, "第二\uFF0F分区", "移动后的页.mojian"), store.GetPageDocumentPath(notebook.Id, page.Id));
        Assert.True(Directory.Exists(System.IO.Path.Combine(temp.Path, notebook.Title, "改名后的分区")));
        var reloaded = new WorkspaceStore(temp.Path).LoadLibrary().ActiveNotebook;
        Assert.Equal(new[] { "第二\uFF0F分区", "改名后的分区" }, reloaded.Sections.Select(section => section.Title));
        Assert.Equal(page.Id, Assert.Single(reloaded.Sections[0].Pages).Id);
        Assert.NotEmpty(new WorkspaceStore(temp.Path).LoadPage(notebook.Id, page.Id).TextItems);
    }

    [Fact]
    public void DuplicateTitlesGetExplorerStyleSuffixes()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var section = library.ActiveNotebook.Sections[0];
        section.Pages.Add(new NotePage("page-a", "同名"));
        section.Pages.Add(new NotePage("page-b", "同名"));
        store.SaveLibrary(library);

        var names = Directory.EnumerateFiles(System.IO.Path.GetDirectoryName(store.GetPageDocumentPath("", "page-a"))!, "同名*.mojian").Select(FileName).Order().ToArray();
        Assert.Equal(new[] { "同名 (2).mojian", "同名.mojian" }, names);
        Assert.Equal(new[] { "同名", "同名 (2)" }, new WorkspaceStore(temp.Path).LoadLibrary().ActiveNotebook.Sections[0].Pages.Skip(1).Select(page => page.Title));
    }

    [Fact]
    public void FoldersAndPagesMadeInFileManagerAreAdopted()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var notebookFolder = System.IO.Path.Combine(temp.Path, library.ActiveNotebook.Title);
        Directory.CreateDirectory(System.IO.Path.Combine(notebookFolder, "外部分区"));
        File.Copy(store.GetPageDocumentPath("", library.ActiveNotebook.Sections[0].Pages[0].Id),
            System.IO.Path.Combine(notebookFolder, "外部分区", "复制的页.mojian"));

        var reloaded = new WorkspaceStore(temp.Path).LoadLibrary().ActiveNotebook;
        var adopted = reloaded.Sections.Single(section => section.Title == "外部分区");
        var copy = Assert.Single(adopted.Pages);
        Assert.Equal("复制的页", copy.Title);
        Assert.NotEqual(library.ActiveNotebook.Sections[0].Pages[0].Id, copy.Id);
        Assert.True(File.Exists(System.IO.Path.Combine(notebookFolder, "外部分区", ".mojian-folder.json")));
    }

    [Fact]
    public void MetadataEditsAndPageSavesNeverOverwriteEachOther()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var notebook = library.ActiveNotebook;
        var page = notebook.Sections[0].Pages[0];
        var document = store.LoadPage(notebook.Id, page.Id);

        page.Appearance.PageColor = "#123456";
        store.SaveLibrary(library);
        document.TextItems.Add(new TextItem { Text = "after metadata edit" });
        store.SavePage(document);

        var reloaded = new WorkspaceStore(temp.Path);
        Assert.Equal("#123456", reloaded.LoadLibrary().ActiveNotebook.Sections[0].Pages[0].Appearance.PageColor);
        Assert.Contains(reloaded.LoadPage(notebook.Id, page.Id).TextItems, item => item.Text == "after metadata edit");
    }

    [Fact]
    public void ContentSavedBeforeThePageJoinsTheLibraryIsKept()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var section = library.ActiveNotebook.Sections[0];
        var page = new NotePage("page-new", "新页面");
        section.Pages.Add(page);
        var document = new PageDocument(library.ActiveNotebook.Id, page.Id);
        document.TextItems.Add(new TextItem { Text = "staged" });
        store.SavePage(document);
        store.SaveLibrary(library);

        Assert.EndsWith(System.IO.Path.Combine(section.Title, "新页面.mojian"), store.GetPageDocumentPath(library.ActiveNotebook.Id, page.Id));
        Assert.Equal("staged", Assert.Single(new WorkspaceStore(temp.Path).LoadPage(library.ActiveNotebook.Id, page.Id).TextItems).Text);
        Assert.Empty(Directory.EnumerateFiles(System.IO.Path.Combine(temp.Path, ".mojian", "pending")));
    }

    [Fact]
    public void AnotherDevicesFolderChangeBlocksStaleLibrarySaves()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        Directory.CreateDirectory(System.IO.Path.Combine(temp.Path, library.ActiveNotebook.Title, "另一台设备的分区"));

        Assert.True(store.HasExternalChanges);
        library.ActiveNotebook.Title = "本机改名";
        Assert.Throws<IOException>(() => store.SaveLibrary(library));
        Assert.Throws<IOException>(() => store.ValidateLibraryUnchanged());
    }

    [Fact]
    public async Task SaveLibrary_WaitsForScannerToReleaseDescriptor()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var descriptor = System.IO.Path.Combine(temp.Path, library.ActiveNotebook.Title, library.ActiveNotebook.Sections[0].Title, ".mojian-folder.json");
        // Scanners hash files with read/write sharing but without FILE_SHARE_DELETE.
        var scanner = new FileStream(descriptor, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var release = Task.Run(async () => { await Task.Delay(300); scanner.Dispose(); });

        library.ActiveNotebook.Sections.Insert(0, new SectionNode("section-front", "排到最前"));
        library.ActiveNotebook.Sections.Add(library.ActiveNotebook.Sections[1]);
        library.ActiveNotebook.Sections.RemoveAt(1);
        store.SaveLibrary(library);
        await release;

        Assert.Equal("排到最前", new WorkspaceStore(temp.Path).LoadLibrary().ActiveNotebook.Sections[0].Title);
        Assert.Empty(Directory.EnumerateFiles(temp.Path, "*.tmp", SearchOption.AllDirectories));
    }

    [Fact]
    public void PageAndAssets_UseFolderLibraryLayout()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var notebook = library.ActiveNotebook;
        var page = notebook.Sections[0].Pages[0];
        var document = store.LoadPage(notebook.Id, page.Id);
        document.Strokes.Add(new StrokeRecord { Points = new List<InkPoint> { new(1, 2), new(3, 4) } });
        store.SavePage(document);
        var asset = store.WriteAsset(notebook.Id, page.Id, ".bin", new byte[] { 1, 2, 3 });

        Assert.Equal(System.IO.Path.Combine(temp.Path, ".mojian", "assets", page.Id, asset), store.ResolveAsset(notebook.Id, page.Id, asset));
        Assert.True(File.Exists(store.ResolveAsset(notebook.Id, page.Id, asset)));
        Assert.Single(new WorkspaceStore(temp.Path).LoadPage(notebook.Id, page.Id).Strokes);
        var head = PageFileFormat.ReadHead(store.GetPageDocumentPath(notebook.Id, page.Id));
        Assert.Equal((3, page.Id), (head.Version, head.Id));
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
    public void NotebookPackage_PreservesAndroidPdfPageAndImageTransformContract()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(System.IO.Path.Combine(temp.Path, "workspace"));
        var notebook = NotebookWorkspace.Sample();
        var page = notebook.SelectedPage()!;
        page.IsPdfPage = true;
        page.PdfPageNumber = 2;
        page.PdfPageCount = 3;
        page.PdfWidthPoints = 612;
        page.PdfHeightPoints = 792;
        page.Appearance = new PageAppearance { PaperKind = PaperKind.Pdf, Width = 816, Height = 1056 };
        page.PdfSourceAssetName = store.WriteAsset(notebook.Id, page.Id, ".pdf", "%PDF-test"u8);
        page.PdfSourceDocumentName = "source.pdf";
        var renderedAsset = store.WriteAsset(notebook.Id, page.Id, ".png", new byte[] { 137, 80, 78, 71 });
        store.SavePage(new PageDocument(notebook.Id, page.Id)
        {
            Objects =
            {
                new PageObject
                {
                    Kind = PageObjectKind.PdfPrintout,
                    AssetName = renderedAsset,
                    SourceAssetName = page.PdfSourceAssetName,
                    SourcePageNumber = 2,
                    IsPageBackground = true,
                    RotationQuarterTurns = 1,
                    FlipHorizontal = true,
                },
            },
        });
        var packagePath = System.IO.Path.Combine(temp.Path, "pdf-page.mjn");

        NotebookPackageService.Export(packagePath, notebook, store, page.Id);

        using var archive = ZipFile.OpenRead(packagePath);
        using var manifestReader = new StreamReader(archive.GetEntry("manifest.json")!.Open());
        var manifestPage = JsonNode.Parse(manifestReader.ReadToEnd())!["sections"]![0]!["pages"]![0]!;
        Assert.Equal("PDF", manifestPage["viewSettings"]!["paperSize"]!.GetValue<string>());
        Assert.Equal(816, manifestPage["viewSettings"]!["customDocumentWidth"]!.GetValue<double>());
        Assert.Equal(page.PdfSourceAssetName, manifestPage["pdf"]!["sourceAssetName"]!.GetValue<string>());
        Assert.Equal(612, manifestPage["pdf"]!["widthPoints"]!.GetValue<double>());

        using var pageReader = new StreamReader(archive.GetEntry($"pages/{page.Id}.json")!.Open());
        var exportedObject = JsonNode.Parse(pageReader.ReadToEnd())!["objects"]![0]!;
        Assert.Equal("PDF_PAGE", exportedObject["sourceKind"]!.GetValue<string>());
        Assert.True(exportedObject["pageBackground"]!.GetValue<bool>());
        Assert.Equal(1, exportedObject["rotationQuarterTurns"]!.GetValue<int>());
        Assert.True(exportedObject["flipHorizontal"]!.GetValue<bool>());
    }

    [Fact]
    public void CopyPageData_StagesUntilTheCopyJoinsTheLibrary()
    {
        using var temp = new TemporaryDirectory();
        var (store, library) = Seeded(temp.Path);
        var notebook = library.ActiveNotebook;
        var source = notebook.Sections[0].Pages[0];
        var assetName = store.WriteAsset(notebook.Id, source.Id, ".bin", new byte[] { 7, 8, 9 });

        store.CopyPageData(notebook.Id, source.Id, notebook.Id, "page-copy");
        var copy = store.LoadPage(notebook.Id, "page-copy");
        Assert.Equal("page-copy", copy.PageId);
        Assert.NotEmpty(copy.TextItems);
        Assert.Equal(new byte[] { 7, 8, 9 }, File.ReadAllBytes(store.ResolveAsset(notebook.Id, copy.PageId, assetName)));

        store.DeletePageData(notebook.Id, copy.PageId);
        Assert.False(File.Exists(store.GetPageDocumentPath(notebook.Id, copy.PageId)));
        Assert.False(Directory.Exists(System.IO.Path.Combine(temp.Path, ".mojian", "assets", "page-copy")));
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
