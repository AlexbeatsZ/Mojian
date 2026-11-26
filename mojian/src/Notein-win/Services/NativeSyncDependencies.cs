using System.Text.Json;
using System.Text.Json.Nodes;

namespace Notein_win.Services;

/// <summary>Dependency components of the current synchronization plan.</summary>
internal sealed class NativeSyncDependencies
{
    private readonly Dictionary<string, string> _parents = new(StringComparer.Ordinal);
    private const string Library = "library.json";

    public static NativeSyncDependencies FromIndexes(JsonObject? local, JsonObject? remote, bool indexChanges,
        IEnumerable<string> structuralPaths)
    {
        var dependencies = new NativeSyncDependencies();
        try
        {
            if (local == null || remote == null) throw new JsonException("笔记目录解析失败");
            var ours = ReadIndex(local);
            var theirs = ReadIndex(remote);
            foreach (var index in new[] { ours, theirs })
                foreach (var (page, reference) in index)
                    if (reference.SourceOwner != null) dependencies.Connect(page, reference.SourceOwner);
            if (indexChanges)
            {
                foreach (var page in ours.Keys.Union(theirs.Keys))
                    if (ours.GetValueOrDefault(page) != theirs.GetValueOrDefault(page)) dependencies.Connect(Library, page);
                foreach (var path in structuralPaths) dependencies.Connect(Library, PageKey(path));
            }
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or ArgumentException)
        {
            // An index we cannot interpret prevents proving independence.
            dependencies._unknown = true;
        }
        return dependencies;
    }

    private bool _unknown;
    public bool Known => !_unknown;

    public string Group(string path) => _unknown ? Library : Root(PageKey(path));

    private static string PageKey(string path)
    {
        if (path == Library) return Library;
        var parts = path.Split('/');
        return parts[2] == "pages" ? $"{parts[1]}/{parts[3][..^5]}" : $"{parts[1]}/{parts[3]}";
    }

    private string Root(string group)
    {
        while (_parents.TryGetValue(group, out var parent)) group = parent;
        return group;
    }

    private void Connect(string left, string right)
    {
        var root = Root(left);
        var other = Root(right);
        if (root != other) _parents[other] = root;
    }

    private sealed record Reference(string? SourceOwner, string? SourceAsset, string? RenderedAsset);

    private static Dictionary<string, Reference> ReadIndex(JsonObject index)
    {
        static IEnumerable<JsonObject> Objects(JsonObject parent, string key, bool required = false)
        {
            if (!parent.TryGetPropertyValue(key, out var node) && !required) return [];
            if (node is not JsonArray array) throw new JsonException("笔记目录列表无效");
            return array.Select(value => value as JsonObject ?? throw new JsonException("笔记目录成员无效"));
        }
        var pages = new Dictionary<string, Reference>(StringComparer.Ordinal);
        foreach (var notebook in Objects(index, "notebooks", required: true))
        {
            var notebookId = notebook["id"]?.GetValue<string>() ?? throw new JsonException("笔记目录缺少笔记本标识");
            Visit(notebookId, Objects(notebook, "sections", required: true));
        }
        return pages;

        void Visit(string notebook, IEnumerable<JsonObject> sections)
        {
            foreach (var section in sections)
            {
                foreach (var page in Objects(section, "pages"))
                {
                    var pageId = page["id"]?.GetValue<string>() ?? throw new JsonException("笔记目录缺少页标识");
                    var path = $"notebooks/{notebook}/pages/{pageId}.json";
                    if (!NativeSyncClient.ValidPath(path)) throw new JsonException("笔记目录页路径无效");
                    var pdf = page["pdfPage"];
                    var owner = pdf?["sourceOwnerPageId"]?.GetValue<string>();
                    var source = pdf?["sourceAssetName"]?.GetValue<string>() ?? page["pdfSourceAssetName"]?.GetValue<string>();
                    var rendered = pdf?["renderedAssetName"]?.GetValue<string>();
                    if (owner != null && !NativeSyncClient.ValidPath($"notebooks/{notebook}/pages/{owner}.json"))
                        throw new JsonException("PDF 素材所有者无效");
                    if (!pages.TryAdd(PageKey(path), new(owner == null ? null : $"{notebook}/{owner}", source, rendered)))
                        throw new JsonException("笔记目录包含重复页标识");
                }
                Visit(notebook, Objects(section, "children"));
            }
        }
    }
}
