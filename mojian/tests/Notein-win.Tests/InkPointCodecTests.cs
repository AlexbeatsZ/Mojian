using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public sealed class InkPointCodecTests
{
    private static JsonArray Cases() =>
        JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "points-coded.json")))!["cases"]!.AsArray();

    [Fact]
    public void GoldenVectorsEncodeExactlyAndDecodeWithinHalfAQuantum()
    {
        foreach (var item in Cases())
        {
            var name = (string)item!["name"]!;
            var points = item["points"]!.AsArray();
            var coded = item["coded"] is JsonValue value ? (string)value! : null;
            Assert.True(coded == InkPointCodec.TryEncode(points), name);
            if (coded == null) continue;
            var rows = InkPointCodec.DecodeRows(coded);
            Assert.Equal(points.Count, rows.Length);
            for (var i = 0; i < rows.Length; i++)
                for (var c = 0; c < InkPointCodec.Channels.Length; c++)
                {
                    var (key, scale) = InkPointCodec.Channels[c];
                    var expected = points[i]![key];
                    Assert.Equal(expected == null, rows[i][c] == null);
                    if (expected != null) Assert.True(Math.Abs((double)expected - rows[i][c]!.Value) <= .5 / scale + 1e-12, $"{name}[{i}].{key}");
                }
        }
    }

    [Fact]
    public void WindowsPointsMatchTheSharedEncodingAndRejectExtensions()
    {
        var windows = Cases().First(item => (string)item!["name"]! == "windows")!;
        var points = windows["points"]!.AsArray().Select(p => new InkPoint
        {
            X = (float)p!["x"]!, Y = (float)p["y"]!, Pressure = (float)p["pressure"]!, TiltX = (float)p["tiltX"]!,
            TiltY = (float)p["tiltY"]!, ElapsedMilliseconds = (long)p["elapsedMilliseconds"]!,
        }).ToList();
        // Golden vectors are JSON doubles; InkPoint holds float32, so compare with the same float values
        // through the generic JSON path (a deliberate rounding tie in the fixture differs as float).
        var widened = new JsonArray(points.Select(p => (JsonNode?)new JsonObject
        {
            ["x"] = (double)p.X, ["y"] = (double)p.Y, ["pressure"] = (double)p.Pressure,
            ["elapsedMilliseconds"] = p.ElapsedMilliseconds, ["tiltX"] = (double)p.TiltX, ["tiltY"] = (double)p.TiltY,
        }).ToArray());
        Assert.Equal(InkPointCodec.TryEncode(widened), InkPointCodec.TryEncode(points));
        var decoded = InkPointCodec.Decode(InkPointCodec.TryEncode(points)!);
        Assert.Equal(InkPointCodec.TryEncode(points), InkPointCodec.TryEncode(decoded)); // idempotent
        Assert.Equal(1234567, decoded[2].ElapsedMilliseconds);
        Assert.Null(decoded[0].TiltRadians);
        points[1].AdditionalData = new() { ["futureSample"] = JsonDocument.Parse("1").RootElement };
        Assert.Null(InkPointCodec.TryEncode(points));
        Assert.Throws<JsonException>(() => InkPointCodec.Decode("1:AQ=="));
        Assert.Throws<JsonException>(() => InkPointCodec.Decode("2:AA=="));
    }

    [Fact]
    public void PageStoreWritesPackedPointsOnlyForChangedPlainStrokes()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-points-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(root);
            FolderLibraryFixture.CopyTo(root);
            var page = Path.Combine(root, "共享样例", "样例分区", "黄金页面.mojian");
            var store = new WorkspaceStore(root);
            var document = store.LoadPage("book.dot", "page.dot");
            var legacy = JsonNode.Parse(File.ReadAllText(page))!["content"]!["strokes"]![0]!.ToJsonString();
            document.Strokes.Add(new StrokeRecord
            {
                Id = "stroke-new", BrushProfile = FountainV2Model.Profile,
                Points = Enumerable.Range(0, 50).Select(i => new InkPoint(i * 1.37f, 20 + i * .21f, .3f + i / 100f, elapsedMilliseconds: i * 4)).ToList(),
            });
            store.SavePage(document);
            var saved = JsonNode.Parse(File.ReadAllText(page))!["content"]!["strokes"]!.AsArray();
            // The legacy stroke has a per-point extension field: verbatim array.
            Assert.Equal(legacy, saved[0]!.ToJsonString());
            Assert.Null(saved[1]!["points"]);
            Assert.StartsWith(InkPointCodec.Prefix, (string)saved[1]!["pointsCoded"]!);
            var bytes = File.ReadAllBytes(page);
            var reloaded = new WorkspaceStore(root).LoadPage("book.dot", "page.dot");
            var stroke = reloaded.Strokes.Single(s => s.Id == "stroke-new");
            Assert.Equal(50, stroke.Points.Count);
            Assert.Equal(49 * 1.37f, stroke.Points[^1].X, .0006);
            Assert.Equal(196, stroke.Points[^1].ElapsedMilliseconds);
            Assert.DoesNotContain(stroke.AdditionalData ?? [], pair => pair.Key == "pointsCoded");
            reloaded.TextItems.Add(new TextItem { Text = "edit elsewhere" });
            new WorkspaceStore(root).SavePage(reloaded);
            Assert.Equal((string)JsonNode.Parse(bytes)!["content"]!["strokes"]![1]!["pointsCoded"]!,
                (string)JsonNode.Parse(File.ReadAllText(page))!["content"]!["strokes"]![1]!["pointsCoded"]!);
            // Clipboard/history/.mjn serializers keep plain arrays for older readers.
            var plain = JsonSerializer.SerializeToNode(stroke, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase })!;
            Assert.NotNull(plain["points"]);
            Assert.Null(plain["pointsCoded"]);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Fact]
    public void LibraryCompactionPacksPlainArraysInPlace() => WpfTestExecutor.Run(() =>
    {
        var page = new JsonObject
        {
            ["version"] = 2, ["pageId"] = "p",
            ["strokes"] = new JsonArray(
                new JsonObject { ["id"] = "a", ["points"] = new JsonArray(new JsonObject { ["x"] = 1.5, ["y"] = 2, ["pressure"] = .5 }), ["after"] = 1 },
                new JsonObject { ["id"] = "b", ["points"] = new JsonArray(new JsonObject { ["x"] = 1, ["y"] = 2, ["futureSample"] = true }) }),
        };
        Assert.Null(OutlineCompaction.CompactPage(page.ToJsonString(), out _));
        var output = JsonNode.Parse(OutlineCompaction.CompactPage(page.ToJsonString(), out var result, packPoints: true)!)!;
        Assert.Equal(1, result.PointsPacked);
        Assert.Equal(["id", "pointsCoded", "after"], output["strokes"]![0]!.AsObject().Select(p => p.Key).ToArray());
        Assert.Equal(page["strokes"]![1]!.ToJsonString(), output["strokes"]![1]!.ToJsonString());
        Assert.Null(OutlineCompaction.CompactPage(output.ToJsonString(), out _, packPoints: true));
    });
}
