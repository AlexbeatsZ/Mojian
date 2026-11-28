using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>
/// The v3 folder library (docs/design/folder-library.md): notebooks and sections are real folders, a page is
/// <c>&lt;title&gt;.mojian</c>, assets live in <c>.mojian/assets/&lt;page-id&gt;</c>. The public API stays keyed by
/// notebook/page ids; an id-to-path index built from the folder tree resolves every location.
/// </summary>
public sealed class WorkspaceStore
{
    private readonly Dictionary<string, (long Length, long Modified)?> _observedFiles = new();
    private readonly object _sync;
    private readonly Dictionary<string, HashSet<string?>> _localVersions = new();
    private Index _index = new();
    private bool _indexed;
    private string? _snapshot;
    private string? _structure;

    private sealed class Index
    {
        public Dictionary<string, string> Folders { get; } = new();       // notebook/section id -> relative folder
        public Dictionary<string, string> Pages { get; } = new();         // page id -> relative file
        public Dictionary<string, string> Keys { get; } = new();          // item id -> sortKey
        public Dictionary<string, string> PageMeta { get; } = new();      // page id -> "page" JSON as stored
        public Dictionary<string, string> PageRaw { get; } = new();       // page id -> "page" object text exactly as on disk
        public Dictionary<string, string> FolderMeta { get; } = new();    // folder id -> descriptor extras JSON
        public Dictionary<string, string> PageOwner { get; } = new();     // page id -> notebook id
        public Dictionary<string, NotePage> PageObjects { get; } = new(); // relative page file -> loaded page
        public List<(string Path, string OriginalId)> Duplicates { get; } = new();
    }

    private static string? PageStamp(string path) => Stamp(path) is { } stamp ? $"{stamp.Length}:{stamp.Modified}" : null;
    private static (long Length, long Modified)? Stamp(string path) => File.Exists(path)
        ? (new FileInfo(path).Length, File.GetLastWriteTimeUtc(path).Ticks) : null;
    private void Observe(string path) { lock (_sync) _observedFiles[path] = Stamp(path); }
    private void GuardExternalWrite(string path)
    {
        if (_observedFiles.TryGetValue(path, out var seen) && seen != Stamp(path))
            throw new IOException("笔记已在另一台设备更新，已阻止覆盖。请等待同步完成并重新加载笔记库。");
    }

    /// <summary>True when an observed page changed or another device added, moved or renamed library items.</summary>
    public bool HasExternalChanges
    {
        get { lock (_sync) return _observedFiles.Any(pair => pair.Value != Stamp(pair.Key)) || (_structure != null && _structure != StructureSignature()); }
    }

    public void ForgetInactivePages(params PageDocument?[] documents)
    {
        lock (_sync)
        {
            var keep = documents.Where(doc => doc != null && _index.Pages.ContainsKey(doc.PageId)).Select(doc => PagePath(doc!.PageId)).ToHashSet();
            foreach (var path in _observedFiles.Keys.Where(path => !keep.Contains(path)).ToArray()) _observedFiles.Remove(path);
        }
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };
    private static readonly JsonSerializerOptions CompactOptions = new(JsonOptions) { WriteIndented = false };
    private static readonly JsonSerializerOptions PageJsonOptions = new(JsonOptions)
    {
        WriteIndented = false,
        Converters = { new StrokeJsonConverter() },
    };

    /// <summary>Page serializer (strokes via <see cref="StrokeJsonConverter"/>), e.g. for previewing a sync conflict copy.</summary>
    internal static JsonSerializerOptions PageSerializerOptions => PageJsonOptions;

    public string RootDirectory { get; }
    private string HiddenDirectory => Path.Combine(RootDirectory, FolderNames.HiddenRoot);
    /// <summary>Device-local workspace settings; never synchronized.</summary>
    public string SettingsPath => Path.Combine(HiddenDirectory, "workspace-settings.json");
    private string NavigationPath => Path.Combine(HiddenDirectory, "navigation.json");
    private string PendingDirectory => Path.Combine(HiddenDirectory, "pending");

    public static string DefaultRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Mojian",
        "Workspace");

    public WorkspaceStore(string? rootDirectory = null)
    {
        RootDirectory = Path.GetFullPath(string.IsNullOrWhiteSpace(rootDirectory) ? DefaultRoot : rootDirectory);
        _sync = WorkspaceFileGate.ForRoot(RootDirectory);
        Directory.CreateDirectory(RootDirectory);
    }

    // ---- Library -------------------------------------------------------------------------------------------

    public NotebookLibrary LoadLibrary()
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync) return LoadLibraryCore();
    }

    private NotebookLibrary LoadLibraryCore()
    {
        var index = new Index();
        var folders = ReadFolders(string.Empty, index, notebookId: null);
        ResolveDuplicates(index, _index);
        var notebooks = folders.Select(folder => ToNotebook(folder, index)).ToList();
        if (notebooks.Count == 0)
        {
            // An empty root beside existing sync state or pages is an accident, not a new library: a sample would be
            // published over the shared library by sync.
            if (!IsNewWorkspace(RootDirectory)) throw new WorkspaceLibraryMissingException(RootDirectory);
            _index = index;
            _indexed = true;
            var created = NotebookLibrary.Sample();
            SaveLibraryCore(created);
            SaveWelcomePage(created.ActiveNotebook);
            return created;
        }
        var library = new NotebookLibrary { Version = 3, Notebooks = notebooks };
        ApplyNavigation(library, ReadNavigation());
        if (library.Notebooks.All(notebook => notebook.Id != library.ActiveNotebookId)) library.ActiveNotebookId = library.Notebooks[0].Id;
        NormalizeSelections(library);
        _index = index;
        _indexed = true;
        WorkspaceDeletionTransaction.Recover(RootDirectory, relative => KeepsDeletedFile(relative, index));
        _snapshot = Snapshot(library);
        _structure = StructureSignature();
        return library;
    }

    private sealed record FolderRead(string Id, string Name, string Relative, string SortKey, string Extras,
        List<FolderRead> Folders, List<(NotePage Page, string SortKey)> Pages);

    /// <summary>Folders below <paramref name="relative"/>, adopting folders and pages created outside Mojian.</summary>
    private List<FolderRead> ReadFolders(string relative, Index index, string? notebookId)
    {
        var directory = Absolute(relative);
        var result = new List<FolderRead>();
        foreach (var path in Directory.EnumerateDirectories(directory))
        {
            var name = Path.GetFileName(path);
            if (FolderNames.IsHidden(name) || (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) continue;
            var childRelative = Combine(relative, name);
            var descriptorPath = Path.Combine(path, FolderNames.Descriptor);
            var descriptor = ReadDescriptor(descriptorPath);
            var id = descriptor["id"]?.GetValue<string>();
            var key = descriptor["sortKey"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(id) || index.Folders.ContainsKey(id) || !IsSafeId(id))
            {
                // A folder made in a file manager or copied with its descriptor becomes its own notebook/section.
                id = IdFactory.New(notebookId == null ? "notebook" : "section");
                descriptor["id"] = id;
                descriptor["version"] = 3;
                AtomicWrite(descriptorPath, descriptor.ToJsonString(JsonOptions));
            }
            index.Folders[id] = childRelative;
            var extras = (JsonObject)descriptor.DeepClone();
            foreach (var field in new[] { "version", "id", "sortKey" }) extras.Remove(field);
            index.FolderMeta[id] = extras.ToJsonString(CompactOptions);
            var owner = notebookId ?? id;
            result.Add(new FolderRead(id, name, childRelative, FractionalIndex.IsValid(key) ? key! : string.Empty,
                index.FolderMeta[id], ReadFolders(childRelative, index, owner), notebookId == null ? [] : ReadPages(childRelative, index, owner)));
        }
        AssignMissingKeys(result.Select(folder => (folder.Id, folder.SortKey, folder.Name)).ToList(), index);
        return result.OrderBy(folder => index.Keys[folder.Id], StringComparer.Ordinal).ThenBy(folder => folder.Name, StringComparer.Ordinal).ToList();
    }

    /// <summary>A damaged descriptor is restored from its previous copy; otherwise a corrupt copy is kept and the folder re-adopted.</summary>
    private JsonObject ReadDescriptor(string path)
    {
        static JsonObject? Parse(string file)
        {
            try { return JsonNode.Parse(File.ReadAllText(file)) as JsonObject; }
            catch (JsonException) { return null; }
        }
        if (!File.Exists(path)) return new JsonObject();
        if (Parse(path) is { } current) return current;
        PreserveCorruptFile(path, RootDirectory);
        var previous = PreviousPath(path);
        if (File.Exists(previous) && Parse(previous) is { } recovered)
        {
            File.Copy(previous, path, overwrite: true);
            return recovered;
        }
        return new JsonObject();
    }

    private List<(NotePage Page, string SortKey)> ReadPages(string relative, Index index, string notebookId)
    {
        var pages = new List<(NotePage Page, string SortKey)>();
        foreach (var path in Directory.EnumerateFiles(Absolute(relative), "*" + FolderNames.PageExtension))
        {
            var name = Path.GetFileName(path);
            if (FolderNames.IsHidden(name)) continue;
            PageFileFormat.Head head;
            try { head = PageFileFormat.ReadHead(path); }
            catch (Exception error) when (error is InvalidDataException or JsonException)
            {
                // A corrupt page keeps its place through the previous version's head; loading it recovers the content.
                try { head = PageFileFormat.ReadHead(PreviousPath(path)); }
                catch (Exception previous) when (previous is InvalidDataException or JsonException or IOException) { continue; }
            }
            var page = head.Page?.Deserialize<NotePage>(JsonOptions) ?? new NotePage();
            var id = head.Id;
            var relativeFile = Combine(relative, name);
            if (!string.IsNullOrWhiteSpace(id) && IsSafeId(id) && index.Pages.ContainsKey(id))
            {
                // A copy made in a file manager: which file keeps the identity is decided after the whole walk.
                index.Duplicates.Add((relativeFile, id));
                id = IdFactory.New("page");
            }
            else if (string.IsNullOrWhiteSpace(id) || !IsSafeId(id))
            {
                id = IdFactory.New("page");
                var bytes = File.ReadAllBytes(path);
                AtomicWrite(path, PageFileFormat.Compose(id, head.SortKey ?? string.Empty, head.Page ?? new JsonObject(), PageFileFormat.ReadContent(bytes).Span));
            }
            page.Id = id;
            page.Title = name[..^FolderNames.PageExtension.Length];
            var relativePath = relativeFile;
            index.PageObjects[relativePath] = page;
            index.Pages[id] = relativePath;
            index.PageOwner[id] = notebookId;
            index.PageMeta[id] = PageMetadata(page).ToJsonString(CompactOptions);
            index.PageRaw[id] = (head.Page ?? new JsonObject()).ToJsonString(CompactOptions);
            pages.Add((page, FractionalIndex.IsValid(head.SortKey) ? head.SortKey! : string.Empty));
        }
        AssignMissingKeys(pages.Select(item => (item.Page.Id, item.SortKey, item.Page.Title)).ToList(), index);
        return pages.OrderBy(item => index.Keys[item.Page.Id], StringComparer.Ordinal).ThenBy(item => item.Page.Title, StringComparer.Ordinal).ToList();
    }

    /// <summary>
    /// Two files carrying one page id: the file the previous index knew keeps it (otherwise the older file); the other
    /// copy is written back under its new id, so navigation, assets and sync never follow the copy.
    /// </summary>
    private void ResolveDuplicates(Index index, Index previous)
    {
        foreach (var (copyPath, originalId) in index.Duplicates)
        {
            var firstPath = index.Pages[originalId];
            var copyPage = index.PageObjects[copyPath];
            var copyId = copyPage.Id;
            var copyWins = previous.Pages.TryGetValue(originalId, out var known)
                ? known == copyPath
                : File.GetCreationTimeUtc(Absolute(copyPath)) < File.GetCreationTimeUtc(Absolute(firstPath));
            var (loserPath, loserId) = copyWins ? (firstPath, copyId) : (copyPath, copyId);
            if (copyWins)
            {
                var firstPage = index.PageObjects[firstPath];
                firstPage.Id = copyId; copyPage.Id = originalId;
                index.Pages[originalId] = copyPath; index.Pages[copyId] = firstPath;
                foreach (var map in new[] { index.PageMeta, index.PageRaw, index.Keys, index.PageOwner })
                    if (map.Remove(originalId, out var value)) { if (map.Remove(copyId, out var other)) map[originalId] = other; map[copyId] = value; }
            }
            var loser = Absolute(loserPath);
            var head = PageFileFormat.ReadHead(loser);
            AtomicWrite(loser, PageFileFormat.Compose(loserId, head.SortKey ?? string.Empty, head.Page ?? new JsonObject(), PageFileFormat.ReadContent(File.ReadAllBytes(loser)).Span));
        }
        index.Duplicates.Clear();
    }

    /// <summary>Items without a valid key sort after keyed items by name; their keys are written on the next save.</summary>
    private static void AssignMissingKeys(List<(string Id, string Key, string Name)> items, Index index)
    {
        var last = items.Where(item => item.Key.Length > 0).Select(item => item.Key).OrderBy(key => key, StringComparer.Ordinal).LastOrDefault();
        foreach (var item in items.OrderBy(item => item.Name, StringComparer.Ordinal))
            index.Keys[item.Id] = item.Key.Length > 0 ? item.Key : last = FractionalIndex.Between(last, null);
    }

    private static NotebookWorkspace ToNotebook(FolderRead folder, Index index)
    {
        var notebook = new NotebookWorkspace { Id = folder.Id, Title = folder.Name, AdditionalData = Extras(folder.Extras) };
        notebook.Sections = folder.Folders.Select(ToSection).ToList();
        return notebook;

        SectionNode ToSection(FolderRead section) => new()
        {
            Id = section.Id, Title = section.Name, AdditionalData = Extras(section.Extras),
            Pages = section.Pages.Select(item => item.Page).ToList(),
            Children = section.Folders.Select(ToSection).ToList(),
        };
    }

    private static Dictionary<string, JsonElement>? Extras(string json)
    {
        var values = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(json);
        return values is { Count: > 0 } ? values : null;
    }

    /// <summary>The stored "page" object: every NotePage field except identity and title (the file name).</summary>
    private static JsonObject PageMetadata(NotePage page)
    {
        var json = JsonSerializer.SerializeToNode(page, CompactOptions)!.AsObject();
        json.Remove("id");
        json.Remove("title");
        return json;
    }

    public bool SaveLibrary(NotebookLibrary library)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync) return SaveLibraryCore(library);
    }

    private bool SaveLibraryCore(NotebookLibrary library)
    {
        WriteNavigation(library);
        var snapshot = Snapshot(library);
        if (snapshot == _snapshot) return false;
        if (_structure != null && _structure != StructureSignature())
            throw new IOException("笔记库已在另一台设备更新，已阻止覆盖。请等待同步完成并重新加载笔记库。");
        Apply(library);
        _snapshot = snapshot;
        _structure = StructureSignature();
        return true;
    }

    public bool LibraryHasChanges(NotebookLibrary library) => Snapshot(library) != _snapshot;

    public void ValidateLibraryUnchanged()
    {
        lock (_sync)
            if (_structure != null && _structure != StructureSignature())
                throw new IOException("笔记库已在另一台设备更新，已阻止覆盖。请等待同步完成并重新加载笔记库。");
    }

    /// <summary>Synchronized library state: titles, order and metadata, without device-local navigation.</summary>
    private static string Snapshot(NotebookLibrary library)
    {
        var builder = new StringBuilder();
        foreach (var notebook in library.Notebooks)
        {
            builder.Append("N|").Append(notebook.Id).Append('|').Append(notebook.Title).Append('|').Append(JsonSerializer.Serialize(notebook.AdditionalData)).Append('\n');
            foreach (var section in notebook.Sections) Section(section, 1);
        }
        return builder.ToString();

        void Section(SectionNode section, int depth)
        {
            builder.Append("S").Append(depth).Append('|').Append(section.Id).Append('|').Append(section.Title).Append('|').Append(JsonSerializer.Serialize(section.AdditionalData)).Append('\n');
            foreach (var page in section.Pages) builder.Append("P|").Append(page.Id).Append('|').Append(page.Title).Append('|').Append(PageMetadata(page).ToJsonString(CompactOptions)).Append('\n');
            foreach (var child in section.Children) Section(child, depth + 1);
        }
    }

    /// <summary>
    /// Folder names, page files and descriptors visible to other devices. Page saves change only content, which
    /// the per-page stamps guard, so a local save never looks like an external structural change.
    /// </summary>
    private string StructureSignature()
    {
        var builder = new StringBuilder();
        void Walk(string directory, string relative)
        {
            foreach (var entry in new DirectoryInfo(directory).EnumerateFileSystemInfos().OrderBy(entry => entry.Name, StringComparer.Ordinal))
            {
                if (entry.Name.StartsWith('.') && entry.Name != FolderNames.Descriptor) continue;
                var name = Combine(relative, entry.Name);
                if (entry is DirectoryInfo folder) { builder.Append("D ").Append(name).Append('\n'); Walk(folder.FullName, name); }
                else if (entry.Name == FolderNames.Descriptor) builder.Append("F ").Append(name).Append(' ').Append(PageStamp(entry.FullName)).Append('\n');
                else if (entry.Name.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal)) builder.Append("P ").Append(name).Append('\n');
            }
        }
        Walk(RootDirectory, string.Empty);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(builder.ToString())));
    }

    // ---- Applying a library to the folder tree --------------------------------------------------------------

    private sealed record Placement(string Id, string Title, bool IsPage, string? Parent, string? Owner, object Item);

    private void Apply(NotebookLibrary library)
    {
        var placements = new List<Placement>();
        foreach (var notebook in library.Notebooks)
        {
            placements.Add(new Placement(notebook.Id, notebook.Title, false, null, notebook.Id, notebook));
            foreach (var section in notebook.Sections) AddSection(section, notebook.Id, notebook.Id);
        }
        void AddSection(SectionNode section, string parent, string owner)
        {
            placements.Add(new Placement(section.Id, section.Title, false, parent, owner, section));
            foreach (var page in section.Pages) placements.Add(new Placement(page.Id, page.Title, true, section.Id, owner, page));
            foreach (var child in section.Children) AddSection(child, section.Id, owner);
        }
        var wanted = placements.Select(item => item.Id).ToHashSet();

        // Pages and folders removed from the library (their files were already moved by a deletion transaction
        // when the user deleted them; anything left is removed here).
        foreach (var (id, relative) in _index.Pages.Where(pair => !wanted.Contains(pair.Key)).ToArray())
        {
            var path = Absolute(relative);
            if (File.Exists(path)) { GuardExternalWrite(path); File.Delete(path); }
            ForgetPath(path);
            var assets = AssetDirectory(id);
            if (Directory.Exists(assets)) Directory.Delete(assets, recursive: true);
            _index.Pages.Remove(id); _index.PageMeta.Remove(id); _index.Keys.Remove(id); _index.PageOwner.Remove(id);
        }
        foreach (var (id, relative) in _index.Folders.Where(pair => !wanted.Contains(pair.Key)).OrderByDescending(pair => pair.Value.Length).ToArray())
        {
            var path = Absolute(relative);
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
            _index.Folders.Remove(id); _index.FolderMeta.Remove(id); _index.Keys.Remove(id);
        }

        // Names and keys per parent. An item keeps its current name while that name still represents its title.
        var finalPaths = new Dictionary<string, string>();
        var keys = new Dictionary<string, string>();
        foreach (var siblings in placements.GroupBy(item => (item.Parent, item.IsPage)))
        {
            foreach (var pair in FractionalIndex.Assign(siblings.Select(item => item.Id).ToList(), _index.Keys)) keys[pair.Key] = pair.Value;
        }
        foreach (var parentGroup in placements.GroupBy(item => item.Parent))
        {
            var taken = new HashSet<string>(StringComparer.Ordinal) { FolderNames.Descriptor.ToLowerInvariant() };
            var parentRelative = parentGroup.Key == null ? string.Empty : null;
            var decided = new List<(Placement Item, string? Name)>();
            foreach (var item in parentGroup)
            {
                var suffix = item.IsPage ? FolderNames.PageExtension : string.Empty;
                var current = CurrentRelative(item);
                var keep = current != null && Path.GetDirectoryName(current)?.Replace('\\', '/') == ParentRelativeOf(item, null) &&
                    FolderNames.Represents(Path.GetFileName(current), item.Title, suffix) && taken.Add(Path.GetFileName(current).ToLowerInvariant());
                decided.Add((item, keep ? Path.GetFileName(current) : null));
            }
            foreach (var (item, name) in decided)
                finalPaths[item.Id] = name ?? FolderNames.Unique(FolderNames.Storable(item.Title), item.IsPage ? FolderNames.PageExtension : string.Empty, taken);
        }

        // Moves happen in two phases (to a hidden temporary name, then into place top-down), so renames that swap
        // names or move a folder into a renamed parent never collide.
        string FinalRelative(Placement item) => item.Parent == null ? finalPaths[item.Id] : Combine(FinalRelative(placements.First(parent => parent.Id == item.Parent)), finalPaths[item.Id]);
        var moving = placements.Where(item => CurrentRelative(item) is { } current && current != FinalRelative(item)).ToList();
        var staged = new Dictionary<string, string>();
        foreach (var item in moving.OrderByDescending(item => CurrentRelative(item)!.Length))
        {
            var current = Absolute(CurrentRelative(item)!);
            var temporary = Path.Combine(Path.GetDirectoryName(current)!, $".mojian-move-{Guid.NewGuid():N}");
            if (item.IsPage) { GuardExternalWrite(current); File.Move(current, temporary); }
            else Directory.Move(current, temporary);
            staged[item.Id] = temporary;
            MoveIndexPrefix(CurrentRelative(item)!, Path.GetRelativePath(RootDirectory, temporary).Replace('\\', '/'), item.IsPage);
        }
        foreach (var item in placements)
        {
            var target = Absolute(FinalRelative(item));
            if (!item.IsPage)
            {
                if (staged.TryGetValue(item.Id, out var from)) { Directory.Move(CurrentAbsolute(item)!, target); }
                else if (!Directory.Exists(target)) Directory.CreateDirectory(target);
                MoveIndexPrefix(CurrentRelative(item) ?? FinalRelative(item), FinalRelative(item), false);
                _index.Folders[item.Id] = FinalRelative(item);
                var extras = item.Item switch { NotebookWorkspace notebook => notebook.AdditionalData, SectionNode section => section.AdditionalData, _ => null };
                var extrasJson = JsonSerializer.Serialize(extras ?? new Dictionary<string, JsonElement>(), CompactOptions);
                var descriptor = Path.Combine(target, FolderNames.Descriptor);
                if (!File.Exists(descriptor) || !_index.Keys.TryGetValue(item.Id, out var oldKey) || oldKey != keys[item.Id] ||
                    !_index.FolderMeta.TryGetValue(item.Id, out var oldExtras) || oldExtras != extrasJson)
                {
                    var json = new JsonObject { ["version"] = 3, ["id"] = item.Id, ["sortKey"] = keys[item.Id] };
                    foreach (var pair in JsonNode.Parse(extrasJson)!.AsObject().ToArray()) json[pair.Key] = pair.Value?.DeepClone();
                    AtomicWrite(descriptor, json.ToJsonString(JsonOptions));
                }
                _index.FolderMeta[item.Id] = extrasJson;
            }
            else
            {
                if (staged.ContainsKey(item.Id)) { var source = CurrentAbsolute(item)!; File.Move(source, target); ForgetPath(source); _index.Pages[item.Id] = FinalRelative(item); }
                WritePageHead((NotePage)item.Item, item.Owner!, target, keys[item.Id]);
            }
            _index.Keys[item.Id] = keys[item.Id];
        }

        string? CurrentRelative(Placement item) => item.IsPage ? _index.Pages.GetValueOrDefault(item.Id) : _index.Folders.GetValueOrDefault(item.Id);
        string? CurrentAbsolute(Placement item) => CurrentRelative(item) is { } relative ? Absolute(relative) : null;
        string? ParentRelativeOf(Placement item, string? _) => item.Parent == null ? string.Empty : _index.Folders.GetValueOrDefault(item.Parent);
    }

    /// <summary>Re-points index entries and guards after a folder or page path changes.</summary>
    private void MoveIndexPrefix(string from, string to, bool page)
    {
        if (from == to) return;
        bool Under(string path) => path == from || path.StartsWith(from + "/", StringComparison.Ordinal);
        string Rebase(string path) => to + path[from.Length..];
        foreach (var (id, path) in _index.Pages.ToArray())
            if (Under(path)) { RenameGuards(Absolute(path), Absolute(Rebase(path))); _index.Pages[id] = Rebase(path); }
        if (page) return;
        foreach (var (id, path) in _index.Folders.ToArray()) if (Under(path)) _index.Folders[id] = Rebase(path);
    }

    private void RenameGuards(string from, string to)
    {
        if (_observedFiles.Remove(from, out var stamp)) _observedFiles[to] = stamp;
        if (_localVersions.Remove(from, out var versions)) _localVersions[to] = versions;
    }

    private void ForgetPath(string path) { _observedFiles.Remove(path); _localVersions.Remove(path); }

    /// <summary>Creates a new page file (using staged content) or rewrites an existing page's head when it changed.</summary>
    private void WritePageHead(NotePage page, string notebookId, string path, string key)
    {
        var meta = PageMetadata(page);
        var metaJson = meta.ToJsonString(CompactOptions);
        _index.PageOwner[page.Id] = notebookId;
        if (File.Exists(path) && _index.Pages.ContainsKey(page.Id))
        {
            if (_index.PageMeta.GetValueOrDefault(page.Id) == metaJson && _index.Keys.GetValueOrDefault(page.Id) == key) return;
            var before = PageStamp(path);
            var bytes = File.ReadAllBytes(path);
            // Metadata changed: write the normalized form; an unchanged head keeps its stored bytes.
            var head = _index.PageMeta.GetValueOrDefault(page.Id) == metaJson ? StoredHead(page.Id) : meta;
            AtomicWrite(path, PageFileFormat.Compose(page.Id, key, head, PageFileFormat.ReadContent(bytes).Span));
            RememberLocalVersion(path, before);
            _index.PageRaw[page.Id] = head.ToJsonString(CompactOptions);
        }
        else
        {
            var pending = PendingPath(page.Id);
            var content = File.Exists(pending) ? File.ReadAllBytes(pending)
                : JsonSerializer.SerializeToUtf8Bytes(new PageDocument(notebookId, page.Id), PageJsonOptions);
            AtomicWrite(path, PageFileFormat.Compose(page.Id, key, meta, content));
            if (File.Exists(pending)) File.Delete(pending);
            RememberLocalVersion(path, null);
            _index.Pages[page.Id] = Path.GetRelativePath(RootDirectory, path).Replace('\\', '/');
            _index.PageRaw[page.Id] = metaJson;
        }
        _index.PageMeta[page.Id] = metaJson;
    }

    /// <summary>
    /// The head written with a page save: the bytes read from disk while the metadata is unchanged, so saving strokes
    /// never reorders or rewrites what other devices see in the page head.
    /// </summary>
    private JsonObject StoredHead(string pageId) =>
        JsonNode.Parse(_index.PageRaw.TryGetValue(pageId, out var raw) ? raw : _index.PageMeta[pageId])!.AsObject();

    /// <summary>A snapshot taken before this store rewrote the head is still this device's own version.</summary>
    private void RememberLocalVersion(string path, string? before)
    {
        if (!_localVersions.TryGetValue(path, out var versions)) _localVersions[path] = versions = new HashSet<string?>();
        versions.Add(before);
        versions.Add(PageStamp(path));
        if (_observedFiles.ContainsKey(path)) Observe(path);
    }

    // ---- Navigation (device-local) ---------------------------------------------------------------------------

    private sealed class Navigation
    {
        public string? ActiveNotebookId { get; set; }
        public Dictionary<string, NotebookNavigation> Notebooks { get; set; } = new();
        public Dictionary<string, bool> CollapsedSections { get; set; } = new();
    }

    private sealed class NotebookNavigation
    {
        public bool Expanded { get; set; } = true;
        public string? FocusedSectionId { get; set; }
        public string SelectedPageSectionId { get; set; } = string.Empty;
        public string SelectedPageId { get; set; } = string.Empty;
    }

    private Navigation ReadNavigation()
    {
        try { return File.Exists(NavigationPath) ? JsonSerializer.Deserialize<Navigation>(File.ReadAllText(NavigationPath), JsonOptions) ?? new() : new(); }
        catch (JsonException) { return new(); }
    }

    private static void ApplyNavigation(NotebookLibrary library, Navigation navigation)
    {
        library.ActiveNotebookId = navigation.ActiveNotebookId ?? string.Empty;
        foreach (var notebook in library.Notebooks)
        {
            if (navigation.Notebooks.TryGetValue(notebook.Id, out var state))
            {
                notebook.Expanded = state.Expanded;
                notebook.FocusedSectionId = state.FocusedSectionId;
                notebook.SelectedPageSectionId = state.SelectedPageSectionId;
                notebook.SelectedPageId = state.SelectedPageId;
            }
            foreach (var section in notebook.FlattenAllSections()) section.Expanded = !navigation.CollapsedSections.ContainsKey(section.Id);
        }
    }

    private void WriteNavigation(NotebookLibrary library)
    {
        var navigation = new Navigation { ActiveNotebookId = library.ActiveNotebookId };
        foreach (var notebook in library.Notebooks)
        {
            navigation.Notebooks[notebook.Id] = new NotebookNavigation
            {
                Expanded = notebook.Expanded, FocusedSectionId = notebook.FocusedSectionId,
                SelectedPageSectionId = notebook.SelectedPageSectionId, SelectedPageId = notebook.SelectedPageId,
            };
            foreach (var section in notebook.FlattenAllSections().Where(section => !section.Expanded)) navigation.CollapsedSections[section.Id] = true;
        }
        var json = JsonSerializer.Serialize(navigation, JsonOptions);
        if (!File.Exists(NavigationPath) || File.ReadAllText(NavigationPath) != json) AtomicWrite(NavigationPath, json);
    }

    // ---- Settings ----------------------------------------------------------------------------------------------

    public WorkspaceSettings LoadSettings()
    {
        RestoreMissingCurrent(SettingsPath);
        var settings = File.Exists(SettingsPath) ? ReadRecoverable<WorkspaceSettings>(SettingsPath, JsonOptions, recoveryRoot: RootDirectory) : new WorkspaceSettings();
        settings.UpgradeDarkImageImportPolicy();
        return settings;
    }

    public void SaveSettings(WorkspaceSettings settings)
    {
        lock (WorkspaceFileGate.ForRoot(SettingsPath)) AtomicWrite(SettingsPath, JsonSerializer.Serialize(settings, JsonOptions));
    }

    // ---- Pages -------------------------------------------------------------------------------------------------

    private string PagePath(string pageId)
    {
        EnsureIndex();
        return _index.Pages.TryGetValue(pageId, out var relative) ? Absolute(relative) : PendingPath(pageId);
    }

    /// <summary>Page access before the first library load (exports, tools) builds the id-to-path index on demand.</summary>
    private void EnsureIndex()
    {
        if (_indexed) return;
        var index = new Index();
        ReadFolders(string.Empty, index, notebookId: null);
        ResolveDuplicates(index, _index);
        _index = index;
        _indexed = true;
    }
    private string PendingPath(string pageId) => Path.Combine(PendingDirectory, SafeId(pageId) + ".json");

    public PageDocument LoadPage(string notebookId, string pageId)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync) return LoadPageCore(notebookId, pageId);
    }

    private PageDocument LoadPageCore(string notebookId, string pageId)
    {
        var path = PagePath(pageId);
        RestoreMissingCurrent(path);
        if (!File.Exists(path)) { Observe(path); throw new PageContentMissingException(notebookId, pageId); }
        var bytes = File.ReadAllBytes(path);
        var content = _index.Pages.ContainsKey(pageId) ? PageFileFormat.ReadContent(bytes) : bytes;
        PageDocument document;
        try { document = JsonSerializer.Deserialize<PageDocument>(content.Span, PageJsonOptions) ?? throw new JsonException("页面为空"); }
        catch (JsonException error)
        {
            PreserveCorruptFile(path, RootDirectory);
            throw new InvalidDataException($"无法读取页面，原文件已保留：{path}", error);
        }
        document.NotebookId = notebookId;
        document.PageId = pageId;
        document.Viewport = new PageViewport { Scale = document.LegacyViewport.Scale, TranslationX = document.LegacyViewport.TranslationX, TranslationY = document.LegacyViewport.TranslationY };
        if (_observedFiles.TryGetValue(path, out var previous) && previous != Stamp(path)) _localVersions.Remove(path);
        Observe(path);
        document.DiskStamp = PageStamp(path);
        return document;
    }

    public void SavePage(PageDocument document)
    {
        document.Revision++;
        document.UpdatedAt = DateTime.UtcNow;
        WritePageSnapshot(document);
    }

    public void WritePageSnapshot(PageDocument snapshot)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync)
        {
            var path = PagePath(snapshot.PageId);
            GuardExternalWrite(path);
            var current = PageStamp(path);
            if (current != null && snapshot.DiskStamp != current && !(_localVersions.TryGetValue(path, out var local) && local.Contains(snapshot.DiskStamp)))
                throw new IOException("页面已更新，已阻止旧页面覆盖。请重新加载。");
            var clock = System.Diagnostics.Stopwatch.StartNew();
            var content = JsonSerializer.SerializeToUtf8Bytes(snapshot, PageJsonOptions);
            var bytes = _index.Pages.ContainsKey(snapshot.PageId)
                ? PageFileFormat.Compose(snapshot.PageId, _index.Keys[snapshot.PageId], StoredHead(snapshot.PageId), content)
                : content;
            var encodeMs = clock.Elapsed.TotalMilliseconds;
            AtomicWrite(path, bytes);
            System.Diagnostics.Trace.TraceInformation($"MojianSave encode {encodeMs:F1} ms, write {clock.Elapsed.TotalMilliseconds - encodeMs:F1} ms, {bytes.Length} bytes");
            if (!_localVersions.TryGetValue(path, out var versions)) _localVersions[path] = versions = new HashSet<string?>();
            versions.Add(snapshot.DiskStamp);
            snapshot.DiskStamp = PageStamp(path);
            versions.Add(snapshot.DiskStamp);
            Observe(path);
        }
    }

    internal static void WriteRecoverySnapshot(string path, PageDocument snapshot) =>
        AtomicWrite(path, JsonSerializer.SerializeToUtf8Bytes(snapshot, PageJsonOptions));

    /// <summary>The page file path (search and diagnostics); staged content for a page not yet in the library.</summary>
    public string GetPageDocumentPath(string notebookId, string pageId) { lock (_sync) return PagePath(pageId); }

    public void CommitDeletion(NotebookLibrary library, string notebookId, IReadOnlyCollection<string>? pageIds = null)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync)
        {
            ValidateLibraryUnchanged();
            var files = new List<string>();
            var removedPages = pageIds ?? _index.PageOwner.Where(pair => pair.Value == notebookId).Select(pair => pair.Key).ToArray();
            foreach (var pageId in removedPages)
            {
                if (_index.Pages.TryGetValue(pageId, out var relative)) files.Add(Absolute(relative));
                var assets = AssetDirectory(pageId);
                if (Directory.Exists(assets)) files.AddRange(Directory.EnumerateFiles(assets, "*", SearchOption.AllDirectories));
            }
            if (pageIds == null && _index.Folders.TryGetValue(notebookId, out var notebookFolder))
                files.AddRange(Directory.EnumerateFiles(Absolute(notebookFolder), "*", SearchOption.AllDirectories));
            foreach (var path in files.Distinct()) GuardExternalWrite(path);
            WorkspaceDeletionTransaction.Execute(RootDirectory, files.Distinct(), () => SaveLibraryCore(library));
        }
    }

    /// <summary>A file a crashed deletion moved aside is restored only while its page or folder still exists.</summary>
    private static bool KeepsDeletedFile(string relative, Index index)
    {
        var path = relative.Replace('\\', '/');
        var parts = path.Split('/');
        if (parts.Length >= 3 && parts[0] == FolderNames.HiddenRoot && parts[1] == "assets") return index.Pages.ContainsKey(parts[2]);
        if (path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal)) return index.Pages.Values.Contains(path);
        if (Path.GetFileName(path) == FolderNames.Descriptor) return index.Folders.Values.Contains(Path.GetDirectoryName(path)!.Replace('\\', '/'));
        return false;
    }

    // ---- Assets --------------------------------------------------------------------------------------------------

    private string AssetDirectory(string pageId) => Path.Combine(RootDirectory, FolderNames.HiddenRoot, "assets", SafeId(pageId));

    public string GetAssetDirectory(string notebookId, string pageId)
    {
        var path = AssetDirectory(pageId);
        Directory.CreateDirectory(path);
        return path;
    }

    public string ResolveAsset(string notebookId, string pageId, string assetName) => Path.Combine(AssetDirectory(pageId), SafeAssetName(assetName));

    public string ImportAsset(string notebookId, string pageId, string sourcePath)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync) return ImportAssetCore(notebookId, pageId, sourcePath);
    }

    private string ImportAssetCore(string notebookId, string pageId, string sourcePath)
    {
        if (!File.Exists(sourcePath)) throw new FileNotFoundException("找不到要插入的文件。", sourcePath);
        var assetName = $"asset-{Guid.NewGuid():N}{SafeExtension(Path.GetExtension(sourcePath))}";
        var destination = ResolveAsset(notebookId, pageId, assetName);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var temporary = destination + $".{Guid.NewGuid():N}.tmp";
        try
        {
            File.Copy(sourcePath, temporary, overwrite: false);
            using (var stream = new FileStream(temporary, FileMode.Open, FileAccess.Write, FileShare.None)) stream.Flush(true);
            File.Move(temporary, destination);
        }
        finally { File.Delete(temporary); }
        return assetName;
    }

    public string WriteAsset(string notebookId, string pageId, string extension, ReadOnlySpan<byte> bytes)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync)
        {
            var assetName = $"asset-{Guid.NewGuid():N}{SafeExtension(extension)}";
            AtomicWrite(ResolveAsset(notebookId, pageId, assetName), bytes);
            return assetName;
        }
    }

    public void CopyPageData(string sourceNotebookId, string sourcePageId, string targetNotebookId, string targetPageId)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync)
        {
            var targetAssets = AssetDirectory(targetPageId);
            if (_index.Pages.ContainsKey(targetPageId) || File.Exists(PendingPath(targetPageId)) || Directory.Exists(targetAssets))
                throw new IOException("目标页面已存在，已停止复制。");
            var sourceDocument = LoadPageCore(sourceNotebookId, sourcePageId);
            foreach (var name in sourceDocument.Objects.SelectMany(item => new[] { item.AssetName, item.SourceAssetName })
                .Where(name => !string.IsNullOrWhiteSpace(name)).Distinct())
            {
                var asset = ResolveAsset(sourceNotebookId, sourcePageId, name!);
                if (!File.Exists(asset)) throw new FileNotFoundException("源页面附件尚未同步，已停止复制。", asset);
            }
            sourceDocument.NotebookId = targetNotebookId;
            sourceDocument.PageId = targetPageId;
            sourceDocument.Revision = 0;
            sourceDocument.DiskStamp = null;
            var sourceAssets = AssetDirectory(sourcePageId);
            try
            {
                if (Directory.Exists(sourceAssets))
                    foreach (var source in Directory.EnumerateFiles(sourceAssets, "*", SearchOption.AllDirectories))
                    {
                        var destination = Path.Combine(targetAssets, Path.GetRelativePath(sourceAssets, source));
                        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                        File.Copy(source, destination, overwrite: false);
                    }
                SavePage(sourceDocument);
            }
            catch
            {
                DeletePageDataCore(targetPageId);
                throw;
            }
        }
    }

    public void DeletePageData(string notebookId, string pageId)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(RootDirectory).BeginMutation();
        lock (_sync) DeletePageDataCore(pageId);
    }

    /// <summary>Removes content that never became a library page (failed creation or copy rollback).</summary>
    private void DeletePageDataCore(string pageId)
    {
        if (File.Exists(PendingPath(pageId))) File.Delete(PendingPath(pageId));
        if (!_index.Pages.ContainsKey(pageId) && Directory.Exists(AssetDirectory(pageId))) Directory.Delete(AssetDirectory(pageId), recursive: true);
    }

    // ---- Names and atomic files --------------------------------------------------------------------------------

    private static string Combine(string parent, string name) => parent.Length == 0 ? name : parent + "/" + name;
    /// <summary>The absolute path of an index-relative path, with this platform's separators.</summary>
    private string Absolute(string relative) => Path.Combine(RootDirectory, relative.Replace('/', Path.DirectorySeparatorChar));

    private static bool IsSafeId(string id)
    {
        try { SafeId(id); return true; } catch (ArgumentException) { return false; }
    }

    public static string SafeId(string id)
    {
        // Same component rule as native sync and Android safeId: an ID that cannot sync must not name a local file.
        if (string.IsNullOrEmpty(id) || id.Length >= 240 || id[0] == '.' || id[^1] == '.' ||
            id.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not ('.' or '-' or '_')))
            throw new ArgumentException("笔记标识无效。", nameof(id));
        return id;
    }

    // Stored names must pass SafeId and native-sync paths, so a non-ASCII extension is dropped.
    internal static string SafeExtension(string? extension)
    {
        var value = (extension ?? string.Empty).TrimStart('.').ToLowerInvariant();
        return value.Length is >= 1 and <= 12 && value.All(char.IsAsciiLetterOrDigit) ? "." + value : string.Empty;
    }

    public static string SafeAssetName(string name) => SafeId(name);

    /// <summary>Where the previous version of <paramref name="path"/> is kept: hidden under the library's .mojian.</summary>
    internal static string PreviousPath(string path)
    {
        var full = Path.GetFullPath(path);
        for (var directory = Path.GetDirectoryName(full); directory != null; directory = Path.GetDirectoryName(directory))
        {
            if (Path.GetFileName(directory) == FolderNames.HiddenRoot) break;
            var hidden = Path.Combine(directory, FolderNames.HiddenRoot);
            if (Directory.Exists(hidden) && Directory.EnumerateFileSystemEntries(directory).Any(entry => Path.GetFileName(entry) == FolderNames.HiddenRoot))
            {
                var relative = Path.GetRelativePath(directory, full);
                if (!relative.StartsWith(FolderNames.HiddenRoot + Path.DirectorySeparatorChar, StringComparison.Ordinal))
                    return Path.Combine(hidden, "previous", relative + ".previous");
                break;
            }
        }
        return full + ".previous";
    }

    public static void AtomicWrite(string path, string content)
        => AtomicWrite(path, Encoding.UTF8.GetBytes(content));

    public static void AtomicWrite(string path, ReadOnlySpan<byte> content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var previous = PreviousPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(previous)!);
        // The temporary file sits beside the previous copy so a crash never leaves visible clutter in a notebook.
        var temporary = Path.Combine(Path.GetDirectoryName(previous)!, $"{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
        if (Path.GetPathRoot(temporary) != Path.GetPathRoot(path)) temporary = path + $".{Guid.NewGuid():N}.tmp";
        try
        {
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(content);
                stream.Flush(flushToDisk: true);
            }
            ReplaceWithRetry(temporary, path, previous);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    // Antivirus scanners briefly open notes without FILE_SHARE_DELETE while hashing them, so File.Replace fails
    // with a sharing violation. Those locks clear within milliseconds; retry for a bounded time. A Replace that
    // already moved the old file to its previous copy leaves `path` missing, which the next attempt completes.
    private static readonly TimeSpan ReplaceRetryBudget = TimeSpan.FromSeconds(3);

    private static void ReplaceWithRetry(string temporary, string path, string previous)
    {
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                if (File.Exists(path)) File.Replace(temporary, path, previous);
                else File.Move(temporary, path);
                return;
            }
            catch (Exception error) when (IsTransientLock(error) && elapsed.Elapsed < ReplaceRetryBudget)
            {
                Thread.Sleep(Math.Min(20 << Math.Min(attempt, 4), 200));
            }
        }
    }

    private static bool IsTransientLock(Exception error) => error switch
    {
        UnauthorizedAccessException => true,
        // ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION, ERROR_UNABLE_TO_REMOVE_REPLACED,
        // ERROR_UNABLE_TO_MOVE_REPLACEMENT(_2).
        IOException io => (io.HResult & 0xFFFF) is 32 or 33 or 1175 or 1176 or 1177,
        _ => false,
    };

    private void SaveWelcomePage(NotebookWorkspace notebook)
    {
        var page = notebook.SelectedPage();
        if (page == null) return;
        var doc = new PageDocument(notebook.Id, page.Id) { DiskStamp = PageStamp(PagePath(page.Id)) };
        doc.TextItems.Add(new TextItem
        {
            X = 72,
            Y = 110,
            Width = 520,
            Height = 160,
            FontSize = 22,
            Bold = true,
            Text = "这是你的本地工作区\n\n用鼠标或手写笔直接书写。笔记本和分区就是你选择的文件夹里的真实文件夹。",
        });
        SavePage(doc);
    }

    private static void NormalizeSelections(NotebookLibrary library)
    {
        foreach (var notebook in library.Notebooks)
        {
            if (notebook.FocusedSectionId == null && string.IsNullOrEmpty(notebook.SelectedPageId))
                notebook.FocusedSectionId = notebook.FlattenAllSections().FirstOrDefault()?.Id;
            if (notebook.FocusedSectionId != null && notebook.FindSection(notebook.FocusedSectionId) == null)
                notebook.FocusedSectionId = notebook.FlattenAllSections().FirstOrDefault()?.Id;
            if (notebook.SelectedPage() == null) notebook.SelectFirstPage(PagePaneMode.CurrentSection);
        }
    }

    internal static void RestoreMissingCurrent(string path)
    {
        var previous = PreviousPath(path);
        if (!File.Exists(path) && File.Exists(previous)) File.Copy(previous, path);
    }

    /// <summary>
    /// Only a never-used folder may be seeded. Notes, a native clone marker or sync state mean the library was lost
    /// and loading must stop instead of creating a replacement.
    /// </summary>
    public static bool IsNewWorkspace(string root) =>
        !File.Exists(Path.Combine(root, ".mojian-native-workspace")) && !Directory.Exists(Path.Combine(root, ".mojian-sync")) &&
        !Directory.Exists(Path.Combine(root, FolderNames.HiddenRoot, "assets")) &&
        !Directory.EnumerateFileSystemEntries(root).Any(entry => !Path.GetFileName(entry).StartsWith('.'));

    internal static T ReadRecoverable<T>(string path, JsonSerializerOptions options, Func<T, bool>? valid = null, string? recoveryRoot = null) where T : class
    {
        T Read(string source)
        {
            using var stream = File.OpenRead(source);
            var result = JsonSerializer.Deserialize<T>(stream, options);
            if (result == null || valid?.Invoke(result) == false) throw new JsonException("笔记数据无效。");
            return result;
        }
        try { return Read(path); }
        catch (JsonException error)
        {
            PreserveCorruptFile(path, recoveryRoot);
            var previous = PreviousPath(path);
            if (!File.Exists(previous))
                throw new InvalidDataException($"无法读取笔记数据，原文件已保留：{path}", error);
            T recovered;
            try { recovered = Read(previous); }
            catch (JsonException previousError)
            {
                throw new InvalidDataException($"当前和恢复版本均无效，已停止加载：{path}", previousError);
            }
            File.Copy(previous, path, overwrite: true);
            return recovered;
        }
    }

    // Corrupt copies mirror their relative path under a hidden directory, outside the synchronized notebooks.
    public const string CorruptRecoveryDirectory = ".mojian-corrupt.previous";

    private static void PreserveCorruptFile(string path, string? recoveryRoot)
    {
        if (!File.Exists(path)) return;
        var name = $"{Path.GetFileName(path)}.corrupt-{DateTime.UtcNow:yyyyMMdd-HHmmss}-{Guid.NewGuid():N}";
        var directory = recoveryRoot == null ? Path.GetDirectoryName(path)!
            : Path.Combine(recoveryRoot, CorruptRecoveryDirectory, Path.GetDirectoryName(Path.GetRelativePath(recoveryRoot, path)) ?? "");
        Directory.CreateDirectory(directory);
        File.Copy(path, Path.Combine(directory, name), overwrite: false);
    }
}

public sealed class PageContentMissingException(string notebookId, string pageId)
    : IOException($"页面内容尚未同步，已停止加载：{notebookId}/{pageId}");

/// <summary>The folder library is empty because it has not been received yet (just after the v2 layout was retired).</summary>
public sealed class FirstReceiveRequiredException(string root) : IOException("正在接收文件夹格式笔记")
{
    public string Root { get; } = root;
}
public sealed class WorkspaceLibraryMissingException(string root, string? detail = null)
    : IOException(detail ?? $"笔记库为空，但目录已有同步状态或数据，已停止加载：{root}")
{
    public string Root { get; } = root;
}
public sealed class PreferencesStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };

    public string FilePath { get; }

    public PreferencesStore(string? filePath = null)
    {
        FilePath = filePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Mojian",
            "preferences.json");
    }

    public AppPreferences Load()
    {
        WorkspaceStore.RestoreMissingCurrent(FilePath);
        if (!File.Exists(FilePath)) return new AppPreferences();
        return WorkspaceStore.ReadRecoverable<AppPreferences>(FilePath, JsonOptions);
    }

    public void Save(AppPreferences preferences) => WorkspaceStore.AtomicWrite(FilePath, JsonSerializer.Serialize(preferences, JsonOptions));
}
