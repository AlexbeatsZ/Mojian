using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>VS Code source-control letters: what taking the server's version does here (native-sync.md "Conflict review").</summary>
public enum ConflictMark { Modify, Add, Delete }

/// <summary>One library change between the two sides of a library.json conflict.</summary>
public sealed record LibraryChange(LibraryChangeSide Side, string Label);
public enum LibraryChangeSide { Local, Remote, Changed }

/// <summary>Strokes the canvas preview marks: only on the server (green), only here (red), this device's version of strokes both changed (yellow).</summary>
public sealed record StrokeDiff(IReadOnlyList<StrokeRecord> Remote, IReadOnlyList<StrokeRecord> Local, IReadOnlyList<StrokeRecord> Changed)
{
    public static readonly StrokeDiff Empty = new([], [], []);
}

/// <summary>Pure review rules shared in meaning with Android's SyncConflictReview.kt.</summary>
public static class SyncConflictReview
{
    public const string Library = "library.json";
    private const long PreviewLimit = 64L * 1024 * 1024;

    public static ConflictMark MarkOf(string action) => action switch { "add" => ConflictMark.Add, "delete" => ConflictMark.Delete, _ => ConflictMark.Modify };
    public static string Letter(ConflictMark mark) => mark switch { ConflictMark.Add => "A", ConflictMark.Delete => "D", _ => "M" };
    public static string ColorHex(ConflictMark mark) => mark switch { ConflictMark.Add => "#73C991", ConflictMark.Delete => "#F14C4C", _ => "#E2C08D" };

    /// <summary><c>notebooks/&lt;nb&gt;/pages/&lt;page&gt;.json</c> and <c>notebooks/&lt;nb&gt;/assets/&lt;page&gt;/...</c> belong to a page.</summary>
    public static (string NotebookId, string PageId)? PageOf(string path)
    {
        var parts = path.Split('/');
        if (parts.Length < 4 || parts[0] != "notebooks") return null;
        if (parts.Length == 4 && parts[2] == "pages" && parts[3].EndsWith(".json", StringComparison.Ordinal)) return (parts[1], parts[3][..^5]);
        if (parts.Length >= 5 && parts[2] == "assets") return (parts[1], parts[3]);
        return null;
    }

    public static bool IsPageFile(string path) => PageOf(path) != null && path.Contains("/pages/", StringComparison.Ordinal);

    public static string Label(string path, Func<string, string, string?> pageTitle)
    {
        if (path == Library) return "笔记目录";
        if (IsPageFile(path) && PageOf(path) is { } page)
            return pageTitle(page.NotebookId, page.PageId) is { Length: > 0 } title ? title : page.PageId;
        return path[(path.LastIndexOf('/') + 1)..];
    }

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
        try { return JsonNode.Parse(File.ReadAllBytes(file)) as JsonObject; }
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

    /// <summary>Pages/sections only on one side or changed on both, labeled by their title trail.</summary>
    public static List<LibraryChange> LibraryChanges(JsonObject local, JsonObject remote)
    {
        var changes = new List<LibraryChange>();
        static string Title(JsonObject item) => item["title"] is JsonValue value && value.TryGetValue<string>(out var text) && text.Length > 0 ? text : Id(item) ?? string.Empty;
        void Add(LibraryChangeSide side, IEnumerable<string> trail, JsonObject item) => changes.Add(new(side, string.Join(" / ", trail.Append(Title(item)))));
        void Walk(JsonObject left, JsonObject right, List<string> trail)
        {
            foreach (var key in left.Select(p => p.Key).Concat(right.Select(p => p.Key)).Distinct())
            {
                if (left[key] is not JsonArray a || right[key] is not JsonArray b) continue;
                if (!(a.Count == 0 || Identified(a)) || !(b.Count == 0 || Identified(b)) || a.Count + b.Count == 0) continue;
                var ours = ById(a); var theirs = ById(b);
                foreach (var (id, item) in ours)
                {
                    if (!theirs.TryGetValue(id, out var other)) { Add(LibraryChangeSide.Local, trail, item); continue; }
                    if (Same(item, other)) continue;
                    if (HasIdentifiedChildren(item) || HasIdentifiedChildren(other))
                    {
                        if (!SameIgnoringArrays(item, other)) Add(LibraryChangeSide.Changed, trail, item);
                        Walk(item, other, [.. trail, Title(item)]);
                    }
                    else Add(LibraryChangeSide.Changed, trail, item);
                }
                foreach (var (id, item) in theirs) if (!ours.ContainsKey(id)) Add(LibraryChangeSide.Remote, trail, item);
            }
        }
        Walk(local, remote, []);
        return changes;
    }

    private static string? Id(JsonObject item) => item["id"] is JsonValue value && value.TryGetValue<string>(out var id) && id.Length > 0 ? id : null;
    private static bool Identified(JsonArray array) => array.Count > 0 && array.All(item => item is JsonObject o && Id(o) != null);
    private static bool HasIdentifiedChildren(JsonObject item) => item.Any(pair => pair.Value is JsonArray array && Identified(array));
    private static bool SameIgnoringArrays(JsonObject left, JsonObject right) =>
        left.Select(p => p.Key).Concat(right.Select(p => p.Key)).Distinct()
            .Where(key => left[key] is not JsonArray && right[key] is not JsonArray).All(key => Same(left[key], right[key]));

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
