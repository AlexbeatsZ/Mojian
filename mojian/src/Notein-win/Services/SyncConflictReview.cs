using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>VS Code source-control letters: what taking the server's version does here (native-sync.md "Conflict review").</summary>
public enum ConflictMark { Modify, Add, Delete }

/// <summary>One library change between the two sides of a library.json conflict.</summary>

/// <summary>Strokes the canvas preview marks: only on the server (green), only here (red), this device's version of strokes both changed (yellow).</summary>
public sealed record StrokeDiff(IReadOnlyList<StrokeRecord> Remote, IReadOnlyList<StrokeRecord> Local, IReadOnlyList<StrokeRecord> Changed)
{
    public static readonly StrokeDiff Empty = new([], [], []);
}

/// <summary>Pure review rules shared in meaning with Android's SyncConflictReview.kt.</summary>
public static class SyncConflictReview
{
    private const long PreviewLimit = 64L * 1024 * 1024;

    public static ConflictMark MarkOf(string action) => action switch { "add" => ConflictMark.Add, "delete" => ConflictMark.Delete, _ => ConflictMark.Modify };
    public static string Letter(ConflictMark mark) => mark switch { ConflictMark.Add => "A", ConflictMark.Delete => "D", _ => "M" };
    public static string ColorHex(ConflictMark mark) => mark switch { ConflictMark.Add => "#73C991", ConflictMark.Delete => "#F14C4C", _ => "#E2C08D" };

    /// <summary>
    /// The page a conflicted item belongs to: <c>.mojian/assets/&lt;page&gt;/…</c> by path, a <c>.mojian</c> page by the id in
    /// either preserved copy's head (page files are named by title, not id).
    /// </summary>
    public static string? PageIdOf(NativeSyncConflictItem item)
    {
        var parts = item.Path.Split('/');
        if (parts.Length == 4 && parts[0] == ".mojian" && parts[1] == "assets") return parts[2];
        if (!IsPageFile(item.Path)) return null;
        foreach (var file in new[] { item.LocalFile, item.RemoteFile })
        {
            if (file == null || !File.Exists(file)) continue;
            try { if (PageFileFormat.ReadHead(file).Id is { } id) return id; }
            catch (Exception error) when (error is InvalidDataException or JsonException or IOException) { }
        }
        return null;
    }

    public static bool IsPageFile(string path) => path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal);

    /// <summary>A page is labelled by its file name, which is its title; other items by their name.</summary>
    public static string Label(string path) => IsPageFile(path)
        ? System.IO.Path.GetFileNameWithoutExtension(path)
        : path[(path.LastIndexOf('/') + 1)..];

    /// <summary>"本机 +1 · 服务器 +1 · 改动 1"; zero counts are omitted.</summary>
    public static string SummaryText(int? localOnly, int? remoteOnly, int? changed) => string.Join(" · ", new[]
    {
        localOnly > 0 ? $"本机 +{localOnly}" : null,
        remoteOnly > 0 ? $"服务器 +{remoteOnly}" : null,
        changed > 0 ? $"改动 {changed}" : null,
    }.OfType<string>());

    public static JsonObject? ReadObject(string? file)
    {
        if (file == null || !File.Exists(file) || new FileInfo(file).Length > PreviewLimit) return null;
        // A folder-library page keeps strokes in "content"; previews compare that part.
        try { return JsonNode.Parse(File.ReadAllBytes(file)) is JsonObject root ? root["content"] as JsonObject ?? root : null; }
        catch (JsonException) { return null; }
    }

    public static StrokeDiff StrokeDiff(JsonObject? local, JsonObject? remote, JsonSerializerOptions strokeOptions)
    {
        var ours = ById(local?["strokes"] as JsonArray);
        var theirs = ById(remote?["strokes"] as JsonArray);
        List<StrokeRecord> Decode(IEnumerable<JsonObject> items) => items.Select(item =>
        {
            try { return item.Deserialize<StrokeRecord>(strokeOptions); }
            catch (Exception error) when (error is JsonException or FormatException or InvalidOperationException or ArgumentException) { return null; }
        }).OfType<StrokeRecord>().ToList();
        return new(
            Decode(theirs.Where(pair => !ours.ContainsKey(pair.Key)).Select(pair => pair.Value)),
            Decode(ours.Where(pair => !theirs.ContainsKey(pair.Key)).Select(pair => pair.Value)),
            Decode(ours.Where(pair => theirs.TryGetValue(pair.Key, out var other) && !Same(other, pair.Value)).Select(pair => pair.Value)));
    }

    private static string? Id(JsonObject item) => item["id"] is JsonValue value && value.TryGetValue<string>(out var id) && id.Length > 0 ? id : null;
    private static bool Identified(JsonArray array) => array.Count > 0 && array.All(item => item is JsonObject o && Id(o) != null);

    private static Dictionary<string, JsonObject> ById(JsonArray? array)
    {
        var result = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        if (array == null) return result;
        foreach (var node in array) if (node is JsonObject item && Id(item) is { } id) result[id] = item;
        return result;
    }

    /// <summary>Structural equality; numbers compare by value so 1 and 1.0 written by different clients match.</summary>
    public static bool Same(JsonNode? left, JsonNode? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left == null || right == null) return left == null && right == null;
        if (left is JsonObject lo && right is JsonObject ro)
            return lo.Count == ro.Count && lo.All(pair => ro.ContainsKey(pair.Key) && Same(pair.Value, ro[pair.Key]));
        if (left is JsonArray la && right is JsonArray ra)
            return la.Count == ra.Count && la.Select((item, index) => Same(item, ra[index])).All(same => same);
        if (left is JsonValue lv && right is JsonValue rv)
        {
            if (lv.GetValueKind() == JsonValueKind.Number && rv.GetValueKind() == JsonValueKind.Number)
                return lv.GetValue<double>() == rv.GetValue<double>();
            return JsonNode.DeepEquals(lv, rv);
        }
        return false;
    }
}
