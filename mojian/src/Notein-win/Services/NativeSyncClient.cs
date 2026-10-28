using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
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
    public NativeSyncStatus Snapshot { get; private set; } = new(NativeSyncPhase.Pending, "本地已保存，等待同步");
    public string Status => Snapshot.Message;
    public event Action<string>? StatusChanged;
    public event Action<NativeSyncStatus>? SnapshotChanged;
    public string RecoveryDirectory => Path.Combine(_directory, "conflicts");
    public bool HasPendingIncoming => File.Exists(IncomingPath);
    private string StatePath => Path.Combine(_directory, "state.json");
    private string OutboxPath => Path.Combine(_directory, "outbox.json");
    private string IncomingPath => Path.Combine(_directory, "incoming.json");
    private string BlobPath(string hash) => Path.Combine(_directory, "blobs", hash);
    private string LocalPath(string path)
    {
        if (!ValidPath(path)) throw new IOException("无效同步路径");
        return Path.Combine(_root, path.Replace('/', Path.DirectorySeparatorChar));
    }
    public static bool ValidPath(string path) => NativeSyncStorage.ValidPath(path);

    public NativeSyncClient(string root, string endpoint, string token, HttpMessageHandler? handler = null)
    {
        _root = Path.GetFullPath(root);
        _localMutations = NativeSyncMutationEpoch.ForRoot(_root);
        RequireDedicatedWorkspace(_root);
        var uri = new Uri(endpoint.TrimEnd('/') + "/", UriKind.Absolute);
        if (uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback)) throw new ArgumentException("同步地址必须使用 HTTPS");
        if (!string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment)) throw new ArgumentException("同步地址不能包含凭据或查询参数");
        if (string.IsNullOrWhiteSpace(token) || token.Contains('\r') || token.Contains('\n')) throw new ArgumentException("请配置同步令牌");
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
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
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
        _ = NativeSyncStorage.Enumerate(root, true).Count();
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
        var files = NativeSyncStorage.Enumerate(source).Select(path => Path.GetRelativePath(source, path).Replace('\\', '/'))
            .Where(NativeSyncStorage.ShouldSyncPath).ToDictionary(path => path, path => NativeSyncStorage.Hash(Path.Combine(source, path)), StringComparer.Ordinal);
        if (!files.ContainsKey("library.json")) throw new IOException("源目录缺少共享笔记目录");
        Directory.CreateDirectory(target);
        foreach (var pair in files)
        {
            var output = Path.Combine(target, pair.Key);
            NativeSyncStorage.CopyDurable(Path.Combine(source, pair.Key), output);
            if (NativeSyncStorage.Hash(output) != pair.Value) throw new IOException("复制期间原笔记发生变化，请重试到新目录");
        }
        if (File.Exists(Path.Combine(source, "settings.json"))) NativeSyncStorage.CopyDurable(Path.Combine(source, "settings.json"), Path.Combine(target, "settings.json"));
        var finalFiles = NativeSyncStorage.Enumerate(source).Select(path => Path.GetRelativePath(source, path).Replace('\\', '/')).Where(NativeSyncStorage.ShouldSyncPath).ToHashSet(StringComparer.Ordinal);
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

    private Dictionary<string, SyncFile> ScanCore()
    {
        var files = new Dictionary<string, SyncFile>(StringComparer.Ordinal);
        var aliases = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (!File.Exists(Path.Combine(_root, "library.json"))) throw new IOException("本地笔记目录缺失，已阻止同步删除");
        foreach (var absolute in NativeSyncStorage.Enumerate(_root, true))
        {
            var path = Path.GetRelativePath(_root, absolute).Replace('\\', '/');
            if (!NativeSyncStorage.ShouldSyncPath(path)) continue;
            if (!aliases.Add(path)) throw new IOException("本地同步路径大小写冲突");
            var before = Stamp(absolute) ?? throw new IOException("文件正在变更");
            if (_cache.TryGetValue(path, out var cached) && (cached.Length, cached.Modified) == before) files[path] = cached;
            else
            {
                var hash = NativeSyncStorage.Hash(absolute) ?? throw new IOException("文件正在变更");
                if (Stamp(absolute) != before) throw new IOException("本地文件正在保存，请稍后重试");
                files[path] = _cache[path] = new(hash, before.Length, before.Modified);
            }
        }
        foreach (var path in _cache.Keys.Except(files.Keys).ToArray()) _cache.TryRemove(path, out _);
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
            var changes = local.Keys.Union(_state.Files.Keys).Where(path => local.GetValueOrDefault(path)?.Hash != Hash(_state.Files, path))
                .Select(path => new SyncChange(path, _state.Files.GetValueOrDefault(path)?.Version ?? 0, local.GetValueOrDefault(path)?.Hash)).ToList();
            foreach (var change in changes.Where(change => change.Hash != null))
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
        using var response = await _http.GetAsync("manifest", cancellation).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var manifest = await response.Content.ReadFromJsonAsync<SyncManifest>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false) ?? throw new IOException("同步清单为空");
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
            if (_state.ConflictDirectory != null) { SetStatus(NativeSyncPhase.Conflict, "接收恢复存在冲突，请先恢复双方副本"); return false; }
            return await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { SetStatus(NativeSyncPhase.Paused, "接收恢复已暂停，暂时不能打开笔记库"); return false; }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        { SetStatus(NativeSyncPhase.Error, "接收恢复未完成，暂时不能打开笔记库：" + error.Message); return false; }
        finally { _running.Release(); }
    }

    public bool RecoverPendingIncoming() => RecoverPendingIncomingAsync(action => { action(); return Task.FromResult(true); }).GetAwaiter().GetResult();

    public async Task SyncAsync(Func<Action, Task<bool>> applyOnIdle, CancellationToken cancellation = default)
    {
        if (!await _running.WaitAsync(0, cancellation).ConfigureAwait(false)) return;
        try
        {
            RequireDedicatedWorkspace(_root);
            if (_state.ConflictDirectory != null) { SetStatus(NativeSyncPhase.Conflict, "同步冲突待处理，双方副本已保留"); return; }
            if (HasPendingIncoming && !await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false)) return;
            SetStatus(NativeSyncPhase.Checking, "正在检查同步");
            var previous = File.Exists(OutboxPath) ? NativeSyncStorage.Read<NativeSyncOutbox>(OutboxPath) : null;
            if (previous is { Submitted: true })
            {
                ValidateOutbox(previous);
                if (!await Commit(previous, cancellation).ConfigureAwait(false)) return;
            }
            var prepared = await Task.Run(() => CaptureOutgoing(cancellation), cancellation).ConfigureAwait(false);
            if (prepared == null)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地变更事务进行中，等待稳定快照后同步"); return; }
            var (local, outbox) = prepared.Value;
            var outgoingCount = outbox.Changes.Count;
            SetStatus(NativeSyncPhase.Pending, $"本地已保存，待上传 {outgoingCount} 项", outgoingCount);
            var remote = await Manifest(cancellation).ConfigureAwait(false);
            var incoming = new Dictionary<string, NativeSyncIncomingFile>(StringComparer.Ordinal);
            var conflicts = new List<string>();
            var changes = new List<SyncChange>();
            var aliases = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var path in local.Keys.Union(_state.Files.Keys).Union(remote.Files.Keys))
            {
                if (!aliases.Add(path)) throw new IOException("服务器路径与本地大小写冲突");
                var localHash = local.GetValueOrDefault(path)?.Hash;
                var baseHash = Hash(_state.Files, path);
                var remoteHash = Hash(remote.Files, path);
                if (localHash == remoteHash)
                {
                    if (remote.Files.TryGetValue(path, out var equal)) _state.Files[path] = equal;
                    continue;
                }
                if (localHash != baseHash && remoteHash != baseHash) conflicts.Add(path);
                else if (localHash != baseHash) changes.Add(new(path, remote.Files.GetValueOrDefault(path)?.Version ?? 0, localHash));
                else if (remote.Files.TryGetValue(path, out var entry)) incoming[path] = new(localHash, entry);
            }
            if (conflicts.Count > 0) { await PreserveConflicts(conflicts, remote, cancellation).ConfigureAwait(false); return; }
            outbox.WorkspaceId = remote.WorkspaceId;
            outbox.Changes = changes;
            if (changes.Count > 0)
            {
                NativeSyncStorage.Save(OutboxPath, outbox);
                if (!await Commit(outbox, cancellation).ConfigureAwait(false)) return;
            }
            else File.Delete(OutboxPath);
            if (incoming.Count > 0)
            {
                foreach (var entry in incoming.Values.Where(entry => entry.Target.Hash != null))
                    await Download(entry.Target.Hash!, cancellation).ConfigureAwait(false);
                NativeSyncStorage.Save(IncomingPath, new NativeSyncIncoming(remote.WorkspaceId, remote.Revision, incoming));
                if (!await ApplyIncoming(applyOnIdle, cancellation).ConfigureAwait(false)) return;
            }
            _state.Revision = Math.Max(_state.Revision, remote.Revision);
            _state.LastSuccessUtc = DateTimeOffset.UtcNow;
            PersistState();
            var remaining = await Task.Run(ScanStable, cancellation).ConfigureAwait(false);
            if (remaining == null)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地变更事务进行中，等待稳定快照后同步"); return; }
            var pending = remaining.Keys.Union(_state.Files.Keys).Count(path => remaining.GetValueOrDefault(path)?.Hash != Hash(_state.Files, path));
            SetStatus(pending == 0 ? NativeSyncPhase.Synced : NativeSyncPhase.Pending,
                pending == 0 ? "已同步" : $"本地已保存，待上传 {pending} 项", pending);
        }
        catch (OperationCanceledException) { SetStatus(cancellation.IsCancellationRequested ? NativeSyncPhase.Paused : NativeSyncPhase.Offline, "同步已暂停，待同步内容已保留", Snapshot.PendingFiles); }
        catch (HttpRequestException error)
        {
            var protocolFailure = error.HttpRequestError is HttpRequestError.SecureConnectionError or HttpRequestError.UserAuthenticationError or HttpRequestError.HttpProtocolError or HttpRequestError.InvalidResponse;
            var offline = !protocolFailure && (error.StatusCode == null || (int)error.StatusCode >= 500 || error.StatusCode == HttpStatusCode.RequestTimeout);
            SetStatus(offline ? NativeSyncPhase.Offline : NativeSyncPhase.Error,
                offline ? "离线或服务暂不可用，本地内容和待同步任务已保留" :
                    protocolFailure ? "HTTPS 安全校验或协议错误，请检查同步配置" : $"同步服务拒绝请求（{(int?)error.StatusCode}），请检查配置", Snapshot.PendingFiles);
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or ArgumentException or InvalidOperationException)
        { SetStatus(NativeSyncPhase.Error, "同步已暂停，本地内容已保留：" + error.Message, Snapshot.PendingFiles); }
        finally { _running.Release(); }
    }

    private void ValidateOutbox(NativeSyncOutbox outbox)
    {
        if (outbox.WorkspaceId != _state.WorkspaceId || outbox.DeviceId != _state.DeviceId || !NativeSyncStorage.ValidId(outbox.OperationId) ||
            outbox.Changes == null || outbox.Changes.Count is < 1 or > 20000 ||
            outbox.Changes.Any(change => change == null || !ValidPath(change.Path) || !NativeSyncStorage.ValidHash(change.Hash) || change.ExpectedVersion < 0 || change.ExpectedVersion > 9007199254740991L) ||
            outbox.Changes.Select(change => change.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() != outbox.Changes.Count)
            throw new IOException("同步待提交记录损坏，请保留恢复目录");
    }
    private async Task<bool> Commit(NativeSyncOutbox outbox, CancellationToken cancellation)
    {
        RequireDedicatedWorkspace(_root);
        ValidateOutbox(outbox);
        SetStatus(NativeSyncPhase.Uploading, $"正在上传 {outbox.Changes.Count} 项", outbox.Changes.Count);
        if (!outbox.Submitted)
        {
            foreach (var hash in outbox.Changes.Where(change => change.Hash != null).Select(change => change.Hash!).Distinct())
            {
                if (NativeSyncStorage.Hash(BlobPath(hash)) != hash) throw new IOException("待提交快照缺失或损坏，请保留恢复目录");
                using var content = new StreamContent(File.OpenRead(BlobPath(hash)));
                using var uploaded = await _http.PutAsync("blobs/" + hash, content, cancellation).ConfigureAwait(false);
                uploaded.EnsureSuccessStatusCode();
            }
            outbox.Submitted = true;
            NativeSyncStorage.Save(OutboxPath, outbox);
        }
        RequireDedicatedWorkspace(_root);
        using var result = await _http.PostAsJsonAsync("commit", new { outbox.WorkspaceId, outbox.DeviceId, outbox.OperationId, outbox.Changes }, NativeSyncStorage.Json, cancellation).ConfigureAwait(false);
        if (result.StatusCode == HttpStatusCode.Conflict)
        {
            var remote = await Manifest(cancellation).ConfigureAwait(false);
            await PreserveConflicts(outbox.Changes.Select(change => change.Path).ToList(), remote, cancellation).ConfigureAwait(false);
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

    private async Task Download(string hash, CancellationToken cancellation)
    {
        if (!NativeSyncStorage.ValidHash(hash)) throw new IOException("无效同步内容地址");
        var target = BlobPath(hash);
        if (NativeSyncStorage.Hash(target) == hash) return;
        var temporary = target + ".tmp";
        try
        {
            using var response = await _http.GetAsync("blobs/" + hash, HttpCompletionOption.ResponseHeadersRead, cancellation).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            await using (var file = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
            { await response.Content.CopyToAsync(file, cancellation).ConfigureAwait(false); file.Flush(true); }
            if (NativeSyncStorage.Hash(temporary) != hash) throw new IOException("下载校验失败，已保留本地内容");
            File.Move(temporary, target, true);
        }
        finally { File.Delete(temporary); }
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
        foreach (var hash in plan.Files.Values.Select(entry => entry.Target.Hash).OfType<string>().Distinct())
            if (NativeSyncStorage.Hash(BlobPath(hash)) != hash) throw new IOException("接收恢复快照缺失，请保留目录");
        SetStatus(NativeSyncPhase.Receiving, $"正在接收 {plan.Files.Count} 项");
        var conflicts = new List<string>();
        var prepared = new List<PreparedIncoming>();
        var stagedState = Path.Combine(_directory, "incoming-state." + Guid.NewGuid().ToString("N") + ".tmp");
        var executed = false;
        try
        {
            RequireDedicatedWorkspace(_root);
            foreach (var pair in plan.Files)
            {
                cancellation.ThrowIfCancellationRequested();
                var target = LocalPath(pair.Key);
                var before = IncomingStamp(target);
                var actual = NativeSyncStorage.Hash(target);
                if (IncomingStamp(target) != before) throw new IOException("接收准备期间本地文件发生变化，请稍后重试");
                if (actual != pair.Value.ExpectedHash && actual != pair.Value.Target.Hash) conflicts.Add(pair.Key);
                var replacement = actual == pair.Value.Target.Hash || pair.Value.Target.Hash == null
                    ? null : target + "." + Guid.NewGuid().ToString("N") + ".tmp";
                prepared.Add(new(pair.Key, target, before, actual != pair.Value.Target.Hash, replacement));
                if (actual == pair.Value.Target.Hash) continue;
                if (actual != null) NativeSyncStorage.CopyDurable(target, Path.Combine(_directory, "incoming-backups", pair.Key));
                if (replacement != null) NativeSyncStorage.CopyDurable(BlobPath(pair.Value.Target.Hash!), replacement);
                if (IncomingStamp(target) != before) throw new IOException("接收准备期间本地文件发生变化，请稍后重试");
            }
            if (conflicts.Count > 0)
            {
                var remote = new SyncManifest(plan.WorkspaceId, plan.Revision, plan.Files.ToDictionary(pair => pair.Key, pair => pair.Value.Target));
                await PreserveConflicts(conflicts, remote, cancellation, true).ConfigureAwait(false);
                return false;
            }
            var nextFiles = new Dictionary<string, SyncEntry>(_state.Files, StringComparer.Ordinal);
            foreach (var pair in plan.Files) nextFiles[pair.Key] = pair.Value.Target;
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
                    foreach (var item in prepared)
                    {
                        NativeSyncStorage.NoLinks(Path.GetDirectoryName(item.Target)!);
                        if (IncomingStamp(item.Target) != item.Stamp)
                            throw new IOException("接收发布前本地文件发生变化，接收未完成，请先恢复笔记库");
                    }
                    foreach (var item in prepared.Where(item => item.Changed).OrderBy(item => item.Path == "library.json" ? 1 : 0))
                    {
                        if (item.Replacement == null) File.Delete(item.Target);
                        else if (item.Stamp != null) File.Replace(item.Replacement, item.Target, null);
                        else File.Move(item.Replacement, item.Target);
                        _cache.TryRemove(item.Path, out _);
                    }
                    File.Move(stagedState, StatePath, true);
                    foreach (var pair in plan.Files) _state.Files[pair.Key] = pair.Value.Target;
                    _state.Revision = nextRevision;
                    File.Delete(IncomingPath);
                    executed = true;
                }
            }).ConfigureAwait(false);
            if (!applied || !executed)
            { SetStatus(NativeSyncPhase.WaitingForIdle, "本地已保存，收到更新等待当前编辑结束"); return false; }
            return true;
        }
        finally
        {
            foreach (var item in prepared)
                if (item.Replacement != null) File.Delete(item.Replacement);
            File.Delete(stagedState);
        }
    }

    private sealed record PreparedIncoming(string Path, string Target, (long Length, long Modified, long Created)? Stamp, bool Changed, string? Replacement);
    private static (long Length, long Modified, long Created)? IncomingStamp(string path)
    {
        var file = new FileInfo(path);
        return file.Exists ? (file.Length, file.LastWriteTimeUtc.Ticks, file.CreationTimeUtc.Ticks) : null;
    }

    private async Task PreserveConflicts(List<string> paths, SyncManifest remote, CancellationToken cancellation, bool localOnly = false)
    {
        var folder = Path.Combine(RecoveryDirectory, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        foreach (var path in paths)
        {
            if (File.Exists(LocalPath(path))) NativeSyncStorage.CopyDurable(LocalPath(path), Path.Combine(folder, "local", path));
            if (remote.Files.GetValueOrDefault(path)?.Hash is { } hash)
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
        NativeSyncStorage.Save(Path.Combine(folder, "conflicts.json"), new
        {
            paths, remote, baseline = _state.Files,
            localDeletions = paths.Where(path => !File.Exists(LocalPath(path))).ToArray(),
            remoteDeletions = paths.Where(path => remote.Files.GetValueOrDefault(path)?.Hash == null).ToArray(),
        });
        _state.ConflictDirectory = folder;
        PersistState();
        SetStatus(NativeSyncPhase.Conflict, $"同步冲突 {paths.Count} 项，双方副本及删除记录已保留", paths.Count);
    }
    public void Dispose() { _watcher.Dispose(); _http.Dispose(); _writerLock.Dispose(); }
}
