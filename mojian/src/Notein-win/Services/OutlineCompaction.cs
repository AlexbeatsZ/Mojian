using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>
/// Rewrites oversized 0.7.21-0.7.33 Windows pen outlines in the compact shared form.
/// The stored outline itself is re-encoded (not regenerated from points), so moved/resized strokes
/// keep their vectorTransform. A stroke changes only when its outline is exactly the legacy disc/hull
/// form; only vectorPath/vectorBounds change, and every other field and stroke keeps its JSON.
/// </summary>
public static class OutlineCompaction
{
    public sealed record Result(int Candidates, int Compacted, int Unmatched, long OutlineCharactersBefore, long OutlineCharactersAfter, int PointsPacked = 0);

    /// <summary>
    /// Returns the rewritten page JSON, or null when nothing qualified. With <paramref name="packPoints"/>,
    /// plain point arrays become `pointsCoded` (shared/ink-points.md) in place; every client that reads
    /// it must already be deployed.
    /// </summary>
    public static string? CompactPage(string json, out Result result, bool packPoints = false)
    {
        var root = JsonNode.Parse(json, documentOptions: new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip })
            as JsonObject ?? throw new JsonException("页面数据无效。");
        int candidates = 0, compacted = 0, unmatched = 0, packed = 0;
        long before = 0, after = 0;
        if (root["strokes"] is JsonArray strokes)
        {
            foreach (var node in strokes)
            {
                if (packPoints && node is JsonObject owner && owner["points"] is JsonArray points && InkPointCodec.TryEncode(points) is { } coded)
                {
                    var index = owner.Select(pair => pair.Key).ToList().IndexOf("points");
                    owner.Remove("points");
                    owner.Insert(index, "pointsCoded", coded);
                    packed++;
                }
                if (node is not JsonObject item || !IsLegacyCandidate(item, out var path)) continue;
                candidates++;
                if (FountainV2Model.CompactLegacyOutline(path) is not { } compact) { unmatched++; continue; }
                // Same coordinate space as the stored outline; vectorTransform (move/resize) is unchanged.
                item["vectorPath"] = compact.Text;
                item["vectorBounds"] = compact.Bounds.IsEmpty ? null :
                    new JsonArray(compact.Bounds.Left, compact.Bounds.Top, compact.Bounds.Right, compact.Bounds.Bottom);
                before += path.Length;
                after += compact.Text.Length;
                compacted++;
            }
        }
        result = new(candidates, compacted, unmatched, before, after, packed);
        return compacted == 0 && packed == 0 ? null : root.ToJsonString();
    }

    private static bool IsLegacyCandidate(JsonObject item, out string path)
    {
        path = item["vectorPath"] is JsonValue value && value.TryGetValue<string>(out var text) ? text : string.Empty;
        var profile = item["brushProfile"] is JsonValue p && p.TryGetValue<string>(out var name) ? name : string.Empty;
        // The compact writer uses relative c/l; the legacy WPF serializer only emits absolute C/L.
        return profile is FountainV2Model.Profile or PenStyle.Profile && path.StartsWith("F1M", StringComparison.Ordinal) &&
            !path.Contains('c') && !path.Contains('l');
    }

    /// <summary>Compacts every page under <paramref name="root"/>/notebooks/*/pages; returns per-page results.</summary>
    public static IReadOnlyList<(string Path, Result Result, long BytesBefore, long BytesAfter)> CompactLibrary(string root, long minimumBytes, bool write,
        Func<string, bool>? include = null, bool packPoints = false)
    {
        var results = new List<(string, Result, long, long)>();
        var notebooks = Path.Combine(root, "notebooks");
        foreach (var file in Directory.EnumerateFiles(notebooks, "*.json", SearchOption.AllDirectories))
        {
            if (!string.Equals(Path.GetFileName(Path.GetDirectoryName(file)), "pages", StringComparison.Ordinal)) continue;
            if (include != null && !include(file)) continue;
            var length = new FileInfo(file).Length;
            if (length < minimumBytes) continue;
            var text = File.ReadAllText(file);
            var compacted = CompactPage(text, out var result, packPoints);
            if (compacted == null) { results.Add((file, result, length, length)); continue; }
            var bytes = System.Text.Encoding.UTF8.GetBytes(compacted);
            // AtomicWrite keeps the replaced page as `.previous`, the same recovery copy as page saves.
            if (write) WorkspaceStore.AtomicWrite(file, bytes);
            results.Add((file, result, length, bytes.Length));
        }
        return results;
    }
}
