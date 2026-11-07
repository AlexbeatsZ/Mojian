using System.Diagnostics;
using System.Reflection;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Xunit.Abstractions;

namespace Notein_win.Tests;

public sealed class SceneCacheAndEraseHitTests(ITestOutputHelper output)
{
    private static object? Call(NoteCanvas canvas, string name, params object[] args) =>
        typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
    private static bool SeparateWetInk(NoteCanvas canvas) => (bool)typeof(NoteCanvas)
        .GetProperty("SeparateWetInk", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
    private static int Builds(NoteCanvas canvas) => (int)typeof(NoteCanvas)
        .GetProperty("SceneRenderCount", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;

    private static void Render(NoteCanvas canvas)
    {
        canvas.UpdateLayout();
        new RenderTargetBitmap(400, 300, 96, 96, PixelFormats.Pbgra32).Render(canvas);
    }

    private static NoteCanvas Canvas(PageDocument document)
    {
        var canvas = new NoteCanvas
        {
            Width = 400, Height = 300, Document = document, SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen),
            Appearance = new PageAppearance { ShowTitle = false, Background = CanvasBackgroundFormat.Blank },
        };
        canvas.Measure(new Size(400, 300)); canvas.Arrange(new Rect(0, 0, 400, 300));
        Render(canvas);
        return canvas;
    }

    private static StrokeRecord Ink(string id, string layer, float y) => new()
    {
        Id = id, LayerId = layer, WidthMm = .4f,
        Points = Enumerable.Range(0, 30).Select(i => new InkPoint(20 + i * 8, y + MathF.Sin(i * .5f) * 6, .5f)).ToList(),
    };

    [Fact]
    public void DrySceneStaysBitmapCachedWithStableClipAndPanRestoresTheCache() => WpfTestExecutor.Run(() =>
    {
        var document = new PageDocument("n", "p");
        document.Strokes.Add(Ink("a", "", 60));
        var canvas = Canvas(document);
        var scene = (CanvasDocumentScene)canvas.FindName("SceneLayer");
        var cache = Assert.IsType<BitmapCache>(scene.CacheMode);
        Assert.Equal(1, cache.RenderAtScale);
        var root = (ContainerVisual)typeof(CanvasDocumentScene).GetField("_root", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(scene)!;
        var clip = root.Clip;
        Assert.NotNull(clip);
        // A replaced clip would dirty the cached layer and re-rasterize every stroke.
        canvas.InvalidateVisual(); Render(canvas);
        Assert.Same(clip, root.Clip);
        Call(canvas, "BeginPanCache");
        Assert.NotSame(cache, scene.CacheMode);
        Call(canvas, "EndPanCache");
        Assert.Same(cache, scene.CacheMode);
    });

    [Fact]
    public void TopmostInkOnLayeredPageUsesWetOverlayAndCoveredInkKeepsItsDepth() => WpfTestExecutor.Run(() =>
    {
        var document = new PageDocument("n", "p");
        document.Layers.Add(new PageLayer { Id = "source", Order = 0 });
        for (var i = 0; i < 20; i++) document.Strokes.Add(Ink("s" + i, "source", 20 + i * 12));
        var canvas = Canvas(document);
        Call(canvas, "BeginPointer", new Point(30, 150), .5f, 0f, 0f, true, false, false);
        Assert.True(SeparateWetInk(canvas));
        Render(canvas);
        var builds = Builds(canvas);
        for (var i = 1; i <= 20; i++) Call(canvas, "ContinuePointer", new Point(30 + i * 10, 150), .5f, 0f, 0f, true);
        Render(canvas);
        Assert.Equal(builds, Builds(canvas));
        Call(canvas, "EndPointer", true); Render(canvas);
        Assert.Equal(21, document.Strokes.Count);

        // Unlayered content later in painter order covers new ink: keep the canonical scene path.
        document.Objects.Add(new PageObject { Kind = PageObjectKind.Image, X = 0, Y = 0, Width = 50, Height = 50, ZOrder = 5 });
        Call(canvas, "BeginPointer", new Point(30, 200), .5f, 0f, 0f, true, false, false);
        Assert.False(SeparateWetInk(canvas));
        Call(canvas, "EndPointer", true);
    });

    [Fact]
    public void ModeledEraserHitMatchesStoredOutlineWithoutWpfIntersection() => WpfTestExecutor.Run(() =>
    {
        const double radius = 17;
        var checks = 0; var mismatches = 0;
        double exactMs = 0, quickMs = 0;
        for (var s = 0; s < 6; s++)
        {
            var stroke = new StrokeRecord
            {
                BrushProfile = FountainV2Model.Profile, WidthMm = .6f + s * .4f, PressurePercent = 200,
                Points = Enumerable.Range(0, 160).Select(i => new InkPoint(40 + i * 1.5f, 80 + MathF.Sin(i * (.08f + s * .03f)) * 30,
                    .2f + .7f * MathF.Abs(MathF.Sin(i * .05f)), elapsedMilliseconds: i * 4)).ToList(),
            };
            FountainV2Model.StoreOutline(stroke);
            Assert.True(StrokeEraserHitTest.UsesModeledOutline(stroke));
            var geometry = stroke.VectorGeometry!;
            bool Exact(Point center, double r) => geometry.FillContainsWithDetail(new EllipseGeometry(center, r, r)) != IntersectionDetail.Empty;
            var bounds = stroke.Bounds;
            for (var x = bounds.Left - radius * 1.3; x <= bounds.Right + radius * 1.3; x += radius / 2)
            for (var y = bounds.Top - radius * 1.3; y <= bounds.Bottom + radius * 1.3; y += radius / 4)
            {
                var center = new Point(x, y);
                var watch = Stopwatch.StartNew();
                var exact = Exact(center, radius);
                exactMs += watch.Elapsed.TotalMilliseconds; watch.Restart();
                var quick = StrokeEraserHitTest.ModeledOutlineHitsCircle(stroke, center, radius);
                quickMs += watch.Elapsed.TotalMilliseconds;
                checks++;
                if (exact == quick) continue;
                mismatches++;
                // Only flattening tolerance at the exact boundary may differ.
                if (quick) Assert.True(Exact(center, radius * 1.03), $"over-hit at {center}");
                else Assert.False(Exact(center, radius * .97), $"missed at {center}");
            }
        }
        output.WriteLine($"checks={checks} mismatches={mismatches} exact_ms={exactMs:F1} modeled_ms={quickMs:F1}");
        Assert.True(quickMs * 20 < exactMs, $"modeled {quickMs:F1} ms vs exact {exactMs:F1} ms");
    });
}
