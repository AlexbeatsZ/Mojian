namespace Notein_win.Services;

/// <summary>
/// Dependency components of a folder-library v3 synchronization plan. A page file, its assets under
/// <c>.mojian/assets/&lt;page-id&gt;</c> and the page that owns its PDF source form one group; every folder and
/// folder descriptor is independent (Syncthing semantics), so one held page never holds back its whole section.
/// </summary>
internal sealed class NativeSyncDependencies
{
    private readonly Dictionary<string, string> _parents = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _pageOfPath = new(StringComparer.Ordinal);

    /// <param name="pages">Page file path to its page id and PDF owner page id, from local files or downloaded blobs.</param>
    public static NativeSyncDependencies FromPages(IEnumerable<(string Path, string? PageId, string? Owner)> pages)
    {
        var dependencies = new NativeSyncDependencies();
        foreach (var (path, pageId, owner) in pages)
        {
            if (pageId == null) continue;
            dependencies._pageOfPath[path] = "page:" + pageId;
            dependencies.Connect("page:" + pageId, "path:" + path);
            if (owner != null) dependencies.Connect("page:" + pageId, "page:" + owner);
        }
        return dependencies;
    }

    public string Group(string path) => Root(Key(path));

    private string Key(string path)
    {
        if (_pageOfPath.TryGetValue(path, out var page)) return page;
        var parts = path.Split('/');
        if (parts.Length == 4 && parts[0] == ".mojian" && parts[1] == "assets") return "page:" + parts[2];
        return "path:" + path;
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
}
