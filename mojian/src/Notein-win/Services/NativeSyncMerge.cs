using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Notein_win.Services;

/// <summary>
/// Three-way JSON merge for workspace and page files; Android's NativeSyncMerge.kt implements the same
/// rules against shared/fixtures/json-merge.json. Arrays whose items all carry a unique string id merge
/// per item, objects per key. A null result means both sides changed the same value differently.
/// </summary>
internal static class NativeSyncMerge
{
    internal abstract record Result
    {
        /// <summary>The merge equals the remote file apart from volatile fields: just receive it.</summary>
        internal sealed record Remote : Result;
        internal sealed record Merged(JsonObject Document) : Result;
    }

    private sealed class Conflict : Exception;
    private static readonly JsonNode Absent = JsonValue.Create("\u0000absent")!;
    private static readonly HashSet<string> MaximumKeys = ["revision", "sheetCount", "infiniteWidth", "infiniteHeight"];
    private static readonly HashSet<string> LatestKeys = ["updatedAt"];
    private static readonly HashSet<string> VolatileKeys = ["revision", "updatedAt"];
    private const string Erasable = "strokes";
    internal static readonly JsonSerializerOptions Output = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public static Result? Merge(JsonObject @base, JsonObject local, JsonObject remote, string? now = null)
    {
        JsonObject merged;
        try { merged = MergeObject(@base, local, remote); }
        catch (Conflict) { return null; }
        if (SameIgnoringVolatile(merged, remote)) return new Result.Remote();
        if (merged.ContainsKey("revision")) merged["revision"] = (merged["revision"]?.GetValue<long>() ?? 0) + 1;
        if (merged.ContainsKey("updatedAt")) merged["updatedAt"] = now ?? DateTimeOffset.UtcNow.ToString("O");
        return new Result.Merged(merged);
    }

    private static bool SameIgnoringVolatile(JsonObject left, JsonObject right) =>
        left.Select(pair => pair.Key).Union(right.Select(pair => pair.Key)).Except(VolatileKeys)
            .All(key => Equal(Value(left, key), Value(right, key)));

    private static JsonNode? Value(JsonObject source, string key) => source.TryGetPropertyValue(key, out var value) ? value : Absent;
    private static bool IsAbsent(JsonNode? node) => ReferenceEquals(node, Absent);

    private static JsonNode? MergeValue(string? key, JsonNode? @base, JsonNode? local, JsonNode? remote)
    {
        if (Equal(local, remote)) return local;
        if (Equal(local, @base)) return remote;
        if (Equal(remote, @base)) return local;
        if (key != null && MaximumKeys.Contains(key) && Number(local) is { } l && Number(remote) is { } r) return l >= r ? local : remote;
        if (key != null && LatestKeys.Contains(key) && local is JsonValue lv && remote is JsonValue rv &&
            lv.TryGetValue<string>(out var ls) && rv.TryGetValue<string>(out var rs)) return string.CompareOrdinal(ls, rs) >= 0 ? local : remote;
        if (local is JsonObject lo && remote is JsonObject ro && (@base is JsonObject || IsAbsent(@base)))
            return MergeObject(@base as JsonObject ?? new JsonObject(), lo, ro);
        if (local is JsonArray la && remote is JsonArray ra && Identified(la) && Identified(ra) &&
            (IsAbsent(@base) || @base is JsonArray ba && Identified(ba)))
            return MergeIdentified(key, @base as JsonArray ?? new JsonArray(), la, ra);
        throw new Conflict();
    }

    private static JsonObject MergeObject(JsonObject @base, JsonObject local, JsonObject remote)
    {
        var result = new JsonObject();
        var keys = new List<string>();
        foreach (var source in new[] { remote, local, @base })
            foreach (var pair in source) if (!keys.Contains(pair.Key)) keys.Add(pair.Key);
        foreach (var key in keys)
        {
            var merged = MergeValue(key, Value(@base, key), Value(local, key), Value(remote, key));
            if (!IsAbsent(merged)) result[key] = merged?.DeepClone();
        }
        return result;
    }

    private static bool Identified(JsonArray array)
    {
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in array)
            if (item is not JsonObject obj || obj["id"] is not JsonValue id || !id.TryGetValue<string>(out var text) || !ids.Add(text)) return false;
        return true;
    }

    private static Dictionary<string, JsonNode> ById(JsonArray array) =>
        array.ToDictionary(item => item!["id"]!.GetValue<string>(), item => item!, StringComparer.Ordinal);

    private static JsonArray MergeIdentified(string? key, JsonArray @base, JsonArray local, JsonArray remote)
    {
        var ancestor = ById(@base); var ours = ById(local); var theirs = ById(remote);
        var kept = new Dictionary<string, JsonNode?>(StringComparer.Ordinal);
        foreach (var id in ancestor.Keys.Concat(ours.Keys).Concat(theirs.Keys))
        {
            if (kept.ContainsKey(id)) continue;
            var before = ancestor.GetValueOrDefault(id) ?? Absent;
            var mine = ours.GetValueOrDefault(id) ?? Absent;
            var other = theirs.GetValueOrDefault(id) ?? Absent;
            JsonNode? merged;
            if (!IsAbsent(before) && IsAbsent(mine)) merged = Equal(other, before) || key == Erasable ? Absent : throw new Conflict();
            else if (!IsAbsent(before) && IsAbsent(other)) merged = Equal(mine, before) || key == Erasable ? Absent : throw new Conflict();
            else merged = MergeValue(null, before, mine, other);
            if (!IsAbsent(merged)) kept[id] = merged;
        }
        var order = theirs.Keys.Where(kept.ContainsKey).ToList();
        var placed = new HashSet<string>(order, StringComparer.Ordinal);
        var anchor = -1;
        foreach (var id in ours.Keys)
        {
            if (!kept.ContainsKey(id)) continue;
            if (placed.Contains(id)) { anchor = order.IndexOf(id); continue; }
            order.Insert(anchor + 1, id); placed.Add(id); anchor++;
        }
        return new JsonArray(order.Select(id => kept[id]?.DeepClone()).ToArray());
    }

    private static decimal? Number(JsonNode? node) =>
        node is JsonValue value && value.GetValueKind() == JsonValueKind.Number && decimal.TryParse(value.ToJsonString(),
            System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number) ? number : null;

    private static bool Equal(JsonNode? left, JsonNode? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left == null || right == null) return false;
        if (IsAbsent(left) || IsAbsent(right)) return false;
        switch (left)
        {
            case JsonObject lo when right is JsonObject ro:
                return lo.Count == ro.Count && lo.All(pair => ro.TryGetPropertyValue(pair.Key, out var other) && Equal(pair.Value, other));
            case JsonArray la when right is JsonArray ra:
                return la.Count == ra.Count && la.Zip(ra).All(pair => Equal(pair.First, pair.Second));
            case JsonValue lv when right is JsonValue rv:
                if (lv.GetValueKind() == JsonValueKind.Number && rv.GetValueKind() == JsonValueKind.Number)
                    return Number(lv) is { } a && Number(rv) is { } b ? a == b : lv.ToJsonString() == rv.ToJsonString();
                return lv.GetValueKind() == rv.GetValueKind() && lv.ToJsonString() == rv.ToJsonString();
            default: return false;
        }
    }
}
