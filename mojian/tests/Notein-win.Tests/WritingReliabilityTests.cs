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
    public void HandPanMovesCachedSceneWithoutPerSampleRedrawAndMatchesVectorPixels() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        NoteCanvas Create()
        {
            var created = new NoteCanvas { Width = 400, Height = 300, Document = new PageDocument("n", "p"), SingleFingerPanEnabled = true,
                Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.GridStandard } };
            for (var row = 0; row < 12; row++)
                created.Document.Strokes.Add(new StrokeRecord { Points = [new InkPoint(10, 20 + row * 40), new InkPoint(700, 40 + row * 40)] });
            created.Measure(new Size(400, 300)); created.Arrange(new Rect(0, 0, 400, 300)); created.UpdateLayout();
            return created;
        }
        byte[] Render(NoteCanvas target)
        {
            target.UpdateLayout();
            var bitmap = new RenderTargetBitmap(400, 300, 96, 96, PixelFormats.Pbgra32); bitmap.Render(target);
            var pixels = new byte[400 * 300 * 4]; bitmap.CopyPixels(pixels, 1600, 0); return pixels;
        }
        void AssertSameAsDirect(NoteCanvas panned, string stage, bool cached = false)
        {
            var reference = Create();
            reference.Viewport.Restore(new PageViewport { Scale = panned.Viewport.Scale, TranslationX = panned.Viewport.TranslationX, TranslationY = panned.Viewport.TranslationY });
            reference.InvalidateVisual();
            var a1 = Render(panned); var b1 = Render(reference);
            var diffs = a1.Zip(b1, (a, b) => Math.Abs(a - b)).ToArray();
            var max = diffs.Max();
            var mean = diffs.Average();
            // The moving bitmap differs only in edge anti-aliasing (mean ~1 per channel); a
            // misplaced scene shifted by one or two pixels measures several times higher.
            var shifted = a1.Skip(1600).Zip(b1, (a, b) => Math.Abs(a - b)).Average();
            output.WriteLine($"{stage}: max={max} mean={mean:F3} one_row_shift_mean={shifted:F3}");
            if (cached) Assert.True(mean < 1.5 && shifted > 3 * mean, $"{stage}: mean={mean:F3} one_row_shift_mean={shifted:F3}");
            else Assert.True(max <= 2, $"{stage}: max_pixel_diff={max}");
        }
        var canvas = Create();
        void Call(string method, params object[] args) => typeof(NoteCanvas).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        int Builds() => (int)typeof(NoteCanvas).GetProperty("SceneRenderCount", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
        bool Cached() => (bool)typeof(NoteCanvas).GetField("_panCacheActive", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
        Render(canvas);
        Call("BeginPointer", new Point(300, 200), .5f, 0f, 0f, true, false, false);
        Call("ContinuePointer", new Point(299, 199), .5f, 0f, 0f, true);
        Render(canvas);
        Assert.True(Cached());
        var builds = Builds();
        for (var i = 1; i <= 40; i++)
        {
            // 3 px per sample: 120 px travel stays within the 200 px horizontal overscan.
            Call("ContinuePointer", new Point(299 - i * 3, 199 - i), .5f, 0f, 0f, true);
            Render(canvas);
        }
        Assert.Equal(builds, Builds());
        AssertSameAsDirect(canvas, "mid-pan", cached: true);
        for (var i = 1; i <= 40; i++) { Call("ContinuePointer", new Point(179 - i * 3, 159), .5f, 0f, 0f, true); Render(canvas); }
        Assert.Equal(builds + 1, Builds());
        AssertSameAsDirect(canvas, "after-recenter", cached: true);
        Call("EndPointer", true);
        Assert.False(Cached());
        AssertSameAsDirect(canvas, "after-release");
    });

    [Fact]
    public void ZoomedOutWetInkReusesCompletedSceneAndPreservesHandoffPixels() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var canvas = new NoteCanvas { Width = 400, Height = 300, Document = new PageDocument("n", "p"),
            SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) { WidthMm = 2 },
            Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank } };
        canvas.Document.Strokes.Add(new StrokeRecord { Points = [new InkPoint(10, 20), new InkPoint(100, 20)] });
        canvas.SetZoom(.5f);
        canvas.Measure(new Size(400, 300)); canvas.Arrange(new Rect(0, 0, 400, 300)); canvas.UpdateLayout();
        void Call(string method, params object[] args) => typeof(NoteCanvas).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        int Builds() => (int)typeof(NoteCanvas).GetProperty("SceneRenderCount", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
        byte[] Render()
        {
            canvas.UpdateLayout();
            var bitmap = new RenderTargetBitmap(400, 300, 96, 96, PixelFormats.Pbgra32); bitmap.Render(canvas);
            var pixels = new byte[400 * 300 * 4]; bitmap.CopyPixels(pixels, 1600, 0); return pixels;
        }
        Render();
        Call("BeginPointer", new Point(200, 100), .5f, 0f, 0f, true, false, false);
        Render(); var builds = Builds();
        for (var i = 1; i <= 30; i++)
        {
            Call("ContinuePointer", new Point(200 + i * 2, 100), .5f, 0f, 0f, true);
            var pixels = Render();
            Assert.Equal(builds, Builds());
            Assert.True(pixels[(100 * 400 + 220) * 4] > 80 || i < 12);
        }
        var wet = Render();
        Call("EndPointer", true); var completed = Render();
        Assert.True(Builds() > builds);
        Assert.True(completed[(100 * 400 + 220) * 4] > 40, $"completed_pixel={completed[(100 * 400 + 220) * 4]}");
        var differences = wet.Zip(completed, (a, b) => Math.Abs(a - b)).ToArray();
        var largest = Array.IndexOf(differences, differences.Max());
        Assert.True(differences.Max() <= 2, $"handoff_diff={differences.Max()}, index={largest}, wet={wet[largest]}, dry={completed[largest]}");
        canvas.Undo(); var undone = Render();
        Assert.True(undone[(100 * 400 + 220) * 4] < 80);
        canvas.SetZoom(64); Render(); Assert.Equal(64f, canvas.Viewport.Scale);
    });

    [Fact]
    public void FountainOutlineMatchesPressureSizedRoundSegmentsEvenWhenRetraced() => Sta(() =>
    {
        var stroke = new StrokeRecord { WidthMm = 2, Points = [new InkPoint(20, 30, .3f), new InkPoint(60, 50, .8f),
            new InkPoint(100, 30, .4f), new InkPoint(60, 50, .7f), new InkPoint(60, 50, .7f)] };
        var outline = FountainStrokeGeometry.Create(stroke);
        Assert.True(outline.IsFrozen);
        var geometry = outline.GetFlattenedPathGeometry(.001, ToleranceType.Absolute);
        for (var y = 20d; y < 60; y += .73)
        for (var x = 10d; x < 110; x += .73)
        {
            var point = new Point(x, y);
            var distanceFromEdge = double.PositiveInfinity;
            for (var i = 1; i < stroke.Points.Count; i++)
            {
                var start = HandwritingEngine.SmoothedPoint(stroke.Points, i - 1);
                var end = HandwritingEngine.SmoothedPoint(stroke.Points, i);
                var direction = end - start;
                var t = direction.LengthSquared < 1e-12 ? 0 : Math.Clamp(Vector.Multiply(point - start, direction) / direction.LengthSquared, 0, 1);
                distanceFromEdge = Math.Min(distanceFromEdge, (point - (start + direction * t)).Length - HandwritingEngine.SegmentWidth(stroke, i) / 2);
            }
            if (Math.Abs(distanceFromEdge) < .02) continue;
            Assert.True((distanceFromEdge < 0) == geometry.FillContains(point, .001, ToleranceType.Absolute), $"point={point}, edge={distanceFromEdge}");
        }
    });

    [Fact]
    public void WetInkBelowSourceCoverKeepsItsLayerOcclusion() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var document = new PageDocument("n", "p")
        {
            Layers = [new PageLayer { Id = "ink", Order = 0 }, new PageLayer { Id = "cover", Order = 1 }],
            Objects = [new PageObject { Kind = PageObjectKind.Image, LayerId = "cover", X = 10, Y = 10, Width = 100, Height = 80 }],
        };
        var canvas = new NoteCanvas { Width = 200, Height = 150, Document = document,
            SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) { WidthMm = 2, Color = InkPalette.Red },
            Appearance = new PageAppearance { Theme = PageTheme.Light, Background = CanvasBackgroundFormat.Blank, ShowTitle = false } };
        canvas.Measure(new Size(200, 150)); canvas.Arrange(new Rect(0, 0, 200, 150));
        void Call(string method, params object[] args) => typeof(NoteCanvas).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        Call("BeginPointer", new Point(30, 60), .5f, 0f, 0f, true, false, false);
        document.Strokes[^1].LayerId = "ink";
        Call("ContinuePointer", new Point(90, 60), .5f, 0f, 0f, true);
        canvas.UpdateLayout();
        var bitmap = new RenderTargetBitmap(200, 150, 96, 96, PixelFormats.Pbgra32); bitmap.Render(canvas);
        var pixels = new byte[200 * 150 * 4]; bitmap.CopyPixels(pixels, 800, 0);
        var index = (60 * 200 + 70) * 4;
        Assert.Equal(pixels[index], pixels[index + 2]);
        Assert.Equal(pixels[index + 1], pixels[index + 2]);
        Assert.True(pixels[index] > 20);
        Call("EndPointer", true);
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
                canvas.Measure(new Size(1400, 1000)); canvas.Arrange(new Rect(0, 0, 1400, 1000));
                foreach (var zoom in new[] { .5f, 1f, 2f, 4f })
                {
                    canvas.SetZoom(zoom);
                    canvas.UpdateLayout();
                    double Raster()
                    {
                        var timer = Stopwatch.StartNew();
                        var bitmap = new RenderTargetBitmap(1400, 1000, 96, 96, PixelFormats.Pbgra32);
                        bitmap.Render(canvas);
                        return timer.Elapsed.TotalMilliseconds;
                    }
                    Raster();
                    // Keep a reference to the previous per-segment renderer, so
                    // the same software raster path compares both implementations.
                    var reference = new DrawingVisual();
                    using (var dc = reference.RenderOpen())
                    {
                        dc.PushClip(new RectangleGeometry(new Rect(0, 0, 1400, 1000)));
                        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(10, 10, 10)), null, new Rect(0, 0, 1400, 1000));
                        dc.PushTransform(new MatrixTransform(canvas.Viewport.DocumentToViewMatrix()));
                        var bounds = new Rect(canvas.Viewport.ViewToDocument(0, 0), canvas.Viewport.ViewToDocument(1400, 1000));
                        typeof(NoteCanvas).GetMethod("DrawPaper", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, [dc, false, bounds]);
                        foreach (var stroke in doc.Strokes.Where(stroke => StrokeViewportCulling.IsVisible(stroke, bounds)))
                        {
                            if (stroke.BrushProfile == "shape-v1" || stroke.Kind == DrawingToolKind.Highlighter || !string.IsNullOrEmpty(stroke.VectorPath))
                            {
                                typeof(NoteCanvas).GetMethod("DrawStroke", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, [dc, stroke, false]);
                                continue;
                            }
                            var brush = (Brush)typeof(NoteCanvas).GetMethod("TextBrush", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [stroke.Color, true, false])!;
                            for (var i = 1; i < stroke.Points.Count; i++)
                                dc.DrawLine(new Pen(brush, HandwritingEngine.SegmentWidth(stroke, i)) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round },
                                    HandwritingEngine.SmoothedPoint(stroke.Points, i - 1), HandwritingEngine.SmoothedPoint(stroke.Points, i));
                        }
                        dc.Pop(); dc.Pop();
                    }
                    double ReferenceRaster()
                    {
                        var timer = Stopwatch.StartNew();
                        var bitmap = new RenderTargetBitmap(1400, 1000, 96, 96, PixelFormats.Pbgra32); bitmap.Render(reference);
                        return timer.Elapsed.TotalMilliseconds;
                    }
                    ReferenceRaster();
                    var builds = typeof(NoteCanvas).GetProperty("SceneRenderCount", BindingFlags.Instance | BindingFlags.NonPublic)!;
                    var before = builds.GetValue(canvas);
                    output.WriteLine($"zoom={zoom:F2}, previous_raster_ms={Enumerable.Range(0, 5).Select(_ => ReferenceRaster()).Average():F2}, raster_ms={Enumerable.Range(0, 5).Select(_ => Raster()).Average():F2}, scene_builds={before}->{builds.GetValue(canvas)}");
                    var imageDirectory = Environment.GetEnvironmentVariable("MOJIAN_WRITING_RENDER_DIRECTORY");
                    if (!string.IsNullOrEmpty(imageDirectory) && zoom == 1)
                    {
                        Directory.CreateDirectory(imageDirectory);
                        foreach (var (visual, name) in new[] { ((Visual)canvas, "current.png"), ((Visual)reference, "previous.png") })
                        {
                            var bitmap = new RenderTargetBitmap(1400, 1000, 96, 96, PixelFormats.Pbgra32); bitmap.Render(visual);
                            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                            using var file = File.Create(Path.Combine(imageDirectory, name)); encoder.Save(file);
                        }
                    }
                }
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
