using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public sealed class OutlineCompactionTests
{
    private static readonly JsonSerializerOptions Camel = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    // Handwriting-like: curls, a sharp turn and changing pressure at 240 Hz.
    private static StrokeRecord Handwriting(string profile = FountainV2Model.Profile) => new()
    {
        Id = "stroke-hand", BrushProfile = profile, WidthMm = .6f,PressurePercent = 200, InkFlowPercent = 100,
        StabilityPercent = 50, StrokeUnitLengthCm = .02,
        PenStyle = profile == PenStyle.Profile ? new PenStyle() : null,
        Points = Enumerable.Range(0, 160).Select(i =>
        {
            var t = i / 159d;
            var x = 20 + t * 140 + 12 * Math.Sin(t * 19);
            var y = 50 + 18 * Math.Sin(t * 7) + (i > 90 ? (i - 90) * .6 : 0);
            return new InkPoint((float)x, (float)y, (float)(.2 + .75 * Math.Abs(Math.Sin(t * 5))), elapsedMilliseconds: i * 4);
        }).ToList(),
    };

    private static byte[] Alpha(Geometry geometry, double scale, int width, int height)
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.PushTransform(new ScaleTransform(scale, scale));
            dc.DrawGeometry(Brushes.Black, null, geometry);
        }
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var pixels = new byte[width * height * 4];
        bitmap.CopyPixels(pixels, width * 4, 0);
        return Enumerable.Range(0, width * height).Select(i => pixels[i * 4 + 3]).ToArray();
    }

    [Theory]
    [InlineData(FountainV2Model.Profile)]
    [InlineData(PenStyle.Profile)]
    public void CompactOutlineKeepsTheLegacyShapeAtHighZoom(string profile) => WpfTestExecutor.Run(() =>
    {
        var stroke = Handwriting(profile);
        var legacy = FountainV2Model.LegacyOutlineText(stroke);
        var compact = FountainV2Model.OutlineText(stroke);
        // This synthetic curl is denser in turns than real writing (a real 358 MB page shrinks ~10x).
        Assert.True(compact.Length * 4 < legacy.Length, $"compact {compact.Length} vs legacy {legacy.Length}");
        // 8x zoom: 0.03 DIP tolerance is a quarter pixel; only anti-aliased edge pixels may move.
        var before = Alpha(Geometry.Parse(legacy), 8, 1400, 900);
        var after = Alpha(Geometry.Parse(compact), 8, 1400, 900);
        var ink = before.Count(value => value > 0);
        var changed = before.Zip(after).Count(pair => Math.Abs(pair.First - pair.Second) > 64);
        var largest = before.Zip(after).Max(pair => Math.Abs(pair.First - pair.Second));
        Assert.True(ink > 10000);
        Assert.True(changed * 500 < ink, $"{changed} of {ink} ink pixels changed by more than 25%");
        Assert.True(largest < 160, $"largest alpha change {largest}");
    });

    [Fact]
    public void CompactOutlineUsesOnlySharedReaderSyntax() => WpfTestExecutor.Run(() =>
    {
        var text = FountainV2Model.OutlineText(Handwriting());
        // Android PathParser, iOS/macOS InkPath.parse and WPF all accept M/c/l/z with relative points.
        Assert.Matches(new Regex(@"^F1(M-?[0-9.]+,-?[0-9.]+[cl][-0-9., ]+z)+$"), text);
        Assert.DoesNotContain("E", text);
        Assert.DoesNotContain("..", text);
        Assert.False(Geometry.Parse(text).Bounds.IsEmpty);
    });

    [Fact]
    public void StoredOutlineIsTheRenderedGeometry() => WpfTestExecutor.Run(() =>
    {
        var stroke = Handwriting(PenStyle.Profile);
        var rendered = FountainV2Model.CreateGeometry(stroke);
        FountainV2Model.StoreOutline(stroke);
        // Wet ink (CreateGeometry) and the saved outline must not jump at pen-up, even at 8x.
        Assert.Equal(Alpha(rendered, 8, 1400, 900), Alpha(stroke.VectorGeometry!, 8, 1400, 900));
        Assert.Equal(rendered.Bounds.Left, stroke.VectorBounds![0], 9);
    });

    [Fact]
    public void SimplificationKeepsEndpointsAndDropsCollinearSamples()
    {
        var samples = Enumerable.Range(0, 50).Select(i => new FountainV2Model.Sample(new Point(i, 10), 1, i, 1)).ToList();
        Assert.Equal([0, 49], FountainV2Model.OutlineSampleIndexes(samples));
        samples[25] = samples[25] with { Position = new Point(25, 10.5) };
        Assert.Contains(25, FountainV2Model.OutlineSampleIndexes(samples));
    }

    [Fact]
    public void LibraryCompactionChangesOnlyVerifiedLegacyOutlines() => WpfTestExecutor.Run(() =>
    {
        JsonObject Node(StrokeRecord stroke, string path, double[] transform)
        {
            var node = JsonSerializer.SerializeToNode(stroke, Camel)!.AsObject();
            node["vectorPath"] = path;
            node["vectorTransform"] = new JsonArray(transform.Select(v => (JsonNode?)v).ToArray());
            return node;
        }
        var legacy = Handwriting(PenStyle.Profile);
        var moved = Handwriting(PenStyle.Profile); moved.Id = "stroke-moved";
        var other = Handwriting(); other.Id = "stroke-other";
        var android = new StrokeRecord { Id = "stroke-android", BrushProfile = FountainV2Model.Profile, Points = [new InkPoint(1, 2, .5f)] };
        var legacyText = FountainV2Model.LegacyOutlineText(legacy);
        var legacyNode = Node(legacy, legacyText, [1, 0, 0, 1, 0, 0]);
        legacyNode["futureField"] = new JsonObject { ["kept"] = true };
        // One disc control point no longer matches its circle: not the legacy form, keep it as stored.
        var tampered = Node(other, Regex.Replace(legacyText, @"C(-?[0-9.]+)", match => "C" + (double.Parse(match.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) + .001).ToString(System.Globalization.CultureInfo.InvariantCulture), RegexOptions.None, TimeSpan.FromSeconds(5)), [1, 0, 0, 1, 0, 0]);
        var page = new JsonObject
        {
            ["version"] = 2, ["pageId"] = "page-a", ["textItems"] = new JsonArray("墨笺"),
            ["strokes"] = new JsonArray(
                legacyNode,
                Node(moved, legacyText, [2, 0, 0, 2, 5, 5]),
                tampered,
                Node(android, "F1 M1,2 L3,4 L5,1 Z", [1, 0, 0, 1, 0, 0])),
        };
        var source = page.ToJsonString();
        var compacted = OutlineCompaction.CompactPage(source, out var result);
        Assert.NotNull(compacted);
        Assert.Equal((3, 2, 1), (result.Candidates, result.Compacted, result.Unmatched));
        var output = JsonNode.Parse(compacted!)!.AsObject();
        var strokes = output["strokes"]!.AsArray();
        // The stored outline is re-encoded in its own space: same text as a fresh compact outline.
        Assert.Equal(FountainV2Model.OutlineText(legacy), (string)strokes[0]!["vectorPath"]!);
        Assert.True((bool)strokes[0]!["futureField"]!["kept"]!);
        Assert.Equal((string)strokes[0]!["vectorPath"]!, (string)strokes[1]!["vectorPath"]!);
        Assert.Equal("[2,0,0,2,5,5]", strokes[1]!["vectorTransform"]!.ToJsonString());
        Assert.Equal(strokes[0]!["vectorBounds"]!.ToJsonString(), strokes[1]!["vectorBounds"]!.ToJsonString());
        for (var i = 2; i < 4; i++) Assert.Equal(page["strokes"]![i]!.ToJsonString(), strokes[i]!.ToJsonString());
        Assert.Equal("墨笺", (string)output["textItems"]![0]!);
        Assert.Null(OutlineCompaction.CompactPage(compacted!, out _));
        // Pixels at 8x of the moved stroke match the legacy outline under the same transform.
        var movedStroke = JsonSerializer.Deserialize<StrokeRecord>(strokes[1]!.ToJsonString(), Camel)!;
        var legacyMoved = Geometry.Parse(legacyText).Clone();
        legacyMoved.Transform = new MatrixTransform(2, 0, 0, 2, 5, 5);
        var a = Alpha(legacyMoved, 4, 1400, 900);
        var b = Alpha(movedStroke.VectorGeometry!, 4, 1400, 900);
        Assert.True(a.Zip(b).Count(pair => Math.Abs(pair.First - pair.Second) > 64) * 500 < a.Count(value => value > 0));
    });
}
