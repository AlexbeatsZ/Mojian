using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;
using Xunit.Abstractions;

namespace Notein_win.Tests;

public sealed class WritingReliabilityTests(ITestOutputHelper output)
{
    private static void Sta(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() => { try { action(); } catch (Exception ex) { failure = ex; } });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        Assert.Null(failure);
    }
    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-writing-" + Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }
    [Fact]
    public void SamePageInSplitViewCannotOverwriteNewInkWithStaleContent()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(temp.Path, new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var page = vm.PrimaryDocument!;
        vm.ToggleSplitViewCommand.Execute(null);
        Assert.Equal(page.PageId, vm.SecondaryDocument!.PageId);
        vm.SecondaryDocument.Strokes.Add(new StrokeRecord { Points = [new InkPoint(10, 20), new InkPoint(20, 30)] });
        vm.SaveDocument(vm.SecondaryDocument);
        vm.SaveAll();
        Assert.Single(vm.Store.LoadPage(page.NotebookId, page.PageId).Strokes);
    }

    [Fact]
    public void DamagedPageRecoversLastSuccessfulRevision()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var doc = new PageDocument("n", "p") { Strokes = [new StrokeRecord { Points = [new InkPoint(10, 20)] }] };
        store.SavePage(doc);
        doc.Strokes.Add(new StrokeRecord { Points = [new InkPoint(30, 40)] });
        store.SavePage(doc);
        File.WriteAllText(store.GetPageDocumentPath("n", "p"), "{damaged");
        Assert.Single(store.LoadPage("n", "p").Strokes);
    }

    [Fact]
    public void UnrecoverablePageAndLibraryAreNeverReplacedByEmptyModels()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var path = store.GetPageDocumentPath("n", "p");
        File.WriteAllText(path, "{damaged");
        for (var i = 0; i < 2; i++) Assert.Throws<InvalidDataException>(() => store.LoadPage("n", "p"));
        Assert.Equal("{damaged", File.ReadAllText(path));
        File.WriteAllText(store.LibraryPath, "null");
        File.WriteAllText(store.LibraryPath + ".previous", "null");
        Assert.Throws<InvalidDataException>(() => store.LoadLibrary());
        Assert.Equal("null", File.ReadAllText(store.LibraryPath));
    }

    [Fact]
    public void ReadLockIsReportedWithoutMovingOrReplacingThePage()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var doc = new PageDocument("n", "p") { Strokes = [new StrokeRecord()] };
        store.SavePage(doc);
        var path = store.GetPageDocumentPath("n", "p");
        using (File.Open(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.Throws<IOException>(() => store.LoadPage("n", "p"));
        Assert.Single(store.LoadPage("n", "p").Strokes);
        Assert.Empty(Directory.GetFiles(Path.GetDirectoryName(path)!, "*.corrupt-*"));
    }

    [Fact]
    public void BackgroundSaveUsesFrozenCopiesAndFlushRetriesFailedPages()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var queue = new PageSaveQueue();
        var doc = new PageDocument("n", "p") { Strokes = [new StrokeRecord { Points = [new InkPoint(10, 20)] }] };
        store.SavePage(doc);
        var snapshot = doc.CopyForSave();
        doc.Strokes[0].Points[0].X = 100;
        doc.Strokes.Clear();
        using (File.Open(store.GetPageDocumentPath("n", "p"), FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            queue.Enqueue(store, snapshot);
            Assert.Throws<IOException>(queue.Flush);
        }
        queue.Flush();
        Assert.Equal(10f, Assert.Single(store.LoadPage("n", "p").Strokes).Points[0].X);
        Assert.Empty(Directory.GetFiles(Path.GetDirectoryName(store.GetPageDocumentPath("n", "p"))!, "*.tmp"));
    }

    [Fact]
    public void BackgroundSaveCoalescesPagesWithoutRevertingLibraryMetadata()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var queue = new PageSaveQueue();
        var library = NotebookLibrary.Sample();
        for (var i = 0; i < 80; i++)
        {
            var doc = new PageDocument("n", i % 2 == 0 ? "left" : "right") { Revision = i };
            doc.Strokes.Add(new StrokeRecord { Points = [new InkPoint(i, 20)] });
            library.ActiveNotebook.Title = "revision-" + i;
            queue.Enqueue(store, doc.CopyForSave(), library.CopyForSave());
        }
        queue.Flush();
        Assert.Equal(78, store.LoadPage("n", "left").Revision);
        Assert.Equal(79, store.LoadPage("n", "right").Revision);
        Assert.Equal("revision-79", store.LoadLibrary().ActiveNotebook.Title);
    }

    [Fact]
    public void RealWindowCloseFlushesInkQueuedByTheCanvas() => Sta(() =>
    {
        if (Application.Current == null)
        {
            _ = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        }
        foreach (var resource in new[] { "DarkColors", "IconResources", "ControlStyles" })
            Application.Current!.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{resource}.xaml", UriKind.Relative)));
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(temp.Path, new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var window = new Notein_win.MainWindow(vm);
        try
        {
            window.Show(); window.UpdateLayout();
            var canvas = (NoteCanvas)window.FindName("LeftCanvas");
            canvas.Document!.Strokes.Add(new StrokeRecord { Points = [new InkPoint(20, 30), new InkPoint(40, 50)] });
            typeof(NoteCanvas).GetMethod("RaiseDocumentChanged", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, null);
            window.Close();
            Assert.Single(vm.Store.LoadPage(vm.PrimaryDocument!.NotebookId, vm.PrimaryDocument.PageId).Strokes);
        }
        finally { if (window.IsVisible) window.Close(); }
    });

    [Fact]
    public void PointerOnlyRefreshMovesNibDotWithoutRenderingTheDocument() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var stroke = new StrokeRecord { Points = [new InkPoint(20, 30), new InkPoint(40, 30)] };
        var canvas = new NoteCanvas { Width = 400, Height = 300, Document = new PageDocument("n", "p") { Strokes = [stroke] },
            SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen),
            Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
        void Field(string name, object value) => typeof(NoteCanvas).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(canvas, value);
        var overlay = (CanvasPointerOverlay)canvas.FindName("PointerOverlay");
        Field("_stylusInRange", true); Field("_hoverViewPoint", new Point(100, 100));
        canvas.Measure(new Size(400, 300)); canvas.Arrange(new Rect(0, 0, 400, 300)); canvas.UpdateLayout();
        byte[] Render()
        {
            var bitmap = new RenderTargetBitmap(400, 300, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(canvas);
            var pixels = new byte[400 * 300 * 4]; bitmap.CopyPixels(pixels, 1600, 0); return pixels;
        }
        Render();
        var cache = (StrokeDrawingCache)typeof(NoteCanvas).GetField("_strokeDrawings", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
        var builds = cache.BuildCount;
        Assert.Equal(1, builds);
        // Changing the model without invalidating the document lets this test
        // detect any unintended document redraw caused by the pointer layer.
        stroke.Points[1].Y = 250; stroke.InvalidateBounds();
        Field("_hoverViewPoint", new Point(200, 120)); overlay.InvalidateVisual(); canvas.UpdateLayout();
        var pixels = Render();
        Assert.Equal(builds, cache.BuildCount);
        Assert.True(pixels[(120 * 400 + 200) * 4] > 180);
        Assert.True(pixels[(100 * 400 + 100) * 4] < 80);
    });

    [Fact]
    public void InkAppendHistorySupportsUndoRedoAndPressureEraserRollback() => Sta(() =>
    {
        var canvas = new NoteCanvas { Document = new PageDocument("n", "p"), SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) };
        void Call(string method, params object[] values) => typeof(NoteCanvas).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, values);
        void Begin(Point point, float pressure) => Call("BeginPointer", point, pressure, 0f, 0f, true, false, false);
        Begin(new Point(10, 10), .2f); Call("ContinuePointer", new Point(20, 10), .2f, 0f, 0f, true); Call("EndPointer", true);
        canvas.Undo(); Assert.Empty(canvas.Document.Strokes);
        canvas.Redo(); Assert.Single(canvas.Document.Strokes);
        canvas.ResolvePressureEraserTool = () => new ToolPreset("eraser", DrawingToolKind.Eraser) { PressureEraseEnabled = true, PressureEraseThresholdPercent = 80 };
        Begin(new Point(15, 10), .95f);
        typeof(NoteCanvas).GetField("_pressureStartedAt", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(canvas, DateTime.UtcNow.AddSeconds(-1));
        Call("CheckPressureEraseActivation"); Call("EndPointer", true);
        Assert.Empty(canvas.Document.Strokes);
        canvas.Undo(); Assert.Single(canvas.Document.Strokes);
        // History trimming retains the most recent edits, including the final stroke.
        canvas.ResolvePressureEraserTool = null;
        for (var i = 0; i < 85; i++) { Begin(new Point(100 + i * 2, 100), .2f); Call("EndPointer", true); }
        var lastId = canvas.Document.Strokes[^1].Id;
        canvas.Undo(); Assert.DoesNotContain(canvas.Document.Strokes, stroke => stroke.Id == lastId);
    });

    [Fact]
    public void StrokeDrawingCacheRebuildsOnlyForChangedStrokeOrStyle() => Sta(() =>
    {
        var cache = new StrokeDrawingCache();
        var stroke = new StrokeRecord { Points = [new InkPoint(1, 2), new InkPoint(3, 4)] };
        DrawingGroup Get(bool dark = true) => cache.Get(stroke, dark, false, dc => dc.DrawLine(new Pen(Brushes.Black, 1), stroke.Points[0].ToPoint(), stroke.Points[1].ToPoint()));
        var original = Get(); Assert.Same(original, Get()); Assert.Equal(1, cache.BuildCount);
        stroke.Points[1].X = 50; stroke.InvalidateBounds(); Assert.NotSame(original, Get());
        stroke.Color = InkPalette.Red; Get();
        Get(false); Assert.Equal(4, cache.BuildCount);
    });

    [Fact]
    public void CachedDrawingPreservesOriginalPenHighlighterAndShapePixels() => Sta(() =>
    {
        var stroke = new StrokeRecord { WidthMm = 2, Points = [new InkPoint(20, 30, .3f), new InkPoint(60, 50, .8f), new InkPoint(100, 30, .4f)] };
        var canvas = new NoteCanvas { Document = new PageDocument("n", "p") { Strokes = [stroke] } };
        byte[] Render(bool cached, bool export)
        {
            var visual = new DrawingVisual();
            using (var dc = visual.RenderOpen())
            {
                if (cached) typeof(NoteCanvas).GetMethod("DrawStrokes", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, [dc, export, new Rect(0, 0, 128, 128), null]);
                else typeof(NoteCanvas).GetMethod("DrawStroke", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, [dc, stroke, export]);
            }
            var bitmap = new RenderTargetBitmap(128, 128, 96, 96, PixelFormats.Pbgra32); bitmap.Render(visual);
            var pixels = new byte[128 * 128 * 4]; bitmap.CopyPixels(pixels, 512, 0); return pixels;
        }
        foreach (var profile in new[] { "fountain-v1", "shape-v1", "highlighter" })
        {
            stroke.BrushProfile = profile;
            stroke.Kind = profile == "highlighter" ? DrawingToolKind.Highlighter : DrawingToolKind.Pen;
            stroke.OpacityPercent = profile == "highlighter" ? 40 : 100;
            Assert.Equal(Render(false, false), Render(true, false));
            Assert.Equal(Render(false, true), Render(true, true));
        }
    });

    [Fact]
    public void SuppliedWritingPageMeasuresRealCanvasAndSaveCosts()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_WRITING_PAGE_JSON");
        if (string.IsNullOrEmpty(path)) return;
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                _ = Application.Current ?? new Application();
                var doc = JsonSerializer.Deserialize<PageDocument>(File.ReadAllText(path), new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
                var canvas = new NoteCanvas { Width = 1400, Height = 1000, Document = doc,
                    Appearance = new PageAppearance { ShowTitle = false, Theme = PageTheme.Dark } };
                var draw = typeof(NoteCanvas).GetMethod("DrawDocument", BindingFlags.Instance | BindingFlags.NonPublic)!;
                double Render()
                {
                    var watch = Stopwatch.StartNew();
                    var visual = new DrawingVisual();
                    using (var context = visual.RenderOpen()) draw.Invoke(canvas, [context, CanvasRenderLayer.Composite, false, new Rect(0, 0, 2000, 3000)]);
                    return watch.Elapsed.TotalMilliseconds;
                }
                var first = Render();
                var frames = Enumerable.Range(0, 10).Select(_ => Render()).ToArray();
                var watch = Stopwatch.StartNew();
                for (var i = 0; i < 10; i++) JsonSerializer.Serialize(doc);
                var snapshot = watch.Elapsed.TotalMilliseconds / 10;
                watch.Restart();
                for (var i = 0; i < 10; i++) doc.CopyForSave();
                var copy = watch.Elapsed.TotalMilliseconds / 10;
                using var temp = new TemporaryDirectory();
                var store = new WorkspaceStore(temp.Path);
                watch.Restart();
                for (var i = 0; i < 5; i++) store.SavePage(doc);
                output.WriteLine($"strokes={doc.Strokes.Count}, points={doc.Strokes.Sum(s => s.Points.Count)}, first_draw_ms={first:F2}, repeat_draw_ms={frames.Average():F2}, snapshot_ms={snapshot:F2}, copy_ms={copy:F2}, save_ms={watch.Elapsed.TotalMilliseconds / 5:F2}");
            }
            catch (Exception ex) { failure = ex; }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        Assert.Null(failure);
    }
}
