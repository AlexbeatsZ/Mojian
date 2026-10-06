using System.IO;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;
using Xunit;

namespace Notein_win.Tests;

public class MainViewModelTests
{
    [Fact]
    public void FullPageMode_UsesTransientPaneStateAndPreservesNormalPanePreferences()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences)
        {
            IsSectionPaneVisible = true,
            IsPagePaneVisible = true,
        };

        viewModel.IsFullPageMode = true;

        Assert.False(viewModel.IsSectionPaneOpen);
        Assert.False(viewModel.IsPagePaneOpen);
        viewModel.ToggleSectionPaneCommand.Execute(null);
        Assert.True(viewModel.IsSectionPaneOpen);
        Assert.False(viewModel.IsPagePaneOpen);

        viewModel.IsFullPageMode = false;

        Assert.True(viewModel.IsSectionPaneVisible);
        Assert.True(viewModel.IsPagePaneVisible);
        Assert.True(viewModel.IsSectionPaneOpen);
        Assert.True(viewModel.IsPagePaneOpen);
    }

    [Fact]
    public void NotebookPaneRow_UsesAndroidExpansionAndRootFocusContract()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences);

        var notebookRow = Assert.Single(viewModel.NotebookTreeRows.OfType<NotebookPaneRow>());
        Assert.True(notebookRow.IsActive);
        Assert.Equal("#7719AA", notebookRow.AccentHex);
        Assert.NotEmpty(viewModel.NotebookTreeRows.OfType<SectionRow>());

        viewModel.ToggleNotebookExpandCommand.Execute(notebookRow.Id);
        Assert.Empty(viewModel.NotebookTreeRows.OfType<SectionRow>());
        viewModel.SelectNotebookCommand.Execute(notebookRow.Id);

        Assert.Null(viewModel.ActiveWorkspace.FocusedSectionId);
        Assert.Empty(viewModel.PagePaneRows);
    }

    [Fact]
    public void ActiveNotebookSectionsAreInsertedImmediatelyBelowTheirNotebookRow()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences);

        viewModel.AddNotebook("Middle");
        var middleId = viewModel.ActiveWorkspace.Id;
        var middleSectionId = Assert.Single(viewModel.ActiveWorkspace.Sections).Id;
        viewModel.AddNotebook("Last");
        var lastId = viewModel.ActiveWorkspace.Id;

        viewModel.SelectNotebookCommand.Execute(middleId);

        var rows = viewModel.NotebookTreeRows.ToList();
        var middleIndex = rows.FindIndex(row => row is NotebookPaneRow notebook && notebook.Id == middleId);
        Assert.True(middleIndex >= 0);
        Assert.Equal(middleSectionId, Assert.IsType<SectionRow>(rows[middleIndex + 1]).Section.Id);
        Assert.Equal(lastId, Assert.IsType<NotebookPaneRow>(rows[middleIndex + 2]).Id);
    }

    [Fact]
    public void AddPdfPage_PreservesNormalPageTemplateAndPersistsSourceGeometry()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences);
        var normalAppearance = new PageAppearance
        {
            PaperKind = PaperKind.A4,
            Theme = PageTheme.Light,
            Background = CanvasBackgroundFormat.GridStandard,
            PageColor = "#E2EDF8",
        };
        Assert.NotNull(viewModel.AddPage("模板页", normalAppearance));

        var sourcePath = Path.Combine(temp.Path, "source.pdf");
        File.WriteAllBytes(sourcePath, "%PDF-test"u8.ToArray());
        var rendered = new RenderedPdfPage(2, 3, 612, 792, new byte[] { 137, 80, 78, 71 });

        var document = viewModel.AddPdfPage("来源 2", rendered, sourcePath, invert: true);

        Assert.NotNull(document);
        Assert.Equal(PaperKind.A4, viewModel.LastPageAppearance.PaperKind);
        Assert.Equal("#E2EDF8", viewModel.LastPageAppearance.PageColor);
        Assert.Equal(612, viewModel.ActiveWorkspace.SelectedPage()!.PdfWidthPoints);
        Assert.Equal(792, viewModel.ActiveWorkspace.SelectedPage()!.PdfHeightPoints);
        Assert.Equal("source.pdf", viewModel.ActiveWorkspace.SelectedPage()!.PdfSourceDocumentName);
        var background = Assert.Single(document!.Objects);
        Assert.True(background.IsPageBackground);
        Assert.True(background.ViewInverted);
        Assert.Equal(background.SourceAssetName, viewModel.ActiveWorkspace.SelectedPage()!.PdfSourceAssetName);
        var reloadedStore = new WorkspaceStore(workspace);
        Assert.True(File.Exists(reloadedStore.ResolveAsset(document.NotebookId, document.PageId, background.AssetName!)));

        var reloaded = reloadedStore.LoadPage(document.NotebookId, document.PageId);
        Assert.True(Assert.Single(reloaded.Objects).IsPageBackground);
    }

    [Fact]
    public void SpringLoadedEraserAndPanoramaExposeRealToolbarStateThenRestoreSelection()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences);
        Assert.True(viewModel.SelectToolImmediate("highlighter-blue"));
        var original = viewModel.SelectedTool;
        var eraser = viewModel.PressureEraserTool;
        eraser.EraserRadiusMm = ToolPreset.MinimumEraserRadiusMm;

        Assert.Same(eraser, viewModel.BeginSpringLoadedTool(SpringLoadedToolKind.Eraser));
        Assert.Same(eraser, viewModel.SelectedTool);
        Assert.True(eraser.IsSelected);
        viewModel.EndSpringLoadedTool(SpringLoadedToolKind.Eraser);
        Assert.Same(original, viewModel.SelectedTool);

        Assert.Null(viewModel.BeginSpringLoadedTool(SpringLoadedToolKind.PanoramaHand));
        Assert.True(viewModel.SingleFingerPanEnabled);
        Assert.DoesNotContain(viewModel.Tools, tool => tool.IsSelected);
        viewModel.EndSpringLoadedTool(SpringLoadedToolKind.PanoramaHand);
        Assert.False(viewModel.SingleFingerPanEnabled);
        Assert.Same(original, viewModel.SelectedTool);
    }

    [Fact]
    public void ToolFlyoutRequiresASecondClickAndAnOpenFlyoutConsumesTheNextToolClick()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var viewModel = new MainViewModel(workspace, preferences);
        var first = viewModel.SelectedTool;
        var second = viewModel.ToolShelf.Tools.First(tool => tool.Id != first.Id);

        viewModel.SingleFingerPanEnabled = true;
        viewModel.SelectToolFromToolbar(first.Id, flyoutWasOpenAtPointerDown: false);
        Assert.False(viewModel.SingleFingerPanEnabled);
        Assert.False(viewModel.IsToolFlyoutOpen);

        viewModel.SelectToolFromToolbar(second.Id, flyoutWasOpenAtPointerDown: false);
        Assert.Same(second, viewModel.SelectedTool);
        Assert.False(viewModel.IsToolFlyoutOpen);

        viewModel.SelectToolFromToolbar(second.Id, flyoutWasOpenAtPointerDown: false);
        Assert.True(viewModel.IsToolFlyoutOpen);

        viewModel.SelectToolFromToolbar(first.Id, flyoutWasOpenAtPointerDown: true);
        Assert.Same(first, viewModel.SelectedTool);
        Assert.False(viewModel.IsToolFlyoutOpen);

        viewModel.SelectToolFromToolbar(first.Id, flyoutWasOpenAtPointerDown: false);
        Assert.True(viewModel.IsToolFlyoutOpen);
        viewModel.SelectToolFromToolbar(first.Id, flyoutWasOpenAtPointerDown: true);
        Assert.False(viewModel.IsToolFlyoutOpen);
    }

    [Fact]
    public void LegacyPenPressureSettingsMigrateIntoEraserAndPanoramaSensitivityPersists()
    {
        using var temp = new TemporaryDirectory();
        var workspace = Path.Combine(temp.Path, "workspace");
        var store = new WorkspaceStore(workspace);
        var shelf = new ToolShelf();
        var pen = shelf.Tools.First(tool => tool.Kind == DrawingToolKind.Pen);
        pen.PressureEraseEnabled = true;
        pen.PressureEraseThresholdPercent = 66;
        pen.PressureEraseDwellMilliseconds = 230;
        store.SaveSettings(new WorkspaceSettings { Version = 2, ToolShelfJson = shelf.ToJson() });
        var preferencesPath = Path.Combine(temp.Path, "preferences.json");

        var viewModel = new MainViewModel(workspace, new PreferencesStore(preferencesPath));
        Assert.True(viewModel.PressureEraserTool.PressureEraseEnabled);
        Assert.Equal(66, viewModel.PressureEraserTool.PressureEraseThresholdPercent);
        Assert.Equal(230, viewModel.PressureEraserTool.PressureEraseDwellMilliseconds);
        viewModel.PanoramaSensitivity = 2.25;
        viewModel.SaveAll();

        var reloaded = new MainViewModel(workspace, new PreferencesStore(preferencesPath));
        Assert.Equal(2.25, reloaded.PanoramaSensitivity, 3);
        Assert.Equal(3, new WorkspaceStore(workspace).LoadSettings().Version);
    }

    [Fact]
    public void DeleteNotebook_RemovesNestedPagesAndAssetsAndSurvivesRestart()
    {
        using var temp = new TemporaryDirectory();
        var root = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var vm = new MainViewModel(root, preferences);
        var keptId = vm.ActiveWorkspace.Id;
        var keptDocument = vm.PrimaryDocument!;
        vm.AddNotebook("Delete me");
        var deletedId = vm.ActiveWorkspace.Id;
        var deletedDocument = vm.PrimaryDocument!;
        vm.Store.WriteAsset(deletedId, deletedDocument.PageId, ".bin", new byte[] { 1, 2, 3 });
        vm.AddChildSection(vm.ActiveWorkspace.Sections[0].Id, "Nested");
        vm.AddPage("Nested page", new PageAppearance());
        vm.ToggleSplitViewCommand.Execute(null);
        var directory = vm.Store.GetNotebookDirectory(deletedId);

        Assert.True(vm.DeleteNotebook(deletedId));
        vm.SaveDocument(deletedDocument); // A late canvas notification cannot resurrect deleted data.
        vm.SaveAll();

        Assert.False(Directory.Exists(directory));
        Assert.Equal(keptId, vm.ActiveWorkspace.Id);
        Assert.DoesNotContain(vm.Library.Notebooks, book => book.Id == deletedId);
        Assert.True(vm.PrimaryDocument?.NotebookId != deletedId);
        Assert.True(vm.SecondaryDocument?.NotebookId != deletedId);
        Assert.True(File.Exists(vm.Store.GetPageDocumentPath(keptId, keptDocument.PageId)));
        var restored = new MainViewModel(root, preferences);
        Assert.Equal(keptId, Assert.Single(restored.Library.Notebooks).Id);
        Assert.False(Directory.Exists(directory));
    }

    [Fact]
    public void DeleteInactiveNotebook_PreservesFocusedPageAndOtherSplitSlot()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var deletedId = vm.ActiveWorkspace.Id;
        vm.ToggleSplitViewCommand.Execute(null);
        vm.FocusCanvas(CanvasSlot.Right);
        vm.AddNotebook("Keep me");
        var keptDocument = vm.SecondaryDocument;

        Assert.True(vm.DeleteNotebook(deletedId));

        Assert.Same(keptDocument, vm.SecondaryDocument);
        Assert.Same(keptDocument, vm.FocusedDocument);
        Assert.Null(vm.PrimaryDocument);
        Assert.Equal(keptDocument!.NotebookId, vm.ActiveWorkspace.Id);
        vm.SaveAll();
        Assert.False(Directory.Exists(Path.Combine(vm.WorkspaceRoot, "notebooks", deletedId)));
    }

    [Fact]
    public void DeleteLastNotebook_CreatesAnEmptyReplacementWithoutRestoringSampleContent()
    {
        using var temp = new TemporaryDirectory();
        var root = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var vm = new MainViewModel(root, preferences);
        var deletedId = vm.ActiveWorkspace.Id;

        Assert.True(vm.DeleteNotebook(deletedId));
        vm.SaveAll();

        var replacement = Assert.Single(vm.Library.Notebooks);
        Assert.NotEqual(deletedId, replacement.Id);
        Assert.Empty(replacement.Sections);
        Assert.Null(vm.FocusedDocument);
        Assert.False(vm.DeleteNotebook("missing"));
        Assert.Empty(Assert.Single(new MainViewModel(root, preferences).Library.Notebooks).Sections);
    }

    [Fact]
    public void RenameInactiveNotebook_PersistsWithoutChangingFocus()
    {
        using var temp = new TemporaryDirectory();
        var root = Path.Combine(temp.Path, "workspace");
        var preferences = new PreferencesStore(Path.Combine(temp.Path, "preferences.json"));
        var vm = new MainViewModel(root, preferences);
        var renamedId = vm.ActiveWorkspace.Id;
        vm.AddNotebook("Focused");
        var focusedId = vm.ActiveWorkspace.Id;

        Assert.True(vm.RenameNotebook(renamedId, "  Renamed  "));
        Assert.False(vm.RenameNotebook(renamedId, "  "));
        Assert.Equal(focusedId, vm.ActiveWorkspace.Id);
        Assert.Equal("Renamed", new WorkspaceStore(root).LoadLibrary().Notebooks.Single(book => book.Id == renamedId).Title);
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-win-tests", Guid.NewGuid().ToString("N"));

        public TemporaryDirectory() => Directory.CreateDirectory(Path);

        public void Dispose()
        {
            if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true);
        }
    }
}
