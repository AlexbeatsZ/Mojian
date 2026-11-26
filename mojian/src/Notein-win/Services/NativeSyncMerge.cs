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
    private const string Strokes = "strokes";
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

    /// <summary>"Keep both" for a reviewed conflict: every id item of either side survives; a value changed on both sides keeps the local one.</summary>
    public static JsonObject Union(JsonObject local, JsonObject remote, string? now = null)
    {
        var merged = (JsonObject)UnionValue(local, remote)!;
        if (merged.ContainsKey("revision"))
            merged["revision"] = Math.Max(local["revision"]?.GetValue<long>() ?? 0, remote["revision"]?.GetValue<long>() ?? 0) + 1;
        if (merged.ContainsKey("updatedAt")) merged["updatedAt"] = now ?? DateTimeOffset.UtcNow.ToString("O");
        return merged;
    }

    private static JsonNode? UnionValue(JsonNode? local, JsonNode? remote)
    {
        if (IsAbsent(local)) return remote?.DeepClone();
        if (IsAbsent(remote) || Equal(local, remote)) return local?.DeepClone();
        if (local is JsonObject lo && remote is JsonObject ro)
        {
            var result = new JsonObject();
            foreach (var key in ro.Select(p => p.Key).Concat(lo.Select(p => p.Key)).Distinct())
                result[key] = UnionValue(Value(lo, key), Value(ro, key));
            return result;
        }
        if (local is JsonArray la && remote is JsonArray ra && Identified(la) && Identified(ra))
        {
            var ours = ById(la); var theirs = ById(ra);
            var kept = ours.Keys.Concat(theirs.Keys).Distinct().ToDictionary(id => id,
                id => UnionValue(ours.GetValueOrDefault(id) ?? Absent, theirs.GetValueOrDefault(id) ?? Absent), StringComparer.Ordinal);
            return new JsonArray(Order(ours.Keys, theirs.Keys, kept.Keys.ToHashSet()).Select(id => kept[id]).ToArray());
        }
        return local?.DeepClone();
    }

    private static List<string> Order(IEnumerable<string> local, IEnumerable<string> remote, IReadOnlySet<string> kept)
    {
        var order = remote.Where(kept.Contains).ToList();
        var placed = new HashSet<string>(order, StringComparer.Ordinal);
        var anchor = -1;
        foreach (var id in local)
        {
            if (!kept.Contains(id)) continue;
            if (placed.Contains(id)) { anchor = order.IndexOf(id); continue; }
            order.Insert(anchor + 1, id); placed.Add(id); anchor++;
        }
        return order;
    }

    /// <summary>Id items (strokes, pages) only on this device, only on the server, or on both but different.</summary>
    internal sealed record Summary(int LocalOnly, int RemoteOnly, int Changed);

    public static Summary Summarize(JsonObject local, JsonObject remote)
    {
        int localOnly = 0, remoteOnly = 0, changed = 0;
        void Walk(JsonNode? left, JsonNode? right)
        {
            if (left is JsonObject lo && right is JsonObject ro)
                foreach (var key in lo.Select(p => p.Key).Union(ro.Select(p => p.Key))) Walk(Value(lo, key), Value(ro, key));
            else if (left is JsonArray la && right is JsonArray ra && Identified(la) && Identified(ra))
            {
                var ours = ById(la); var theirs = ById(ra);
                foreach (var (id, item) in ours)
                {
                    if (!theirs.TryGetValue(id, out var other)) localOnly += Count(item);
                    else if (!Equal(item, other))
                    {
                        if (item is JsonObject io && other is JsonObject oo && HasIdentified(io)) Walk(io, oo); else changed++;
                    }
                }
                foreach (var id in theirs.Keys.Where(id => !ours.ContainsKey(id))) remoteOnly += Count(theirs[id]);
            }
            else if (IsAbsent(left) && !IsAbsent(right)) remoteOnly += CountNested(right);
            else if (IsAbsent(right) && !IsAbsent(left)) localOnly += CountNested(left);
        }
        Walk(local, remote);
        return new(localOnly, remoteOnly, changed);
    }

    private static int Count(JsonNode? item) => Math.Max(1, CountNested(item));
    private static int CountNested(JsonNode? item) => item switch
    {
        JsonObject o => o.Sum(p => CountNested(p.Value)),
        JsonArray a when Identified(a) => a.Sum(Count),
        _ => 0,
    };
    private static bool HasIdentified(JsonObject item) => item.Any(p => p.Value is JsonArray a && a.Count > 0 && Identified(a));

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

    private static JsonObject MergeObject(JsonObject @base, JsonObject local, JsonObject remote, bool stroke = false)
    {
        var result = new JsonObject();
        var keys = new List<string>();
        foreach (var source in new[] { remote, local, @base })
            foreach (var pair in source) if (!keys.Contains(pair.Key)) keys.Add(pair.Key);
        foreach (var key in keys)
        {
            if (stroke && IsSampleField(key)) continue;
            var before = Value(@base, key);
            var mine = Value(local, key);
            var theirs = Value(remote, key);
            var merged = stroke && (EqualStrokeField(key, mine, theirs) || EqualStrokeField(key, mine, before)) ? theirs
                : stroke && EqualStrokeField(key, theirs, before) ? mine
                : MergeValue(key, before, mine, theirs);
            if (!IsAbsent(merged)) result[key] = merged?.DeepClone();
        }
        if (stroke)
        {
            var samples = SamplesEqual(local, remote) || SamplesEqual(local, @base) ? remote
                : SamplesEqual(remote, @base) ? local : throw new Conflict();
            foreach (var pair in samples.Where(pair => IsSampleField(pair.Key)))
                result[pair.Key] = pair.Value?.DeepClone();
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
            bool stroke = key == Strokes;
            JsonNode? merged;
            if (!IsAbsent(before) && IsAbsent(mine)) merged = ItemEqual(other, before, stroke) ? Absent : throw new Conflict();
            else if (!IsAbsent(before) && IsAbsent(other)) merged = ItemEqual(mine, before, stroke) ? Absent : throw new Conflict();
            else if (stroke && mine is JsonObject localStroke && other is JsonObject remoteStroke)
                merged = MergeObject(before as JsonObject ?? new JsonObject(), localStroke, remoteStroke, stroke: true);
            else merged = MergeValue(null, before, mine, other);
            if (!IsAbsent(merged)) kept[id] = merged;
        }
        return new JsonArray(Order(ours.Keys, theirs.Keys, kept.Keys.ToHashSet()).Select(id => kept[id]?.DeepClone()).ToArray());
    }

    private static bool IsSampleField(string key) => key is "points" or "pointsCoded";

    private static bool ItemEqual(JsonNode? left, JsonNode? right, bool stroke)
    {
        if (!stroke || left is not JsonObject lo || right is not JsonObject ro) return Equal(left, right);
        return lo.Select(pair => pair.Key).Union(ro.Select(pair => pair.Key)).Where(key => !IsSampleField(key))
            .All(key => EqualStrokeField(key, Value(lo, key), Value(ro, key))) && SamplesEqual(lo, ro);
    }

    private static bool EqualStrokeField(string key, JsonNode? left, JsonNode? right)
    {
        if (Equal(left, right)) return true;
        var explicitValue = IsAbsent(left) ? right : IsAbsent(right) ? left : Absent;
        return key switch
        {
            "stabilityPercent" => Number(explicitValue) == 50,
            "strokeUnitLengthCm" => Number(explicitValue) == 0,
            _ => false,
        };
    }

    private static bool SamplesEqual(JsonObject left, JsonObject right)
    {
        var leftPlain = Value(left, "points");
        var rightPlain = Value(right, "points");
        var leftCoded = Value(left, "pointsCoded");
        var rightCoded = Value(right, "pointsCoded");
        if (Equal(leftPlain, rightPlain) && Equal(leftCoded, rightCoded)) return true;
        if (leftCoded is JsonValue && rightPlain is JsonArray && IsAbsent(leftPlain) && IsAbsent(rightCoded)) return SamplesEqual(right, left);
        return IsAbsent(leftCoded) && IsAbsent(rightPlain) && leftPlain is JsonArray points
            && rightCoded is JsonValue coded && coded.TryGetValue<string>(out var text)
            && InkPointCodec.TryEncode(points) is { } packed && packed == text;
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
