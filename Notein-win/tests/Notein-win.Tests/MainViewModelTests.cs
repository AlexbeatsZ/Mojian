using System.IO;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;
using Xunit;

namespace Notein_win.Tests;

public class MainViewModelTests
{
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
