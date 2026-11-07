using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Xunit.Abstractions;

namespace Notein_win.Tests;

public sealed class WindowsInkPerformanceTests(ITestOutputHelper output)
{
    private static int Builds(NoteCanvas canvas) => (int)typeof(NoteCanvas)
        .GetProperty("SceneRenderCount", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
    private static void Call(NoteCanvas canvas, string name, params object[] args) =>
        typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);

    private static byte[] Render(NoteCanvas canvas)
    {
        canvas.UpdateLayout();
        var image = new RenderTargetBitmap(800, 600, 96, 96, PixelFormats.Pbgra32);
        image.Render(canvas);
        var pixels = new byte[800 * 600 * 4];
        image.CopyPixels(pixels, 800 * 4, 0);
        return pixels;
    }

    private static NoteCanvas DenseCanvas(int strokeCount = 3000)
    {
        var document = new PageDocument("n", "p");
        for (var i = 0; i < strokeCount; i++)
        {
            var x = 20 + i % 60 * 12;
            var y = 20 + i / 60 * 10;
            document.Strokes.Add(new StrokeRecord
            {
                Id = "s" + i, WidthMm = .35f,
                Points = Enumerable.Range(0, 80).Select(p => new InkPoint(x + p * .1f, y + MathF.Sin(p * .1f), .5f)).ToList(),
            });
        }
        var canvas = new NoteCanvas
        {
            Width = 800, Height = 600, Document = document,
            SelectedTool = new ToolPreset("eraser", DrawingToolKind.Eraser) { EraserRadiusMm = 1 },
            Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank },
        };
        InfinitePageLayout.EnsureCapacity(canvas.Appearance, document);
        canvas.Measure(new Size(800, 600)); canvas.Arrange(new Rect(0, 0, 800, 600));
        Render(canvas);
        return canvas;
    }

    [Fact]
    public void DenseEraseDoesNotSerializePageOrRebuildSceneForEverySample() => WpfTestExecutor.Run(() =>
    {
        var canvas = DenseCanvas();
        var allocated = GC.GetAllocatedBytesForCurrentThread();
        var timer = Stopwatch.StartNew();
        Call(canvas, "BeginPointer", new Point(20, 20), .5f, 0f, 0f, true, false, false);
        timer.Stop();
        var beginAllocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
        output.WriteLine($"erase_begin_ms={timer.Elapsed.TotalMilliseconds:F3}, allocated={beginAllocated}");
        Render(canvas);
        var builds = Builds(canvas);
        var inputTimes = new List<double>();
        timer.Restart();
        for (var i = 1; i <= 60; i++)
        {
            var input = Stopwatch.StartNew();
            Call(canvas, "ContinuePointer", new Point(20 + i * 3, 20), .5f, 0f, 0f, true);
            inputTimes.Add(input.Elapsed.TotalMilliseconds);
            Render(canvas);
        }
        timer.Stop();
        output.WriteLine($"60_erase_samples_and_software_snapshots_ms={timer.Elapsed.TotalMilliseconds:F1}, scene_builds={builds}->{Builds(canvas)}");
        output.WriteLine($"erase_input_median_ms={inputTimes.Order().ElementAt(30):F3}, p95_ms={inputTimes.Order().ElementAt(56):F3}");
        Call(canvas, "EndPointer", true);
        Assert.True(beginAllocated < 2_000_000, $"Eraser contact allocated {beginAllocated:N0} bytes for 240,000 points.");
        Assert.Equal(builds, Builds(canvas));
        Assert.True(canvas.Document!.Strokes.Count < 3000);
        canvas.Undo(); Assert.Equal(3000, canvas.Document.Strokes.Count);
        canvas.Redo(); Assert.True(canvas.Document.Strokes.Count < 3000);
    });

    [Fact]
    public void EraseDeltaRestoresOrderVectorsTagsAndCanBeCancelled() => WpfTestExecutor.Run(() =>
    {
        var canvas = DenseCanvas(1);
        var document = canvas.Document!;
        document.Strokes = Enumerable.Range(0, 5).Select(i => new StrokeRecord
        {
            Id = "v" + i, VectorPath = $"M {20 + i * 80},20 h 20 v 10 h -20 Z", ColorHex = "#315579",
            VectorTransform = [1, 0, 0, 1, -5, 2], SourceBrush = JsonSerializer.SerializeToElement(new { owner = "preserve" }),
        }).ToList();
        document.Layers = [new PageLayer { Id = "hidden", Visible = false }];
        document.Strokes.Add(new StrokeRecord { Id = "hidden", LayerId = "hidden", Points = [new InkPoint(30, 25)] });
        document.Tags = [new CanvasTag(CanvasTagKind.Question, 105, 25), new CanvasTag(CanvasTagKind.Question, 345, 25)];
        var original = JsonSerializer.Serialize(document);
        var saves = 0; canvas.DocumentChanged += (_, _) => saves++;
        canvas.InvalidateVisual(); Render(canvas);
        Call(canvas, "BeginPointer", new Point(340, 25), .5f, 0f, 0f, true, false, false);
        Call(canvas, "ContinuePointer", new Point(100, 25), .5f, 0f, 0f, true);
        Assert.True(document.HasPendingContentChanges);
        Assert.Equal(new[] { "v0", "v2", "v3", "hidden" }, document.Strokes.Select(stroke => stroke.Id));
        Assert.Empty(document.Tags);
        Call(canvas, "EndPointer", true); Assert.Equal(1, saves);
        var erased = JsonSerializer.Serialize(document);
        canvas.Undo(); Assert.Equal(original, JsonSerializer.Serialize(document));
        canvas.Redo(); Assert.Equal(erased, JsonSerializer.Serialize(document));
        canvas.Undo();
        Call(canvas, "BeginPointer", new Point(20, 25), .5f, 0f, 0f, true, false, false);
        Call(canvas, "CancelStylusToolActionForNavigation");
        Assert.Equal(original, JsonSerializer.Serialize(document));
        canvas.Undo(); Assert.Equal(original, JsonSerializer.Serialize(document));
    });

    [Fact]
    public void PenContactAndLiftKeepPaperAndImagesRetainedAndAssetChangesRefresh() => WpfTestExecutor.Run(() =>
    {
        var directory = Path.Combine(Path.GetTempPath(), ".agents", "mojian-scene-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var path = Path.Combine(directory, "image.png");
            void Write(byte blue, byte red, int revision)
            {
                var bitmap = BitmapSource.Create(1, 1, 96, 96, PixelFormats.Bgra32, null, new byte[] { blue, 0, red, 255 }, 4);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using (var stream = File.Create(path)) encoder.Save(stream);
                File.SetLastWriteTimeUtc(path, DateTime.UtcNow.AddSeconds(revision));
            }
            Write(0, 255, 0);
            var canvas = DenseCanvas(1);
            canvas.ResolveAssetPath = _ => path;
            canvas.Document!.Objects.Add(new PageObject { Kind = PageObjectKind.Image, AssetName = "image.png", X = 100, Y = 100, Width = 200, Height = 200 });
            canvas.InvalidateVisual(); Render(canvas);
            var scene = (CanvasDocumentScene)canvas.FindName("SceneLayer");
            int Recordings() => (int)typeof(CanvasDocumentScene).GetProperty("PartRecordingCount", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(scene)!;
            var before = Recordings();
            canvas.SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen);
            Call(canvas, "BeginPointer", new Point(350, 400), .5f, 0f, 0f, true, false, false); Render(canvas);
            Assert.Equal(before, Recordings());
            Call(canvas, "ContinuePointer", new Point(450, 400), .5f, 0f, 0f, true);
            Call(canvas, "EndPointer", true); var red = Render(canvas);
            Assert.Equal(before + 1, Recordings());
            canvas.InvalidateVisual(); Render(canvas); Assert.Equal(before + 1, Recordings());
            Write(255, 0, 2);
            canvas.InvalidateVisual(); var blue = Render(canvas);
            Assert.Equal(before + 2, Recordings());
            var index = (200 * 800 + 200) * 4;
            Assert.True(red[index + 2] > 240 && red[index] < 10);
            Assert.True(blue[index] > 240 && blue[index + 2] < 10);
        }
        finally { Directory.Delete(directory, true); }
    });

    [Fact]
    public void EmptyEraseDoesNotSaveOrDiscardUndoRedoAndCancellationKeepsEarlierInk() => WpfTestExecutor.Run(() =>
    {
        var canvas = new NoteCanvas { Document = new PageDocument("n", "p"), SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) };
        Call(canvas, "BeginPointer", new Point(20, 20), .5f, 0f, 0f, true, false, false);
        Call(canvas, "EndPointer", true);
        canvas.Undo();
        canvas.Document!.HasPendingContentChanges = false;
        canvas.SelectedTool = new ToolPreset("eraser", DrawingToolKind.Eraser);
        var saves = 0; canvas.DocumentChanged += (_, _) => saves++;
        Call(canvas, "BeginPointer", new Point(200, 200), .5f, 0f, 0f, true, false, false);
        Call(canvas, "EndPointer", true);
        Assert.Equal(0, saves); Assert.False(canvas.Document.HasPendingContentChanges);
        canvas.Redo(); Assert.Single(canvas.Document.Strokes);
        Call(canvas, "BeginPointer", new Point(200, 200), .5f, 0f, 0f, true, false, false);
        Call(canvas, "CancelStylusToolActionForNavigation");
        Assert.Single(canvas.Document.Strokes);
        canvas.Undo(); Assert.Empty(canvas.Document.Strokes);
    });

    [Fact]
    public void LayeredWritingUpdatesOnlyWetVisualAndMatchesCanonicalPainter() => WpfTestExecutor.Run(() =>
    {
        var canvas = DenseCanvas();
        LiveInkCostProbe.SetSceneCache(canvas, false); // Offscreen RTB compares vector pixels only.
        var document = canvas.Document!;
        document.Layers = [new PageLayer { Id = "ink", Order = 0 }, new PageLayer { Id = "cover", Order = 1 }];
        document.Objects.Add(new PageObject { Kind = PageObjectKind.Image, LayerId = "cover", X = 390, Y = 540, Width = 100, Height = 40 });
        canvas.SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) { WidthMm = 2, Color = InkPalette.Red };
        Call(canvas, "BeginPointer", new Point(350, 560), .5f, 0f, 0f, true, false, false);
        document.Strokes[^1].LayerId = "ink";
        Render(canvas); var builds = Builds(canvas);
        for (var i = 1; i <= 30; i++)
        {
            Call(canvas, "ContinuePointer", new Point(350 + i * 4, 560), .5f, 0f, 0f, true);
            if (i % 10 == 0) Render(canvas);
        }
        Assert.Equal(builds, Builds(canvas));
        var wet = Render(canvas);
        var index = (560 * 800 + 430) * 4;
        Assert.Equal(wet[index], wet[index + 2]); // cover still covers the red wet stroke
        Call(canvas, "EndPointer", true);
        var dry = Render(canvas);
        var handoff = wet.Zip(dry, (a, b) => Math.Abs(a - b)).ToArray();
        var largest = Array.IndexOf(handoff, handoff.Max());
        Assert.True(handoff.Max() <= 2, $"handoff_diff={handoff.Max()}, x={largest / 4 % 800}, y={largest / 3200}, wet={wet[largest]}, dry={dry[largest]}");
        // Independently paint the canonical composite into one visual for pixel comparison.
        var reference = new DrawingVisual();
        using (var context = reference.RenderOpen())
            Call(canvas, "DrawDocument", context, CanvasRenderLayer.Composite, false, new Rect(0, 0, 800, 600));
        var bitmap = new RenderTargetBitmap(800, 600, 96, 96, PixelFormats.Pbgra32); bitmap.Render(reference);
        var expected = new byte[dry.Length]; bitmap.CopyPixels(expected, 3200, 0);
        Assert.True(expected.Zip(dry, (a, b) => Math.Abs(a - b)).Max() <= 2);
    });

    [Fact]
    public void LiveWindowDenseEraseFramesAndScreenshot() => WpfTestExecutor.Run(() =>
    {
        var canvas = DenseCanvas();
        var window = new Window { Title = "Mojian · Windows ink performance verification", Content = canvas, SizeToContent = SizeToContent.WidthAndHeight };
        window.Show(); window.UpdateLayout();
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        var intervals = new List<double>();
        var inputs = new List<double>();
        var clock = Stopwatch.StartNew();
        var previous = 0d; var count = 0; var builds = 0;
        EventHandler handler = (_, _) =>
        {
            var now = clock.Elapsed.TotalMilliseconds;
            if (count > 3) intervals.Add(now - previous);
            previous = now;
            if (count == 0) Call(canvas, "BeginPointer", new Point(20, 20), .5f, 0f, 0f, true, false, false);
            else if (count <= 90)
            {
                if (count == 1) builds = Builds(canvas);
                var input = Stopwatch.StartNew();
                Call(canvas, "ContinuePointer", new Point(20 + count * 4, 20), .5f, 0f, 0f, true);
                inputs.Add(input.Elapsed.TotalMilliseconds);
            }
            else { frame.Continue = false; return; }
            count++;
        };
        timer.Tick += (_, _) => frame.Continue = false;
        CompositionTarget.Rendering += handler; timer.Start();
        try { Dispatcher.PushFrame(frame); }
        finally { timer.Stop(); CompositionTarget.Rendering -= handler; }
        Assert.Equal(91, count);
        Assert.Equal(builds, Builds(canvas));
        output.WriteLine($"live_erase_90_frames: frame_median_ms={intervals.Order().ElementAt(intervals.Count / 2):F3}, frame_p95_ms={intervals.Order().ElementAt((int)(intervals.Count * .95)):F3}, input_p95_ms={inputs.Order().ElementAt(85):F3}, rendering_tier={RenderCapability.Tier >> 16}");
        Call(canvas, "EndPointer", true);
        if (Environment.GetEnvironmentVariable("MOJIAN_INK_EVIDENCE_DIRECTORY") is { Length: > 0 } directory)
        {
            Directory.CreateDirectory(directory);
            var bitmap = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(window);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var stream = File.Create(Path.Combine(directory, "windows-dense-erase.png")); encoder.Save(stream);
        }
        window.Close();
    });
}
