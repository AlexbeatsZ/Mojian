using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Collections.Concurrent;

namespace Notein_win.Services;

public sealed class NativeSyncClient : IDisposable
{
    private readonly string _root;
    private readonly string _directory;
    private readonly HttpClient _http;
    private readonly FileStream _writerLock;
    private readonly SemaphoreSlim _running = new(1, 1);
    private readonly NativeSyncMutationEpoch _localMutations;
    private readonly ConcurrentDictionary<string, SyncFile> _cache = new(StringComparer.Ordinal);
    private readonly FileSystemWatcher _watcher;
    private readonly NativeSyncState _state;
    private readonly NativeSyncAddressHandler? _addresses;
    private readonly bool _recoveryOnly;
    public NativeSyncStatus Snapshot { get; private set; } = new(NativeSyncPhase.Pending, "本地已保存，等待同步");
    public string Status => Snapshot.Message;
    public event Action<string>? StatusChanged;
    public event Action<NativeSyncStatus>? SnapshotChanged;
    public string RecoveryDirectory => Path.Combine(_directory, "conflicts");
    public bool HasPendingIncoming => File.Exists(IncomingPath);
    private string StatePath => Path.Combine(_directory, "state.json");
    private string OutboxPath => Path.Combine(_directory, "outbox.json");
    private string IncomingPath => Path.Combine(_directory, "incoming.json");
    private string DeferredPath => Path.Combine(_directory, "deferred-incoming.json");
    private NativeSyncIncoming? ResumeIncoming()
    {
        if (File.Exists(DeferredPath)) File.Move(DeferredPath, IncomingPath);
        return HasPendingIncoming ? NativeSyncStorage.Read<NativeSyncIncoming>(IncomingPath) : null;
    }
    private string BlobPath(string hash) => Path.Combine(_directory, "blobs", hash);
    private string LocalPath(string path)
    {
        if (!ValidPath(path)) throw new IOException("无效同步路径");
        return Path.Combine(_root, path.Replace('/', Path.DirectorySeparatorChar));
    }
    public static bool ValidPath(string path) => NativeSyncStorage.ValidPath(path);

    public NativeSyncClient(string root, NativeSyncConfiguration configuration)
        : this(root, configuration.Endpoint, configuration.Token, new NativeSyncAddressHandler(configuration)) { }

    public NativeSyncClient(string root, string endpoint, string token, HttpMessageHandler? handler = null)
        : this(root, endpoint, token, handler, recoveryOnly: false) { }

    public static NativeSyncClient ForLocalRecovery(string root)
    {
        var state = NativeSyncStorage.Read<NativeSyncState>(Path.Combine(root, ".mojian-sync", "state.json"));
        return new NativeSyncClient(root, state.Endpoint, "", null, recoveryOnly: true);
    }

    private NativeSyncClient(string root, string endpoint, string token, HttpMessageHandler? handler, bool recoveryOnly)
    {
        _recoveryOnly = recoveryOnly;
        _addresses = handler as NativeSyncAddressHandler;
        _root = Path.GetFullPath(root);
        _localMutations = NativeSyncMutationEpoch.ForRoot(_root);
        RequireDedicatedWorkspace(_root);
        var uri = new Uri(endpoint.TrimEnd('/') + "/", UriKind.Absolute);
        if (uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback)) throw new ArgumentException("同步地址必须使用 HTTPS");
        if (!string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment)) throw new ArgumentException("同步地址不能包含凭据或查询参数");
        if (!recoveryOnly && (string.IsNullOrWhiteSpace(token) || token.Contains('\r') || token.Contains('\n'))) throw new ArgumentException("请配置同步令牌");
        _directory = Path.Combine(_root, ".mojian-sync");
        Directory.CreateDirectory(Path.Combine(_directory, "blobs"));
        _writerLock = new FileStream(Path.Combine(_directory, "client.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        try
        {
            _state = File.Exists(StatePath) ? NativeSyncStorage.Read<NativeSyncState>(StatePath) : new();
            if (_state.Format != 1 || !NativeSyncStorage.ValidId(_state.DeviceId) || _state.Endpoint == null || _state.WorkspaceId == null)
                throw new IOException("同步记录版本或设备标识无效");
            NativeSyncStorage.ValidateEntries(_state.Files, _state.Revision);
            if (_state.Endpoint.Length > 0 && _state.Endpoint != uri.AbsoluteUri) throw new IOException("此副本已绑定其他服务器，请使用新的独立副本");
            _state.Endpoint = uri.AbsoluteUri;
            PersistState();
            _http = handler == null ? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) : new HttpClient(handler);
            _http.Timeout = TimeSpan.FromMinutes(10);
            _http.BaseAddress = uri;
            if (!recoveryOnly) _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            _watcher = new FileSystemWatcher(_root) { IncludeSubdirectories = true,
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.DirectoryName | NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.CreationTime };
            _watcher.Changed += Invalidate;
            _watcher.Created += Invalidate;
            _watcher.Deleted += Invalidate;
            _watcher.Renamed += InvalidateRename;
            _watcher.Error += (_, _) => _cache.Clear();
            _watcher.EnableRaisingEvents = true;
            if (_state.ConflictDirectory != null) SetStatus(NativeSyncPhase.Conflict, "存在同步冲突，双方副本已保留");
        }
        catch { _watcher?.Dispose(); _http?.Dispose(); _writerLock.Dispose(); throw; }
    }

    private void Invalidate(object sender, FileSystemEventArgs change)
    {
        var path = Path.GetRelativePath(_root, change.FullPath).Replace('\\', '/');
        if (ValidPath(path)) _cache.TryRemove(path, out _);
    }

    private void InvalidateRename(object sender, RenamedEventArgs change)
    {
        Invalidate(sender, change);
        var previous = Path.GetRelativePath(_root, change.OldFullPath).Replace('\\', '/');
        if (ValidPath(previous)) _cache.TryRemove(previous, out _);
        var current = Path.GetRelativePath(_root, change.FullPath).Replace('\\', '/');
        if (!current.StartsWith(".mojian-sync/", StringComparison.Ordinal) && Directory.Exists(change.FullPath)) _cache.Clear();
    }

    public static void RequireDedicatedWorkspace(string root)
    {
        root = Path.GetFullPath(root);
        NativeSyncStorage.NoLinks(root);
        if (!File.Exists(Path.Combine(root, ".mojian-native-workspace"))) throw new IOException("请先创建原生同步专用副本");
        RejectSyncthingAncestors(root);
        var privateDirectory = Path.Combine(root, ".mojian-sync");
        if (Directory.Exists(privateDirectory))
            foreach (var entry in Directory.EnumerateFileSystemEntries(privateDirectory, "*", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = 0 }))
                if ((File.GetAttributes(entry) & FileAttributes.ReparsePoint) != 0) throw new IOException("同步恢复目录包含链接");
    }

    private static void RejectSyncthingAncestors(string root)
    {
        for (var parent = new DirectoryInfo(root); parent != null; parent = parent.Parent)
            if (new[] { ".stfolder", ".stignore", ".stversions" }.Any(name => File.Exists(Path.Combine(parent.FullName, name)) || Directory.Exists(Path.Combine(parent.FullName, name))))
                throw new IOException("此路径属于 Syncthing 文件夹，请选择其外部的独立副本");
    }

    public static void CloneWorkspace(string source, string target)
    {
        source = Path.GetFullPath(source);
        target = Path.GetFullPath(target);
        NativeSyncStorage.NoLinks(source);
        NativeSyncStorage.NoLinks(target);
        RejectSyncthingAncestors(target);
        if (Directory.Exists(target) || File.Exists(target) || target.Equals(source, StringComparison.OrdinalIgnoreCase) ||
            target.StartsWith(source.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new IOException("副本路径必须是原笔记库之外尚不存在的新目录");
        var mutations = NativeSyncMutationEpoch.ForRoot(source);
        var epoch = mutations.Capture() ?? throw new IOException("源笔记库正在修改，请稍后重试");
        var items = NativeSyncStorage.EnumerateItems(source).Where(item => item.Failure == null).ToArray();
        if (!items.Any(item => item.Directory && !item.Path.Contains('/') && !item.Path.StartsWith('.')))
            throw new IOException("源目录没有笔记本文件夹");
        var files = items.Where(item => !item.Directory).ToDictionary(item => item.Path, item => NativeSyncStorage.Hash(Path.Combine(source, item.Path)), StringComparer.Ordinal);
        Directory.CreateDirectory(target);
        foreach (var folder in items.Where(item => item.Directory)) Directory.CreateDirectory(Path.Combine(target, folder.Path));
        foreach (var pair in files)
        {
            var output = Path.Combine(target, pair.Key);
            NativeSyncStorage.CopyDurable(Path.Combine(source, pair.Key), output);
            if (NativeSyncStorage.Hash(output) != pair.Value) throw new IOException("复制期间原笔记发生变化，请重试到新目录");
        }
        var settings = Path.Combine(source, ".mojian", "workspace-settings.json");
        if (File.Exists(settings)) NativeSyncStorage.CopyDurable(settings, Path.Combine(target, ".mojian", "workspace-settings.json"));
        var finalFiles = NativeSyncStorage.EnumerateItems(source).Where(item => item.Failure == null && !item.Directory).Select(item => item.Path).ToHashSet(StringComparer.Ordinal);
        if (!finalFiles.SetEquals(files.Keys) || files.Any(pair => NativeSyncStorage.Hash(Path.Combine(source, pair.Key)) != pair.Value))
            throw new IOException("复制期间原笔记发生变化，请重试到新目录");
        if (!mutations.PublishIfUnchanged(epoch, () => NativeSyncStorage.Save(Path.Combine(target, ".mojian-native-workspace"), Guid.NewGuid().ToString("N"))))
            throw new IOException("复制期间原笔记发生变化，未启用此副本，请重试到新目录");
        RequireDedicatedWorkspace(target);
    }

    public Dictionary<string, SyncFile> Scan()
    {
        return ScanStable() ?? throw new IOException("本地变更事务进行中，请稍后重试");
    }

    private Dictionary<string, SyncFile>? ScanStable()
    {
        var epoch = _localMutations.Capture();
        if (epoch == null) { _cache.Clear(); return null; }
        try
        {
            var files = ScanCore();
            if (_localMutations.Capture() == epoch) return files;
        }
        catch (IOException) when (_localMutations.Capture() != epoch) { }
        _cache.Clear();
        return null;
    }

    /// <summary>Items skipped by the latest scan or plan, with their reasons; every other item keeps synchronizing.</summary>
    public IReadOnlyDictionary<string, string> FailedItems =>
        _scanFailures.Concat(_failures).GroupBy(pair => pair.Key).ToDictionary(group => group.Key, group => group.Last().Value, StringComparer.Ordinal);
    private Dictionary<string, string> _failures = new(StringComparer.Ordinal);
    private Dictionary<string, string> _scanFailures = new(StringComparer.Ordinal);

    private Dictionary<string, SyncFile> ScanCore()
    {
        var files = new Dictionary<string, SyncFile>(StringComparer.Ordinal);
        var failures = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var item in NativeSyncStorage.EnumerateItems(_root))
        {
            if (item.Failure != null)
            {
                failures[item.Path] = item.Failure;
                // A skipped item keeps its acknowledged identity, so it is neither uploaded as new nor deleted remotely.
                foreach (var known in _state.Files.Where(pair => pair.Key == item.Path || pair.Key.StartsWith(item.Path + "/", StringComparison.Ordinal)))
                    if (known.Value.Hash != null) files[known.Key] = new(known.Value.Hash, -1, -1);
                continue;
            }
            if (item.Directory) { files[item.Path] = new(NativeSyncIdentity.Directory, 0, 0); continue; }
            var absolute = LocalPath(item.Path);
            var before = Stamp(absolute);
            if (before == null) continue;
            if (_cache.TryGetValue(item.Path, out var cached) && (cached.Length, cached.Modified) == before) files[item.Path] = cached;
            else
            {
                var hash = NativeSyncStorage.Hash(absolute);
                if (hash == null) continue;
                if (Stamp(absolute) != before)
                {
                    // Still being written: keep its last acknowledged identity and retry on the next pass.
                    failures[item.Path] = "文件仍在写入，下一轮重试";
                    if (_state.Files.TryGetValue(item.Path, out var acknowledged) && acknowledged.Hash != null) files[item.Path] = new(acknowledged.Hash, -1, -1);
                    continue;
                }
                files[item.Path] = _cache[item.Path] = new(hash, before.Value.Length, before.Value.Modified);
            }
        }
        // Two live local names that differ only by case cannot coexist on the server or other devices.
        foreach (var group in files.Keys.GroupBy(path => path, StringComparer.OrdinalIgnoreCase).Where(group => group.Count() > 1))
            foreach (var path in group)
            {
                failures[path] = $"与 {string.Join("、", group.Where(other => other != path))} 只差大小写，其他设备无法同时保存";
                if (_state.Files.TryGetValue(path, out var acknowledged) && acknowledged.Hash != null) files[path] = new(acknowledged.Hash, -1, -1);
                else files.Remove(path);
            }
        foreach (var path in _cache.Keys.Except(files.Keys).ToArray()) _cache.TryRemove(path, out _);
        _scanFailures = failures;
        return files;
    }
    private static (long Length, long Modified)? Stamp(string path) => File.Exists(path) ? (new FileInfo(path).Length, File.GetLastWriteTimeUtc(path).Ticks) : null;
    private static string? Hash(Dictionary<string, SyncEntry> files, string path) => files.GetValueOrDefault(path)?.Hash;
    private void PersistState() => NativeSyncStorage.Save(StatePath, _state);
    private void SetStatus(NativeSyncPhase phase, string message, int pending = 0)
    {
        Snapshot = new(phase, message, pending, _state.ConflictDirectory, _state.LastSuccessUtc);
        StatusChanged?.Invoke(message);
        SnapshotChanged?.Invoke(Snapshot);
    }
    private void Capture(string path, string hash)
    {
        var target = BlobPath(hash);
        if (NativeSyncStorage.Hash(target) == hash) return;
        NativeSyncStorage.CopyDurable(LocalPath(path), target);
        if (NativeSyncStorage.Hash(target) != hash) throw new IOException("捕获同步快照时本地已变化，请稍后重试");
    }

    private (Dictionary<string, SyncFile> Local, NativeSyncOutbox Outbox)? CaptureOutgoing(CancellationToken cancellation)
    {
        var epoch = _localMutations.Capture();
        if (epoch == null) { _cache.Clear(); return null; }
        try
        {
            cancellation.ThrowIfCancellationRequested();
            var local = ScanCore();
            // A missing or unmounted library must never publish the deletion of every notebook.
            if (!local.Any(pair => pair.Value.Hash == NativeSyncIdentity.Directory && !pair.Key.Contains('/') && !pair.Key.StartsWith('.')) &&
                _state.Files.Any(pair => pair.Value.Hash == NativeSyncIdentity.Directory && !pair.Key.Contains('/') && !pair.Key.StartsWith('.')))
                throw new IOException("本地笔记库缺失，已阻止同步删除");
            var changes = local.Keys.Union(_state.Files.Keys).Where(path => !NativeSyncStorage.IsLegacy(path) && local.GetValueOrDefault(path)?.Hash != Hash(_state.Files, path))
                .Select(path => new SyncChange(path, _state.Files.GetValueOrDefault(path)?.Version ?? 0, local.GetValueOrDefault(path)?.Hash)).ToList();
            foreach (var change in changes.Where(change => NativeSyncIdentity.IsBlob(change.Hash)))
            {
                cancellation.ThrowIfCancellationRequested();
                if (_localMutations.Capture() != epoch) { _cache.Clear(); return null; }
                Capture(change.Path, change.Hash!);
            }
            var outbox = new NativeSyncOutbox { DeviceId = _state.DeviceId, WorkspaceId = _state.WorkspaceId, Changes = changes };
            var published = _localMutations.PublishIfUnchanged(epoch.Value, () =>
            {
                cancellation.ThrowIfCancellationRequested();
                if (changes.Count > 0) NativeSyncStorage.Save(OutboxPath, outbox);
                else File.Delete(OutboxPath);
            });
            if (published) return (local, outbox);
        }
        catch (IOException) when (_localMutations.Capture() != epoch) { }
        _cache.Clear();
        return null;
    }
    private async Task<SyncManifest> Manifest(CancellationToken cancellation)
    {
        var manifest = await new NativeSyncManifestFeed(_http, _directory).ReadAsync(_state.WorkspaceId, _state.Revision, cancellation).ConfigureAwait(false);
        NativeSyncStorage.ValidateEntries(manifest.Files, manifest.Revision);
        if (string.IsNullOrEmpty(manifest.WorkspaceId) || (_state.WorkspaceId.Length > 0 && _state.WorkspaceId != manifest.WorkspaceId)) throw new IOException("服务器身份变化，已暂停同步，请检查备份");
        if (manifest.Revision < _state.Revision) throw new IOException("服务器版本回退，已暂停同步，请检查备份");
        foreach (var pair in _state.Files)
            if (!manifest.Files.TryGetValue(pair.Key, out var current) || current.Version < pair.Value.Version ||
                (current.Version == pair.Value.Version && current.Hash != pair.Value.Hash)) throw new IOException("服务器丢失已确认版本，已暂停同步");
        _state.WorkspaceId = manifest.WorkspaceId;
        PersistState();
        return manifest;
    }

    public async Task<bool> RecoverPendingIncomingAsync(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation = default)
    {
        if (!await _running.WaitAsync(0, cancellation).ConfigureAwait(false)) return false;
        try
        {
            RequireDedicatedWorkspace(_root);
            if (!HasPendingIncoming) return true;
            return await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { SetStatus(NativeSyncPhase.Paused, "接收恢复已暂停，暂时不能打开笔记库"); return false; }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        { SetStatus(NativeSyncPhase.Error, "接收恢复未完成，暂时不能打开笔记库：" + error.Message); return false; }
        finally { _running.Release(); }
    }

    /// <summary>
    /// Moves the v2 layout (<c>library.json</c>, <c>notebooks/</c>) left from before the folder library into
    /// <c>.mojian/legacy-v2/&lt;time&gt;/</c>, once and offline (docs/design/folder-library.md "Migration"). Nothing is deleted.
    /// Returns the v2 files whose bytes differ from the last synchronized version: those edits never reached the server and
    /// stay only in that folder, so the caller must show them.
    /// </summary>
    public IReadOnlyList<string> RetireLegacyLayout()
    {
        var index = Path.Combine(_root, "library.json");
        var notebooks = Path.Combine(_root, "notebooks");
        var legacyFolder = Directory.Exists(notebooks) && !File.Exists(Path.Combine(notebooks, FolderNames.Descriptor));
        if (!File.Exists(index) && !legacyFolder) return [];
        if (!File.Exists(StatePath)) throw new IOException("旧格式笔记库没有同步记录，无法确认已上传；请先用 tools/folder-library 转换");
        var files = (File.Exists(index) ? new[] { index } : []).Concat(legacyFolder ? Directory.EnumerateFiles(notebooks, "*", SearchOption.AllDirectories) : []);
        var unsynced = files.Select(file => Path.GetRelativePath(_root, file).Replace('\\', '/'))
            .Where(path => NativeSyncStorage.IsLegacy(path) && _state.Files.GetValueOrDefault(path)?.Hash != NativeSyncStorage.Hash(Path.Combine(_root, path)))
            .Order(StringComparer.Ordinal).ToList();
        var target = Path.Combine(_root, FolderNames.HiddenRoot, "legacy-v2", DateTime.UtcNow.ToString("yyyy-MM-ddTHH-mm-ssZ"));
        Directory.CreateDirectory(target);
        if (unsynced.Count > 0) File.WriteAllLines(Path.Combine(target, "unsynced.txt"), unsynced);
        if (File.Exists(index)) File.Move(index, Path.Combine(target, "library.json"));
        if (File.Exists(index + ".previous")) File.Move(index + ".previous", Path.Combine(target, "library.json.previous"));
        if (legacyFolder) Directory.Move(notebooks, Path.Combine(target, "notebooks"));
        return unsynced;
    }

    public bool RecoverPendingIncoming() => RecoverPendingIncomingAsync(action => { action(); return Task.FromResult(true); }).GetAwaiter().GetResult();

    public async Task SyncAsync(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation = default)
    {
        if (_recoveryOnly) throw new InvalidOperationException("本地恢复客户端仅允许重放已下载的接收记录");
        if (!await _running.WaitAsync(0, cancellation).ConfigureAwait(false)) return;
        try
        {
            for (var index = 0; index < (_addresses?.Count ?? 1); index++)
            {
                _addresses?.Select(index);
                if (!await SyncAddressAsync(applyOnIdle, cancellation).ConfigureAwait(false)) break;
            }
        }
        finally { _running.Release(); }
    }

    private async Task<bool> SyncAddressAsync(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation)
    {
        try
        {
            RequireDedicatedWorkspace(_root);
            if (HasPendingIncoming && !await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false)) return false;
            var activeConflicts = ActiveConflictPaths();
            var heldPaths = new HashSet<string>(activeConflicts, StringComparer.Ordinal);
            if (File.Exists(DeferredPath))
                heldPaths.UnionWith(NativeSyncStorage.Read<NativeSyncIncoming>(DeferredPath).Files.Keys);
            SetStatus(NativeSyncPhase.Checking, "正在检查同步");
            var previous = File.Exists(OutboxPath) ? NativeSyncStorage.Read<NativeSyncOutbox>(OutboxPath) : null;
            if (previous is { Submitted: true })
            {
                ValidateOutbox(previous);
                if (_addresses != null) _ = await Manifest(cancellation).ConfigureAwait(false);
                if (!await Commit(previous, cancellation).ConfigureAwait(false)) return false;
            }
            var prepared = await Task.Run(() => CaptureOutgoing(cancellation), cancellation).ConfigureAwait(false);
            if (prepared == null)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地变更事务进行中，等待稳定快照后同步"); return false; }
            var (local, outbox) = prepared.Value;
            var failures = new Dictionary<string, string>(_scanFailures, StringComparer.Ordinal);
            bool IsHeld(string path) => heldPaths.Contains(path) || failures.Keys.Any(failed => path.StartsWith(failed + "/", StringComparison.Ordinal) || path == failed);
            var outgoingCount = outbox.Changes.Count;
            SetStatus(NativeSyncPhase.Pending, $"本地已保存，待上传 {outgoingCount} 项", outgoingCount);
            var remote = await Manifest(cancellation).ConfigureAwait(false);
            var incoming = new Dictionary<string, NativeSyncIncomingFile>(StringComparer.Ordinal);
            var conflicts = new List<string>();
            var changes = new List<SyncChange>();
            foreach (var path in local.Keys.Union(_state.Files.Keys).Union(remote.Files.Keys))
            {
                if (IsHeld(path) || NativeSyncStorage.IsLegacy(path)) continue;
                var localHash = local.GetValueOrDefault(path)?.Hash;
                switch (NativeSyncDecisions.DecideEntry(_state.Files.GetValueOrDefault(path), localHash, remote.Files.GetValueOrDefault(path)))
                {
                    case NativeSyncDecision.Equal:
                        if (remote.Files.TryGetValue(path, out var equal)) _state.Files[path] = equal;
                        break;
                    case NativeSyncDecision.Conflict: conflicts.Add(path); break;
                    case NativeSyncDecision.Failed: failures[path] = "同一路径一边是文件、一边是文件夹，请保留其中一个"; break;
                    case NativeSyncDecision.Upload: changes.Add(new(path, remote.Files.GetValueOrDefault(path)?.Version ?? 0, localHash)); break;
                    case NativeSyncDecision.Receive:
                        if (remote.Files.TryGetValue(path, out var entry)) incoming[path] = new(localHash, entry);
                        break;
                }
            }
            foreach (var path in conflicts.ToList())
            {
                var entry = remote.Files.GetValueOrDefault(path);
                if (entry == null) continue;
                var merged = await MergeConflict(path, Hash(_state.Files, path), local.GetValueOrDefault(path)?.Hash, entry.Hash, cancellation).ConfigureAwait(false);
                if (merged == null) continue;
                conflicts.Remove(path);
                incoming[path] = new(local.GetValueOrDefault(path)?.Hash, entry, merged == RemoteMerge ? null : merged);
            }
            var blocked = heldPaths.Concat(conflicts).ToHashSet(StringComparer.Ordinal);
            if (blocked.Count > 0)
            {
                // Page identities come from local heads or downloaded blobs; a held page holds only its own group.
                foreach (var entry in incoming.Values.Where(entry => NativeSyncIdentity.IsBlob(entry.Target.Hash) && entry.Content == null))
                    await Download(entry.Target.Hash!, cancellation).ConfigureAwait(false);
                var dependencies = PageDependencies(local.Keys.Union(remote.Files.Keys).Union(heldPaths), path => remote.Files.GetValueOrDefault(path)?.Hash);
                var blockedGroups = blocked.Select(dependencies.Group).ToHashSet(StringComparer.Ordinal);
                changes.RemoveAll(change => blockedGroups.Contains(dependencies.Group(change.Path)));
                foreach (var path in incoming.Keys.Where(path => blockedGroups.Contains(dependencies.Group(path))).ToArray()) incoming.Remove(path);
                if (conflicts.Count > 0) await PreserveConflicts(conflicts, remote, cancellation, expectedLocal: local).ConfigureAwait(false);
            }
            // Large imports go up in server-sized batches; the next pass continues with the rest (deletions first).
            changes = changes.OrderBy(change => change.Hash == null ? 0 : 1).ToList();
            var truncated = changes.Count > BatchLimit;
            if (truncated) changes = changes.Take(BatchLimit).ToList();
            _failures = failures;
            outbox.WorkspaceId = remote.WorkspaceId;
            outbox.Changes = changes;
            if (changes.Count > 0)
            {
                NativeSyncStorage.Save(OutboxPath, outbox);
                if (!await Commit(outbox, cancellation).ConfigureAwait(false)) return false;
            }
            else File.Delete(OutboxPath);
            if (incoming.Count > 0)
            {
                foreach (var entry in incoming.Values.Where(entry => NativeSyncIdentity.IsBlob(entry.Target.Hash) && entry.Content == null))
                    await Download(entry.Target.Hash!, cancellation).ConfigureAwait(false);
                var retained = ResumeIncoming();
                if (retained != null)
                    foreach (var pair in retained.Files) incoming.Add(pair.Key, pair.Value);
                NativeSyncStorage.Save(IncomingPath, new NativeSyncIncoming(remote.WorkspaceId, Math.Max(remote.Revision, retained?.Revision ?? 0), incoming, retained?.ReviewPaths));
                if (!await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false)) return false;
            }
            _state.Revision = Math.Max(_state.Revision, remote.Revision);
            PersistState();
            PruneTransferBlobs();
            var unresolved = ActiveConflictPaths().Count;
            if (unresolved > 0 || HasPendingIncoming || File.Exists(DeferredPath))
            {
                SetStatus(NativeSyncPhase.Conflict, $"同步冲突 {unresolved} 项，独立笔记已检查", unresolved);
                return false;
            }
            var remaining = await Task.Run(ScanStable, cancellation).ConfigureAwait(false);
            if (remaining == null)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地变更事务进行中，等待稳定快照后同步"); return false; }
            var pending = remaining.Keys.Union(_state.Files.Keys).Count(path => remaining.GetValueOrDefault(path)?.Hash != Hash(_state.Files, path) && !IsHeld(path));
            if (truncated) pending = Math.Max(pending, 1);
            if (failures.Count > 0)
            {
                var (failedPath, reason) = failures.First();
                SetStatus(NativeSyncPhase.Error, $"其他内容已同步；{failures.Count} 项无法同步：{failedPath}（{reason}）", pending);
                return truncated;
            }
            if (pending == 0)
            {
                _state.LastSuccessUtc = DateTimeOffset.UtcNow;
                PersistState();
            }
            SetStatus(pending == 0 ? NativeSyncPhase.Synced : NativeSyncPhase.Pending,
                pending == 0 ? (_addresses == null ? "已同步" : "已同步 · " + new Uri(_addresses.CurrentAddress).Authority) : $"本地已保存，待上传 {pending} 项", pending);
        }
        catch (OperationCanceledException) { SetStatus(cancellation.IsCancellationRequested ? NativeSyncPhase.Paused : NativeSyncPhase.Offline, "同步已暂停，待同步内容已保留", Snapshot.PendingFiles); return !cancellation.IsCancellationRequested; }
        catch (HttpRequestException error)
        {
            var protocolFailure = error.HttpRequestError is HttpRequestError.SecureConnectionError or HttpRequestError.UserAuthenticationError or HttpRequestError.HttpProtocolError or HttpRequestError.InvalidResponse;
            var offline = !protocolFailure && (error.StatusCode == null || (int)error.StatusCode >= 500 || error.StatusCode == HttpStatusCode.RequestTimeout);
            SetStatus(offline && _state.ConflictDirectory != null ? NativeSyncPhase.Conflict : offline ? NativeSyncPhase.Offline : NativeSyncPhase.Error,
                offline ? (_state.ConflictDirectory != null ? "离线，未处理的冲突与待同步任务已保留" : "离线或服务暂不可用，本地内容和待同步任务已保留") :
                    protocolFailure ? "HTTPS 安全校验或协议错误，请检查同步配置" : $"同步服务拒绝请求（{(int?)error.StatusCode}），请检查配置", Snapshot.PendingFiles);
            return true;
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        { SetStatus(NativeSyncPhase.Error, "同步已暂停，本地内容已保留：" + error.Message, Snapshot.PendingFiles); }
        return false;
    }

    internal int BatchLimit { get; init; } = 20000;

    /// <summary>Page id and PDF owner of each page path, read from the local file head or a downloaded remote blob.</summary>
    private NativeSyncDependencies PageDependencies(IEnumerable<string> paths, Func<string, string?> remoteHash)
    {
        var pages = new List<(string, string?, string?)>();
        foreach (var path in paths.Distinct().Where(path => path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal)))
        {
            string? id = null, owner = null;
            foreach (var candidate in new[] { LocalPath(path), NativeSyncIdentity.IsBlob(remoteHash(path)) ? BlobPath(remoteHash(path)!) : null })
            {
                if (candidate == null || !File.Exists(candidate)) continue;
                try
                {
                    var head = PageFileFormat.ReadHead(candidate);
                    id ??= head.Id;
                    owner ??= head.Page?["pdfPage"]?["sourceOwnerPageId"]?.GetValue<string>();
                }
                catch (Exception error) when (error is InvalidDataException or JsonException or InvalidOperationException) { }
            }
            pages.Add((path, id, owner));
        }
        return NativeSyncDependencies.FromPages(pages);
    }

    // Staged blobs only back the outbox, incoming journal and conflict capture; once none remain
    // they duplicate acknowledged content (Android clears its transfer directory at this point).
    private void PruneTransferBlobs()
    {
        if (File.Exists(OutboxPath) || HasPendingIncoming || File.Exists(DeferredPath) || _state.ConflictDirectory != null) return;
        foreach (var blob in Directory.EnumerateFiles(Path.Combine(_directory, "blobs")))
        {
            try { File.Delete(blob); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private void ValidateOutbox(NativeSyncOutbox outbox)
    {
        if (outbox.WorkspaceId != _state.WorkspaceId || outbox.DeviceId != _state.DeviceId || !NativeSyncStorage.ValidId(outbox.OperationId) ||
            outbox.Changes == null || outbox.Changes.Count is < 1 or > 20000 ||
            outbox.Changes.Any(change => change == null || !ValidPath(change.Path) || NativeSyncStorage.IsLegacy(change.Path) || !NativeSyncStorage.ValidHash(change.Hash) || change.ExpectedVersion < 0 || change.ExpectedVersion > 9007199254740991L) ||
            outbox.Changes.Select(change => change.Path).Distinct(StringComparer.Ordinal).Count() != outbox.Changes.Count ||
            // A case-only rename retires one spelling and creates another in the same batch.
            outbox.Changes.Where(change => change.Hash != null).Select(change => change.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() !=
                outbox.Changes.Count(change => change.Hash != null))
            throw new IOException("同步待提交记录损坏，请保留恢复目录");
    }
    private async Task<bool> Commit(NativeSyncOutbox outbox, CancellationToken cancellation)
    {
        RequireDedicatedWorkspace(_root);
        ValidateOutbox(outbox);
        SetStatus(NativeSyncPhase.Uploading, $"正在上传 {outbox.Changes.Count} 项", outbox.Changes.Count);
        if (!outbox.Submitted)
        {
            foreach (var hash in outbox.Changes.Where(change => NativeSyncIdentity.IsBlob(change.Hash)).Select(change => change.Hash!).Distinct())
            {
                if (NativeSyncStorage.Hash(BlobPath(hash)) != hash) throw new IOException("待提交快照缺失或损坏，请保留恢复目录");
                await new NativeSyncBlobTransfer(_http).UploadAsync(hash, BlobPath(hash), cancellation).ConfigureAwait(false);
            }
            outbox.Submitted = true;
            NativeSyncStorage.Save(OutboxPath, outbox);
        }
        RequireDedicatedWorkspace(_root);
        using var result = await _http.PostAsJsonAsync("commit", new { outbox.WorkspaceId, outbox.DeviceId, outbox.OperationId, outbox.Changes }, NativeSyncStorage.Json, cancellation).ConfigureAwait(false);
        if (result.StatusCode == HttpStatusCode.Conflict)
        {
            using var error = JsonDocument.Parse(await result.Content.ReadAsStringAsync(cancellation).ConfigureAwait(false));
            if (error.RootElement.TryGetProperty("error", out var code) && code.GetString() == "version_conflict")
            {
                // This response proves the CAS was rejected atomically. Recompute the three-way
                // decision next pass. Lost responses and other 409s must keep the original outbox.
                File.Delete(OutboxPath);
                SetStatus(NativeSyncPhase.Pending, "远端已更新，下次同步重新检查；本地修改已保留", outbox.Changes.Count);
                return false;
            }
            SetStatus(NativeSyncPhase.Error, "同步提交被拒绝，原操作已保留，请检查服务器身份或操作记录", outbox.Changes.Count);
            return false;
        }
        result.EnsureSuccessStatusCode();
        var receipt = await result.Content.ReadFromJsonAsync<SyncReceipt>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false) ?? throw new IOException("同步回执缺失");
        NativeSyncStorage.ValidateEntries(receipt.Files, receipt.Revision);
        if (receipt.WorkspaceId != outbox.WorkspaceId || receipt.OperationId != outbox.OperationId || receipt.Files.Count != outbox.Changes.Count ||
            outbox.Changes.Any(change => !receipt.Files.TryGetValue(change.Path, out var entry) || entry.Hash != change.Hash || entry.Version != receipt.Revision || entry.Version <= change.ExpectedVersion))
            throw new IOException("同步回执与待提交内容不一致");
        foreach (var pair in receipt.Files) _state.Files[pair.Key] = pair.Value;
        _state.Revision = Math.Max(_state.Revision, receipt.Revision);
        PersistState();
        File.Delete(OutboxPath);
        return true;
    }

    private Task Download(string hash, CancellationToken cancellation)
    {
        if (!NativeSyncStorage.ValidHash(hash)) throw new IOException("无效同步内容地址");
        return new NativeSyncBlobTransfer(_http).DownloadAsync(hash, BlobPath(hash), cancellation);
    }

    private Task<bool> ApplyIncoming(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation) =>
        Task.Run(() => ApplyIncomingCore(applyOnIdle, cancellation), cancellation);

    private async Task<bool> ApplyIncomingCore(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation)
    {
        var plan = NativeSyncStorage.Read<NativeSyncIncoming>(IncomingPath);
        if (plan.WorkspaceId != _state.WorkspaceId || plan.Revision < 0 || plan.Revision > 9007199254740991L || plan.Files == null || plan.Files.Count == 0 ||
            plan.Files.Any(pair => !ValidPath(pair.Key) || pair.Value == null || pair.Value.Target == null || !NativeSyncStorage.ValidHash(pair.Value.ExpectedHash)))
            throw new IOException("接收恢复记录损坏");
        NativeSyncStorage.ValidateEntries(plan.Files.ToDictionary(pair => pair.Key, pair => pair.Value.Target), plan.Revision);
        if (plan.Files.Values.Any(entry => entry.Content != null && !NativeSyncStorage.ValidHash(entry.Content))) throw new IOException("接收恢复记录损坏");
        foreach (var hash in plan.Files.Values.Where(entry => !entry.KeepLocal).Select(entry => entry.WantedHash).Where(NativeSyncIdentity.IsBlob).OfType<string>().Distinct())
            if (NativeSyncStorage.Hash(BlobPath(hash)) != hash) throw new IOException("接收恢复快照缺失，请保留目录");
        SetStatus(NativeSyncPhase.Receiving, $"正在接收 {plan.Files.Count} 项");
        var conflicts = new List<string>();
        var plannedReview = (plan.ReviewPaths ?? []).ToHashSet(StringComparer.Ordinal);
        if (plannedReview.Any(path => !plan.Files.ContainsKey(path))) throw new IOException("无效冲突审阅记录");
        var prepared = new List<PreparedIncoming>();
        var rollback = new List<PreparedIncoming>();
        var stagedState = Path.Combine(_directory, "incoming-state." + Guid.NewGuid().ToString("N") + ".tmp");
        var executed = false;
        try
        {
            RequireDedicatedWorkspace(_root);
            var listings = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase);
            var replan = new List<string>();
            // Deterministic publication order: an interrupted batch always stops at the same file.
            foreach (var pair in plan.Files.OrderBy(pair => pair.Key, StringComparer.Ordinal))
            {
                cancellation.ThrowIfCancellationRequested();
                var target = LocalPath(pair.Key);
                var before = IncomingStamp(target);
                // A case-insensitive disk resolves `Note.mojian` for `note.mojian`; only the exact spelling is this item.
                var exact = ExactPath(pair.Key, listings);
                var actual = !exact ? null : Directory.Exists(target) ? NativeSyncIdentity.Directory : NativeSyncStorage.Hash(target);
                if (IncomingStamp(target) != before) throw new IOException("接收准备期间本地文件发生变化，请稍后重试");
                var wanted = pair.Value.WantedHash;
                if (actual != pair.Value.ExpectedHash && actual != wanted)
                {
                    // A folder has no content to review: it is planned again on the next pass instead of opening a conflict.
                    if (actual == NativeSyncIdentity.Directory || wanted == NativeSyncIdentity.Directory || pair.Value.ExpectedHash == NativeSyncIdentity.Directory)
                    { replan.Add(pair.Key); continue; }
                    conflicts.Add(pair.Key);
                }
                var replacement = actual == wanted || !NativeSyncIdentity.IsBlob(wanted)
                    ? null : Path.Combine(_directory, "incoming-replacements", Guid.NewGuid().ToString("N") + ".tmp");
                prepared.Add(new(pair.Key, target, before, actual != wanted, replacement, actual, wanted));
                if (actual == wanted) continue;
                if (NativeSyncIdentity.IsBlob(actual)) NativeSyncStorage.CopyDurable(target, Path.Combine(_directory, "incoming-backups", pair.Key));
                if (replacement != null && !pair.Value.KeepLocal) NativeSyncStorage.CopyDurable(BlobPath(wanted!), replacement);
                if (IncomingStamp(target) != before) throw new IOException("接收准备期间本地文件发生变化，请稍后重试");
            }
            if (replan.Count > 0)
            {
                plan = plan with { Files = plan.Files.Where(pair => !replan.Contains(pair.Key)).ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal) };
                if (plan.Files.Count == 0) { File.Delete(IncomingPath); return true; }
            }
            var active = ActiveConflictPaths();
            var newConflicts = conflicts.Where(path => !active.Contains(path) || plannedReview.Contains(path)).ToList();
            if (newConflicts.Count > 0)
            {
                var remote = new SyncManifest(plan.WorkspaceId, plan.Revision, plan.Files.ToDictionary(pair => pair.Key, pair => pair.Value.Target));
                await PreserveConflicts(newConflicts, remote, cancellation, true).ConfigureAwait(false);
            }
            var blocked = active.Except(plannedReview).Concat(conflicts).Distinct().ToArray();
            var canDefer = false;
            var remaining = new Dictionary<string, NativeSyncIncomingFile>(StringComparer.Ordinal);
            if (blocked.Length > 0)
            {
                var dependencies = PageDependencies(plan.Files.Keys.Union(blocked), path => plan.Files.GetValueOrDefault(path)?.WantedHash);
                var canTrack = true;
                var groups = blocked.Select(dependencies.Group).ToHashSet(StringComparer.Ordinal);
                remaining = plan.Files.Where(pair => groups.Contains(dependencies.Group(pair.Key)))
                    .ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
                canDefer = canTrack;
                // Undo only verified partial publications in a held component before opening the editor.
                foreach (var item in prepared.Where(item => remaining.ContainsKey(item.Path) && !item.Changed))
                {
                    var file = remaining[item.Path];
                    if (file.ExpectedHash == file.WantedHash) continue;
                    string? replacement = null;
                    if (NativeSyncIdentity.IsBlob(file.ExpectedHash))
                    {
                        var previous = Path.Combine(_directory, "incoming-backups", item.Path);
                        if (NativeSyncStorage.Hash(previous) != file.ExpectedHash) { canDefer = false; break; }
                        replacement = item.Target + "." + Guid.NewGuid().ToString("N") + ".tmp";
                        NativeSyncStorage.CopyDurable(previous, replacement);
                    }
                    // Restoring the acknowledged version: the published bytes are what is on disk now.
                    rollback.Add(item with { Replacement = replacement, Changed = true, Actual = item.Wanted, Wanted = file.ExpectedHash });
                }
            }
            var runnable = plan.Files.Where(pair => !remaining.ContainsKey(pair.Key)).ToList();
            var deferredReview = plannedReview.Where(path => remaining.ContainsKey(path) && !conflicts.Contains(path)).ToArray();
            if ((runnable.Count == 0 && rollback.Count == 0) || (remaining.Count > 0 && !canDefer))
            {
                if (runnable.Count == 0)
                {
                    if (deferredReview.Length != plannedReview.Count)
                        NativeSyncStorage.Save(IncomingPath, plan with { ReviewPaths = deferredReview });
                    if (canDefer) File.Move(IncomingPath, DeferredPath);
                }
                SetStatus(NativeSyncPhase.Conflict, "接收组存在冲突，双方副本及其依赖项已保留", blocked.Length);
                return canDefer;
            }
            var settled = plannedReview.Where(path => !remaining.ContainsKey(path)).ToArray();
            var nextFiles = new Dictionary<string, SyncEntry>(_state.Files, StringComparer.Ordinal);
            foreach (var pair in runnable) nextFiles[pair.Key] = pair.Value.Target;
            var nextRevision = Math.Max(_state.Revision, plan.Revision);
            NativeSyncStorage.Save(stagedState, new NativeSyncState
            {
                Format = _state.Format, DeviceId = _state.DeviceId, Endpoint = _state.Endpoint,
                WorkspaceId = _state.WorkspaceId, Revision = nextRevision, Files = nextFiles,
                ConflictDirectory = _state.ConflictDirectory, LastSuccessUtc = _state.LastSuccessUtc
            });
            cancellation.ThrowIfCancellationRequested();
            var applied = await applyOnIdle(() =>
            {
                lock (WorkspaceFileGate.ForRoot(_root))
                {
                    cancellation.ThrowIfCancellationRequested();
                    using var mutation = _localMutations.BeginMutation();
                    NativeSyncStorage.NoLinks(_root);
                    RejectSyncthingAncestors(_root);
                    if (!File.Exists(Path.Combine(_root, ".mojian-native-workspace"))) throw new IOException("原生同步副本标记已丢失");
                    var publish = prepared.Where(item => !remaining.ContainsKey(item.Path)).Concat(rollback).ToList();
                    foreach (var item in publish)
                    {
                        NativeSyncStorage.NoLinks(Path.GetDirectoryName(item.Target)!);
                        if (IncomingStamp(item.Target) != item.Stamp)
                            throw new IOException("接收发布前本地文件发生变化，接收未完成，请先恢复笔记库");
                    }
                    Publish(publish.Where(item => item.Changed).ToList());
                    File.Move(stagedState, StatePath, true);
                    foreach (var pair in runnable) _state.Files[pair.Key] = pair.Value.Target;
                    _state.Revision = nextRevision;
                    CompleteConflictReview(settled);
                    if (remaining.Count == 0) File.Delete(IncomingPath);
                    else
                    {
                        NativeSyncStorage.Save(IncomingPath, plan with { Files = remaining, ReviewPaths = deferredReview });
                        File.Move(IncomingPath, DeferredPath);
                    }
                    executed = true;
                }
            }).ConfigureAwait(false);
            if (!applied || !executed)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地已保存，收到更新等待当前编辑结束"); return false; }
            if (remaining.Count > 0)
            {
                SetStatus(NativeSyncPhase.Conflict, "已接收独立组，冲突组仍待审阅", blocked.Length);
            }
            return true;
        }
        finally
        {
            foreach (var item in prepared.Concat(rollback))
                if (item.Replacement != null) File.Delete(item.Replacement);
            File.Delete(stagedState);
        }
    }

    /// <summary>The files of the current conflict, as this device would see them after taking the server's version.</summary>
    public IReadOnlyList<NativeSyncConflictItem> ConflictReview()
    {
        if (_state.ConflictDirectory is not { } folder || !File.Exists(Path.Combine(folder, "conflicts.json"))) return [];
        var record = JsonNode.Parse(File.ReadAllBytes(Path.Combine(folder, "conflicts.json")))!.AsObject();
        return record["paths"]!.AsArray().Select(node => node!.GetValue<string>()).Where(ValidPath).Select(path =>
        {
            var local = Path.Combine(folder, "local", path); var remote = Path.Combine(folder, "remote", path);
            string? l = File.Exists(local) ? local : null, r = File.Exists(remote) ? remote : null;
            NativeSyncMerge.Summary? summary = null;
            if ((path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal) || path.EndsWith(".json", StringComparison.Ordinal)) && l != null && r != null && new FileInfo(l).Length <= MergeLimit && new FileInfo(r).Length <= MergeLimit)
                try { if (JsonNode.Parse(File.ReadAllBytes(l)) is JsonObject lo && JsonNode.Parse(File.ReadAllBytes(r)) is JsonObject ro) summary = NativeSyncMerge.Summarize(lo, ro); }
                catch (JsonException) { }
            return new NativeSyncConflictItem(path, l == null ? "add" : r == null ? "delete" : "modify", l, r, summary?.LocalOnly, summary?.RemoteOnly, summary?.Changed);
        }).ToList();
    }

    /// <summary>
    /// Resolves some conflicted paths: Local keeps this device's file (uploaded next pass), Remote takes the server's,
    /// Both keeps every id item of both JSON versions. Unchosen paths stay in the conflict.
    /// </summary>
    public async Task<bool> ResolveConflictsAsync(IReadOnlyDictionary<string, NativeSyncChoice> choices, Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation = default)
    {
        if (!await _running.WaitAsync(0, cancellation).ConfigureAwait(false)) return false;
        try
        {
            if (_state.ConflictDirectory is not { } folder) return true;
            var recordPath = Path.Combine(folder, "conflicts.json");
            var record = JsonNode.Parse(File.ReadAllBytes(recordPath))!.AsObject();
            var paths = record["paths"]!.AsArray().Select(node => node!.GetValue<string>()).ToList();
            if (choices.Count == 0 || choices.Keys.Any(path => !paths.Contains(path))) throw new ArgumentException("请选择冲突文件保留的版本");
            var remote = record["remote"]!.Deserialize<SyncManifest>(NativeSyncStorage.Json)!;
            var existing = ResumeIncoming();
            if (existing != null && existing.WorkspaceId != _state.WorkspaceId) throw new IOException("接收日志身份不匹配");
            var incoming = existing?.Files ?? new Dictionary<string, NativeSyncIncomingFile>(StringComparer.Ordinal);
            foreach (var (path, choice) in choices)
            {
                var preserved = Path.Combine(folder, "local", path);
                var preservedHash = File.Exists(preserved) ? NativeSyncStorage.Hash(preserved) : null;
                if (NativeSyncStorage.Hash(LocalPath(path)) != preservedHash) throw new IOException(path + " 在冲突后又改过，请先同步重新比较");
                var entry = remote.Files.GetValueOrDefault(path);
                var remoteFile = Path.Combine(folder, "remote", path);
                if (entry?.Hash is { } hash && NativeSyncIdentity.IsBlob(hash) && NativeSyncStorage.Hash(BlobPath(hash)) != hash)
                {
                    if (!File.Exists(remoteFile)) throw new IOException("冲突快照缺失，请保留目录");
                    NativeSyncStorage.CopyDurable(remoteFile, BlobPath(hash));
                }
                switch (choice)
                {
                    case NativeSyncChoice.Local:
                        incoming[path] = new(preservedHash, entry ?? throw new IOException("服务器没有该文件的版本"), KeepLocal: true);
                        break;
                    case NativeSyncChoice.Remote:
                        incoming[path] = new(preservedHash, entry ?? throw new IOException("服务器没有该文件的版本"));
                        break;
                    default:
                        if (entry == null || preservedHash == null || !File.Exists(remoteFile) || !(path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal) || path.EndsWith(".json", StringComparison.Ordinal)) ||
                            JsonNode.Parse(File.ReadAllBytes(preserved)) is not JsonObject ours || JsonNode.Parse(File.ReadAllBytes(remoteFile)) is not JsonObject theirs)
                            throw new IOException("只有两边都存在的笔记文件可以保留两边");
                        var bytes = System.Text.Encoding.UTF8.GetBytes(NativeSyncMerge.Union(ours, theirs).ToJsonString(NativeSyncMerge.Output));
                        var merged = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes));
                        Directory.CreateDirectory(Path.Combine(_directory, "blobs"));
                        NativeSyncStorage.Write(BlobPath(merged), bytes);
                        incoming[path] = new(preservedHash, entry, merged);
                        break;
                }
            }
            var review = (existing?.ReviewPaths ?? []).Union(choices.Keys, StringComparer.Ordinal).ToArray();
            NativeSyncStorage.Save(IncomingPath, new NativeSyncIncoming(_state.WorkspaceId, Math.Max(remote.Revision, existing?.Revision ?? 0), incoming, review));
            if (!await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false)) return false;
            var remaining = ActiveConflictPaths().Count;
            SetStatus(remaining == 0 ? NativeSyncPhase.Pending : NativeSyncPhase.Conflict,
                remaining == 0 ? "冲突已处理，下一轮同步继续" : $"还有 {remaining} 个冲突待处理", remaining);
            return true;
        }
        finally { _running.Release(); }
    }

    private const string RemoteMerge = "remote";
    private const long MergeLimit = 64L * 1024 * 1024;

    /// <summary>
    /// Three-way merges a JSON page/library conflict. Returns <see cref="RemoteMerge"/> when the local file only differed in
    /// volatile fields, the staged merged blob hash, or null to keep the conflict for manual review.
    /// </summary>
    private async Task<string?> MergeConflict(string path, string? baseHash, string? localHash, string? remoteHash, CancellationToken cancellation)
    {
        if (!(path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal) || path.EndsWith(".json", StringComparison.Ordinal)) ||
            !NativeSyncIdentity.IsBlob(baseHash) || !NativeSyncIdentity.IsBlob(localHash) || !NativeSyncIdentity.IsBlob(remoteHash)) return null;
        var localFile = LocalPath(path);
        if (!File.Exists(localFile) || new FileInfo(localFile).Length > MergeLimit) return null;
        try { await Download(baseHash, cancellation).ConfigureAwait(false); }
        catch (HttpRequestException error) when (error.StatusCode == System.Net.HttpStatusCode.NotFound) { return null; }
        await Download(remoteHash, cancellation).ConfigureAwait(false);
        return await Task.Run(() =>
        {
            if (new FileInfo(BlobPath(baseHash)).Length > MergeLimit || new FileInfo(BlobPath(remoteHash)).Length > MergeLimit) return null;
            try
            {
                var ours = File.ReadAllBytes(localFile);
                if (Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(ours)) != localHash) return null;
                JsonObject? Read(byte[] bytes) => JsonNode.Parse(bytes) as JsonObject;
                var (b, l, r) = (Read(File.ReadAllBytes(BlobPath(baseHash))), Read(ours), Read(File.ReadAllBytes(BlobPath(remoteHash))));
                if (b == null || l == null || r == null) return null;
                switch (NativeSyncMerge.Merge(b, l, r))
                {
                    case NativeSyncMerge.Result.Remote: return RemoteMerge;
                    case NativeSyncMerge.Result.Merged merged:
                        var bytes = System.Text.Encoding.UTF8.GetBytes(merged.Document.ToJsonString(NativeSyncMerge.Output));
                        var hash = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes));
                        Directory.CreateDirectory(Path.Combine(_directory, "blobs"));
                        NativeSyncStorage.Write(BlobPath(hash), bytes);
                        return hash;
                    default: return null;
                }
            }
            catch (JsonException) { return null; }
        }, cancellation).ConfigureAwait(false);
    }

    private sealed record PreparedIncoming(string Path, string Target, (long Length, long Modified, long Created)? Stamp, bool Changed, string? Replacement,
        string? Actual = null, string? Wanted = null);

    /// <summary>Exact on-disk spelling of every component below the library root.</summary>
    private bool ExactPath(string relative, Dictionary<string, HashSet<string>> listings)
    {
        var parent = _root;
        foreach (var part in relative.Split('/'))
        {
            if (!listings.TryGetValue(parent, out var names))
            {
                try { names = Directory.EnumerateFileSystemEntries(parent).Select(entry => Path.GetFileName(entry)).ToHashSet(StringComparer.Ordinal); }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
                listings[parent] = names;
            }
            if (!names.Contains(part)) return false;
            parent = Path.Combine(parent, part);
        }
        return true;
    }

    /// <summary>
    /// Case-only renames move the old spelling (delete-then-create would delete the only copy on a case-insensitive
    /// disk), then folders are created shallow-first, files written, files deleted and folders removed deepest-first.
    /// A remotely deleted folder that still holds local content is kept; the next pass uploads it again (Syncthing).
    /// </summary>
    private void Publish(List<PreparedIncoming> items)
    {
        var done = new HashSet<string>(StringComparer.Ordinal);
        foreach (var old in items.Where(item => item.Wanted == null && item.Actual != null))
        {
            var renamed = items.FirstOrDefault(item => item != old && item.Wanted != null && item.Actual == null &&
                string.Equals(item.Path, old.Path, StringComparison.OrdinalIgnoreCase));
            if (renamed == null) continue;
            var temporary = old.Target + ".mojian-case-" + Guid.NewGuid().ToString("N") + ".tmp";
            if (old.Actual == NativeSyncIdentity.Directory) { Directory.Move(old.Target, temporary); Directory.Move(temporary, renamed.Target); }
            else { File.Move(old.Target, temporary); File.Move(temporary, renamed.Target); }
            done.Add(old.Path);
            if (renamed.Wanted == old.Actual) done.Add(renamed.Path);
            else if (renamed.Replacement != null) { File.Replace(renamed.Replacement, renamed.Target, null); done.Add(renamed.Path); }
            _cache.TryRemove(old.Path, out _); _cache.TryRemove(renamed.Path, out _);
        }
        static int Depth(string path) => path.Count(character => character == '/');
        foreach (var item in items.Where(item => !done.Contains(item.Path) && item.Wanted == NativeSyncIdentity.Directory).OrderBy(item => Depth(item.Path)))
        {
            if (NativeSyncIdentity.IsBlob(item.Actual)) File.Delete(item.Target);
            Directory.CreateDirectory(item.Target);
        }
        foreach (var item in items.Where(item => !done.Contains(item.Path) && NativeSyncIdentity.IsBlob(item.Wanted)))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(item.Target)!);
            if (item.Actual == NativeSyncIdentity.Directory) Directory.Delete(item.Target, recursive: false);
            if (item.Replacement == null) continue;
            if (item.Stamp != null) File.Replace(item.Replacement, item.Target, null);
            else File.Move(item.Replacement, item.Target);
            _cache.TryRemove(item.Path, out _);
        }
        foreach (var item in items.Where(item => !done.Contains(item.Path) && item.Wanted == null && NativeSyncIdentity.IsBlob(item.Actual)))
        { File.Delete(item.Target); _cache.TryRemove(item.Path, out _); }
        foreach (var item in items.Where(item => !done.Contains(item.Path) && item.Wanted == null && item.Actual == NativeSyncIdentity.Directory)
            .OrderByDescending(item => Depth(item.Path)))
        {
            if (Directory.Exists(item.Target) && !Directory.EnumerateFileSystemEntries(item.Target).Any(entry => !NativeSyncStorage.IsExcluded(
                    Path.GetRelativePath(_root, entry).Replace('\\', '/'))))
                Directory.Delete(item.Target, recursive: true);
        }
    }
    private static (long Length, long Modified, long Created)? IncomingStamp(string path)
    {
        var file = new FileInfo(path);
        return file.Exists ? (file.Length, file.LastWriteTimeUtc.Ticks, file.CreationTimeUtc.Ticks) : null;
    }


    private void CompleteConflictReview(string[] choices)
    {
        if (choices.Length == 0 || _state.ConflictDirectory is not { } folder) return;
        var path = Path.Combine(folder, "conflicts.json");
        var record = JsonNode.Parse(File.ReadAllBytes(path))!.AsObject();
        var remaining = record["paths"]!.AsArray().Select(node => node!.GetValue<string>()).Except(choices, StringComparer.Ordinal).ToArray();
        record["paths"] = new JsonArray(remaining.Select(path => (JsonNode)path).ToArray());
        NativeSyncStorage.Write(path, System.Text.Encoding.UTF8.GetBytes(record.ToJsonString()));
        if (remaining.Length == 0)
        {
            _state.ConflictDirectory = null;
            PersistState();
        }
    }

    private HashSet<string> ActiveConflictPaths()
    {
        if (_state.ConflictDirectory is not { } folder) return new(StringComparer.Ordinal);
        var record = NativeSyncStorage.Read<NativeSyncConflictRecord>(Path.Combine(folder, "conflicts.json"));
        if (record.Paths.Any(path => !ValidPath(path))) throw new IOException("冲突记录路径无效");
        return record.Paths.ToHashSet(StringComparer.Ordinal);
    }

    private async Task PreserveConflicts(List<string> paths, SyncManifest remote, CancellationToken cancellation, bool localOnly = false, Dictionary<string, SyncFile>? expectedLocal = null)
    {
        var folder = Path.Combine(RecoveryDirectory, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var retained = ActiveConflictPaths();
        var preserved = new Dictionary<string, SyncEntry>(remote.Files, StringComparer.Ordinal);
        var revision = remote.Revision;
        if (_state.ConflictDirectory is { } previousFolder)
        {
            var previous = NativeSyncStorage.Read<NativeSyncConflictRecord>(Path.Combine(previousFolder, "conflicts.json"));
            revision = Math.Max(revision, previous.Remote.Revision);
            foreach (var path in retained.Except(paths))
            {
                foreach (var side in new[] { "local", "remote" })
                {
                    var original = Path.Combine(previousFolder, side, path);
                    if (File.Exists(original)) NativeSyncStorage.CopyDurable(original, Path.Combine(folder, side, path));
                }
                if (previous.Remote.Files.TryGetValue(path, out var entry)) preserved[path] = entry;
                else preserved.Remove(path);
            }
        }
        foreach (var path in paths)
        {
            cancellation.ThrowIfCancellationRequested();
            var local = LocalPath(path);
            var snapshot = Path.Combine(folder, "local", path);
            if (File.Exists(local)) NativeSyncStorage.CopyDurable(local, snapshot);
            if (expectedLocal != null && NativeSyncStorage.Hash(snapshot) != expectedLocal.GetValueOrDefault(path)?.Hash)
                throw new IOException("保存冲突快照时本地内容已变化，下一轮重试");
            if (remote.Files.GetValueOrDefault(path)?.Hash is { } hash && NativeSyncIdentity.IsBlob(hash))
            {
                if (localOnly)
                {
                    if (NativeSyncStorage.Hash(BlobPath(hash)) != hash) throw new IOException("恢复快照缺失，请保留目录");
                }
                else await Download(hash, cancellation).ConfigureAwait(false);
                NativeSyncStorage.CopyDurable(BlobPath(hash), Path.Combine(folder, "remote", path));
            }
        }
        if (File.Exists(OutboxPath)) NativeSyncStorage.CopyDurable(OutboxPath, Path.Combine(folder, "outbox.json"));
        var allPaths = retained.Union(paths).Order(StringComparer.Ordinal).ToArray();
        NativeSyncStorage.Save(Path.Combine(folder, "conflicts.json"), new
        {
            paths = allPaths, remote = new SyncManifest(remote.WorkspaceId, revision, preserved), baseline = _state.Files,
            localDeletions = allPaths.Where(path => !File.Exists(Path.Combine(folder, "local", path))).ToArray(),
            remoteDeletions = allPaths.Where(path => preserved.GetValueOrDefault(path)?.Hash == null).ToArray(),
        });
        _state.ConflictDirectory = folder;
        PersistState();
        SetStatus(NativeSyncPhase.Conflict, $"同步冲突 {paths.Count} 项，双方副本及删除记录已保留", paths.Count);
    }
    public void Dispose() { _watcher.Dispose(); _http.Dispose(); _writerLock.Dispose(); }
}
