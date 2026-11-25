using System.IO;
using System.Reflection;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class DocumentHistoryTests
{
    private static object? Call(NoteCanvas canvas, string method, params object[] args) => typeof(NoteCanvas)
        .GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);

    private static NoteCanvas Canvas(PageDocument document)
    {
        var canvas = new NoteCanvas { Width = 500, Height = 400, Document = document,
            SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen),
            Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
        canvas.Measure(new Size(500, 400)); canvas.Arrange(new Rect(0, 0, 500, 400));
        return canvas;
    }

    [Fact]
    public void EraseAndMixedContentMoveUndoInOrderWithoutChangingViewport() => WpfTestExecutor.Run(() =>
    {
        var document = new PageDocument("book", "page");
        document.Strokes.Add(new StrokeRecord { Id = "erased", Points = [new(30, 30), new(60, 30)] });
        document.Strokes.Add(new StrokeRecord { Id = "moved", Points = [new(100, 100), new(240, 200)] });
        document.TextItems.Add(new TextItem { Id = "text", Text = "move me", X = 100, Y = 110, Width = 140, Height = 80 });
        var canvas = Canvas(document);
        Call(canvas, "BeginPointer", new Point(40, 30), .5f, 0f, 0f, true, false, true);
        Call(canvas, "EndPointer", true);
        Assert.Equal("moved", Assert.Single(document.Strokes).Id);
        canvas.SelectAllContent();
        Call(canvas, "BeginDirectPointer", new Point(160, 150), false);
        Call(canvas, "ContinueDirectPointer", new Point(200, 180));
        Call(canvas, "EndDirectPointer", new Point(200, 180));
        Assert.Equal(140, document.Strokes[0].Points[0].X);
        Assert.Equal(140, document.TextItems[0].X);
        canvas.SetZoom(2); canvas.Viewport.Pan(80, 90);
        var viewport = canvas.Viewport.DocumentToViewMatrix();
        canvas.Undo();
        Assert.Equal(100, Assert.Single(document.Strokes).Points[0].X);
        Assert.Equal(100, document.TextItems[0].X);
        canvas.Undo();
        Assert.Equal(new[] { "erased", "moved" }, document.Strokes.Select(stroke => stroke.Id));
        canvas.Redo(); canvas.Redo();
        Assert.Equal(140, Assert.Single(document.Strokes).Points[0].X);
        Assert.Equal(140, document.TextItems[0].X);
        Assert.Equal(viewport, canvas.Viewport.DocumentToViewMatrix());
    });

    [Fact]
    public void PageAppearanceSharesHistoryWithClearAndPersistsRestoration() => WpfTestExecutor.Run(() =>
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-history-" + Guid.NewGuid().ToString("N"));
        try
        {
            var model = new MainViewModel(Path.Combine(root, "library"), new PreferencesStore(Path.Combine(root, "preferences.json")));
            var document = model.PrimaryDocument!;
            document.Strokes.Add(new StrokeRecord { Id = "before-clear", Points = [new(20, 20), new(30, 30)] });
            var canvas = Canvas(document);
            model.RequestRecordDocumentEdit += canvas.RecordReversibleEdit;
            var original = model.ActivePageAppearance.Background;
            var target = original == CanvasBackgroundFormat.Blank ? CanvasBackgroundFormat.GridStandard : CanvasBackgroundFormat.Blank;
            model.SetBackgroundFormat(target);
            canvas.ClearPage();
            canvas.Undo();
            Assert.Equal(target, model.ActivePageAppearance.Background);
            canvas.Undo();
            Assert.Equal(original, model.ActivePageAppearance.Background);
            canvas.Redo();
            Assert.Equal(target, model.ActivePageAppearance.Background);
            // Selecting the existing value must not discard the redo of ClearPage.
            model.SetBackgroundFormat(target);
            canvas.Redo();
            Assert.Empty(document.Strokes);
            model.SaveAll();
            var reloaded = new WorkspaceStore(Path.Combine(root, "library")).LoadLibrary();
            Assert.Equal(target, reloaded.ActiveNotebook.SelectedPage()!.Appearance.Background);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    });

    [Fact]
    public void FineZoomStepsReverseAndRespectLimits()
    {
        var viewport = new ViewportState();
        Assert.Equal(1.05f, viewport.SteppedScale(1), 5);
        viewport.SetScale(1.37f, 300, 180);
        var anchor = viewport.ViewToDocument(300, 180);
        viewport.SetScale(viewport.SteppedScale(1), 300, 180);
        viewport.SetScale(viewport.SteppedScale(-1), 300, 180);
        Assert.Equal(1.37f, viewport.Scale, 5);
        Assert.Equal(anchor.X, viewport.ViewToDocument(300, 180).X, 3);
        viewport.SetScale(64, 0, 0); Assert.Equal(64, viewport.SteppedScale(1));
        viewport.SetScale(.5f, 0, 0); Assert.Equal(.5f, viewport.SteppedScale(-1));
    }
}
