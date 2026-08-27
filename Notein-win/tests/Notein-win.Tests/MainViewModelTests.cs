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

        var notebookRow = Assert.Single(viewModel.NotebookRows);
        Assert.True(notebookRow.IsActive);
        Assert.Equal("#7719AA", notebookRow.AccentHex);
        Assert.NotEmpty(viewModel.SectionRows);

        viewModel.ToggleNotebookExpandCommand.Execute(notebookRow.Id);
        Assert.Empty(viewModel.SectionRows);
        viewModel.SelectNotebookCommand.Execute(notebookRow.Id);

        Assert.Null(viewModel.ActiveWorkspace.FocusedSectionId);
        Assert.Empty(viewModel.PagePaneRows);
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
