using System.IO;
using System.IO.Compression;
using System.Text.Json.Nodes;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class InfinitePageLayoutTests
{
    [Fact]
    public void ThresholdOnlyGrowsTheRightOrBottomEdge()
    {
        var width = InfinitePageLayout.InitialWidth;
        var threshold = width - InfinitePageLayout.AutoGrowMargin;
        Assert.Equal(width, InfinitePageLayout.RequiredExtent(width, threshold - 1, width));
        Assert.Equal(width + InfinitePageLayout.GrowthStep, InfinitePageLayout.RequiredExtent(width, threshold, width), 6);
        Assert.Equal(width, InfinitePageLayout.RequiredExtent(width, double.NaN, width));
    }

    [Theory]
    [InlineData("ink")]
    [InlineData("text")]
    [InlineData("tag")]
    [InlineData("object")]
    public void EveryContentTypeGrowsPaperAndRemovingItCannotShrinkPaper(string kind)
    {
        var document = new PageDocument("notebook", "page");
        const float edge = 3000;
        switch (kind)
        {
            case "ink": document.Strokes.Add(new StrokeRecord { Points = [new InkPoint(edge, edge)] }); break;
            case "text": document.TextItems.Add(new TextItem { X = edge, Y = edge, Text = "hello" }); break;
            case "tag": document.Tags.Add(new CanvasTag(CanvasTagKind.Important, edge, edge)); break;
            case "object": document.Objects.Add(new PageObject { X = edge, Y = edge, Width = 100, Height = 100 }); break;
        }
        var appearance = new PageAppearance();
        Assert.True(InfinitePageLayout.EnsureCapacity(appearance, document));
        var grown = appearance.Clone();
        Assert.True(grown.InfiniteWidth > edge);
        Assert.True(grown.InfiniteHeight > edge);
        Assert.False(InfinitePageLayout.EnsureCapacity(appearance, new PageDocument()));
        Assert.Equal(grown.InfiniteWidth, appearance.InfiniteWidth);
        Assert.Equal(grown.InfiniteHeight, appearance.InfiniteHeight);
    }

    [Fact]
    public void ContentOutsideLeftOrTopDoesNotExpandAndExportStillPreservesIt()
    {
        var document = new PageDocument
        {
            Objects = [new PageObject { X = -5000, Y = 3000, Width = 20, Height = 20 }, new PageObject { X = 3000, Y = -5000, Width = 20, Height = 20 }],
        };
        Assert.False(InfinitePageLayout.EnsureCapacity(new PageAppearance(), document));
        Assert.True(InfinitePageLayout.ExportBounds(document).Left < -5000);
        Assert.True(InfinitePageLayout.ExportBounds(document).Top < -5000);
    }

    [Fact]
    public void SmartCropTracksCurrentMixedContentInsteadOfAccumulatedPaper()
    {
        var document = new PageDocument
        {
            Objects = [new PageObject { X = 100, Y = 200, Width = 80, Height = 50 }],
            Tags = [new CanvasTag(CanvasTagKind.Important, 2000, 3000)],
        };
        var appearance = new PageAppearance();
        InfinitePageLayout.EnsureCapacity(appearance, document);
        var fullCrop = InfinitePageLayout.ExportBounds(document);
        document.Tags.Clear();
        var cropped = InfinitePageLayout.ExportBounds(document);
        Assert.Equal(80 + InfinitePageLayout.ExportPadding * 2, cropped.Width, 5);
        Assert.Equal(50 + InfinitePageLayout.ExportPadding * 2, cropped.Height, 5);
        Assert.True(cropped.Width < fullCrop.Width);
        Assert.True(appearance.InfiniteWidth > cropped.Width);
    }

    [Fact]
    public void RealCanvasNavigationDoesNotGrowAndExportIsIndependentOfViewport()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                _ = Application.Current ?? new Application();
                var canvas = new NoteCanvas { Document = new PageDocument("notebook", "page"), Appearance = new PageAppearance { Background = CanvasBackgroundFormat.Blank, ShowTitle = false } };
                var item = new PageObject { Kind = PageObjectKind.Table, X = 3000, Y = 3000, Width = 80, Height = 50 };
                canvas.AddObject(item);
                var width = canvas.Appearance.InfiniteWidth;
                var height = canvas.Appearance.InfiniteHeight;
                // Cropping across the origin must not export the viewport-scaled paper outline.
                item.X = 0;
                item.Y = 0;
                var initial = canvas.RenderLayerBitmap(CanvasRenderLayer.Composite);
                canvas.Viewport.Restore(new PageViewport { Scale = 4, TranslationX = -100000, TranslationY = -100000 });
                canvas.SetZoom(2);
                Assert.Equal(width, canvas.Appearance.InfiniteWidth);
                Assert.Equal(height, canvas.Appearance.InfiniteHeight);
                var moved = canvas.RenderLayerBitmap(CanvasRenderLayer.Composite);
                Assert.Equal(initial.PixelWidth, moved.PixelWidth);
                Assert.Equal(initial.PixelHeight, moved.PixelHeight);
                var originalPixels = new byte[initial.PixelWidth * initial.PixelHeight * 4];
                var movedPixels = new byte[originalPixels.Length];
                initial.CopyPixels(originalPixels, initial.PixelWidth * 4, 0);
                moved.CopyPixels(movedPixels, moved.PixelWidth * 4, 0);
                Assert.Equal(originalPixels, movedPixels);
                Assert.True(initial.PixelWidth < 200);
                canvas.RemoveObject(item);
                Assert.Equal(width, canvas.Appearance.InfiniteWidth);
                Assert.Equal(height, canvas.Appearance.InfiniteHeight);
                canvas.Viewport.Restore(new PageViewport { TranslationX = 80, TranslationY = 80 });
                canvas.SetZoom(1);
                canvas.Measure(new Size(500, 400));
                canvas.Arrange(new Rect(0, 0, 500, 400));
                canvas.UpdateLayout();
                var screen = new RenderTargetBitmap(500, 400, 96, 96, PixelFormats.Pbgra32);
                screen.Render(canvas);
                var pixels = new byte[500 * 400 * 4];
                screen.CopyPixels(pixels, 500 * 4, 0);
                Assert.Equal(10, pixels[(10 * 500 + 10) * 4]);
                Assert.Equal(27, pixels[(200 * 500 + 200) * 4]);
            }
            catch (Exception ex) { failure = ex; }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        Assert.Null(failure);
    }

    [Fact]
    public void PersistedAndPackagedExtentsKeepTheirPhysicalSize()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-infinite-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new WorkspaceStore(root);
            var library = store.LoadLibrary();
            var notebook = library.ActiveNotebook;
            var page = notebook.SelectedPage()!;
            page.Appearance.InfiniteWidth = InfinitePageLayout.InitialWidth + InfinitePageLayout.GrowthStep;
            page.Appearance.InfiniteHeight = InfinitePageLayout.InitialHeight + InfinitePageLayout.GrowthStep;
            store.SaveLibrary(library);
            Assert.Equal(page.Appearance.InfiniteWidth, store.LoadLibrary().ActiveNotebook.SelectedPage()!.Appearance.InfiniteWidth);
            var path = Path.Combine(root, "test.mjn");
            NotebookPackageService.Export(path, notebook, store, page.Id);
            using var archive = ZipFile.OpenRead(path);
            using var reader = new StreamReader(archive.GetEntry("manifest.json")!.Open());
            var settings = JsonNode.Parse(reader.ReadToEnd())!["sections"]![0]!["pages"]![0]!["viewSettings"]!;
            Assert.Equal(310d * 12.16225d, settings["boundlessDocumentWidth"]!.GetValue<double>(), 4);
            Assert.Equal(397d * 12.16225d, settings["boundlessDocumentHeight"]!.GetValue<double>(), 4);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
