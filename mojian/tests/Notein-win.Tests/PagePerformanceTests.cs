using System.IO;
using System.Threading;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public sealed class PagePerformanceTests
{
    [Fact]
    public void StrokeBounds_AreInvalidatedAfterPointMutation()
    {
        var stroke = new StrokeRecord { Points = [new InkPoint(10, 20), new InkPoint(20, 30)] };
        var original = stroke.Bounds;

        stroke.Points[1].X = 200;
        Assert.Equal(original, stroke.Bounds);
        stroke.InvalidateBounds();

        Assert.True(stroke.Bounds.Right > 190);
        Assert.True(StrokeViewportCulling.IsVisible(stroke, new Rect(180, 0, 40, 80)));
        Assert.False(StrokeViewportCulling.IsVisible(stroke, new Rect(300, 0, 40, 80)));
    }

    [Fact]
    public void ViewportChanges_DoNotScheduleWholePageAutosave()
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() =>
        {
            try
            {
                _ = Application.Current ?? new Application();
                var canvas = new NoteCanvas
                {
                    Width = 900,
                    Height = 720,
                    Document = new PageDocument("notebook", "page"),
                    Appearance = new PageAppearance { PaperKind = PaperKind.Infinite },
                };
                var documentChanges = 0;
                var viewportChanges = 0;
                canvas.DocumentChanged += (_, _) => documentChanges++;
                canvas.ViewportChanged += (_, _) => viewportChanges++;

                canvas.SetZoom(1.25f);

                Assert.Equal(0, documentChanges);
                Assert.Equal(1, viewportChanges);
                Assert.Equal(1.25f, canvas.Document.Viewport.Scale, 3);
            }
            catch (Exception ex)
            {
                failure = ex;
            }
        });
        Assert.Null(failure);
    }

    [Fact]
    public void WorkspaceStore_WritesLargePageDocumentsWithoutIndentation()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-compact-page-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new WorkspaceStore(root);
            var document = new PageDocument("notebook", "page");
            document.Strokes.Add(new StrokeRecord { Points = [new InkPoint(1, 2), new InkPoint(3, 4)] });

            store.SavePage(document);

            var json = File.ReadAllText(store.GetPageDocumentPath(document.NotebookId, document.PageId));
            Assert.DoesNotContain("\r\n", json);
            Assert.DoesNotContain("\n  ", json);
            Assert.Single(store.LoadPage(document.NotebookId, document.PageId).Strokes);
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        }
    }
}
