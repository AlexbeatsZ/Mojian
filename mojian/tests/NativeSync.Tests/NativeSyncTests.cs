using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Services;

Console.OutputEncoding = Encoding.UTF8;
if (args is ["--shared-contracts"])
{
    await DecisionFixture();
    await SharedCorePlanFixture();
    await MergeFixture();
    await ReviewFixture();
    Console.WriteLine("Shared native sync contracts passed");
    return;
}
var endpoint = Environment.GetEnvironmentVariable("MOJIAN_TEST_SYNC_URL") ?? throw new InvalidOperationException("Loopback test server required");
var token = Environment.GetEnvironmentVariable("MOJIAN_TEST_SYNC_TOKEN") ?? throw new InvalidOperationException("Test token required");
var passed = 0;
if (args is ["--expired-ancestor"])
{
    await ExpiredMergeAncestor();
    Console.WriteLine("Expired merge ancestor regression passed");
    return;
}
await Run("offline outbox survives restart and uploads without touching local content", OfflineOutbox);
await Run("lost commit response replays stable operation without a second revision", LostReceipt);
await Run("newer local edits survive lost-response retry", NewerEdit);
await Run("two writers preserve recoverable conflict copies", Conflict);
await Run("local deletion versus remote edit remains a recoverable conflict", DeletionConflict);
await Run("remote tombstones delete only clean local content", Tombstones);
await Run("partly published incoming journal replays offline with duplicate hashes", IncomingRecovery);
await Run("incoming recovery refuses unexpected local edits and never networks", IncomingConflict);
await Run("old journal isolates dependent assets and survives an interrupted review", DeferredIncomingReview);
await Run("partial upload and local ranged download resume against real core", ResumeLargeBlob);
await Run("corrupt incoming staging blocks editable startup", CorruptIncoming);
await Run("incoming publication only renames worker-prepared files", PreparedIncomingPublication);
await Run("incoming preparation rejects an edit made before UI publication", IncomingPublicationRace);
await Run("async offline recovery prepares large incoming files off the calling context", IncomingPreparationThread);
await Run("failed second replacement retains journal and checkpoint for offline batch recovery", IncomingPublicationFailure);
await Run("incoming callback refusal and exceptions respect the publication boundary", IncomingCallbackBoundary);
await Run("cancellation before a queued incoming Action prevents publication", IncomingPublicationCancellation);
await Run("authentication failure is configuration error, not offline", Authentication);
await Run("server identity change and revision rollback are blocked", ServerIdentity);
await Run("same-size edits with restored timestamps invalidate the hash cache", SameStampEdit);
await Run("dedicated migration, Syncthing ancestor/descendant and writer guards", MigrationGuards);
await Run("shared golden wire fixture matches actual Windows serialization and server response", WireFixture);
await Run("active deletion defers without waiting or publishing a torn snapshot", DeletionSnapshotBarrier);
await Run("epoch rejects intervening and nested mutations without locking scan work", MutationEpochBarrier);
await Run("network allows local edits while immutable outgoing snapshots remain consistent", SnapshotAfterNetworkMutation);
await Run("unsupported canonical content is refused by scan and clone without a marker", UnsupportedContentPaths);
await Run("ordered addresses restart from first and replay lost commit receipt across routes", OrderedAddresses);
await Run("CAS rejection retries without a permanent conflict and other 409s retain the outbox", RejectedCommit);
await Run("remote index deletion and incoming index tombstones preserve the local library", DeletedLibrary);
await Run("offline incoming recovery needs no endpoint credential configuration", RecoveryWithoutCredentials);
await Run("shared three-way decision table covers edits, deletions and equal concurrent changes", DecisionFixture);
await Run("shared JSON merge fixtures match Android", MergeFixture);
await Run("shared file-sync-core plan trace", SharedCorePlanFixture);
await Run("strokes written on two devices merge and a volatile-only resave takes the server page", StrokeMerge);
await Run("expired merge ancestor preserves conflict and receives independent pages", ExpiredMergeAncestor);
await Run("shared keep-both and summary fixtures match Android", ReviewFixture);
await Run("reviewed conflicts resolve per file, keep both and resume sync", ConflictReviewFlow);
await Run("folders replicate as items: empty sections, renames and deletions", FolderItems);
await Run("a remotely deleted folder that gained local content is kept and uploaded again", FolderWithLocalContent);
await Run("case-only renames of pages and folders keep content", CaseOnlyRename);
await Run("large change sets upload in server-sized batches", BatchedUpload);
Console.WriteLine($"Windows native sync: {passed} isolated scenarios passed");

async Task Run(string name, Func<Task> scenario)
{
    // Mandatory sharing locks exist only on Windows; elsewhere these scenarios cannot reach their failure boundary.
    if (!OperatingSystem.IsWindows() && name.StartsWith("failed second replacement", StringComparison.Ordinal))
    { Console.WriteLine("SKIP (needs Windows file locks) " + name); return; }
    await scenario();
    passed++;
    Console.WriteLine("PASS " + name);
}
void Check(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}
// Folder library v3: pages are <title>.mojian files in section folders; "测试本" is the test notebook folder.
const string Index = "测试本/.mojian-folder.json";
string NewPage() => "测试本/分区/" + Guid.NewGuid().ToString("N") + ".mojian";
string PageFile(string id, string value) => JsonSerializer.Serialize(new { version = 3, id, sortKey = "a0", page = new { }, content = new { text = value } });
Task<bool> Idle(Action action) { action(); return Task.FromResult(true); }
Task<bool> OnPublicationThread(Func<bool> callback)
{
    var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    new Thread(() =>
    {
        try { completion.TrySetResult(callback()); }
        catch (Exception error) { completion.TrySetException(error); }
    }).Start();
    return completion.Task;
}
string HashText(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
async Task<SyncManifest> Remote()
{
    using var http = new HttpClient();
    http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    return await http.GetFromJsonAsync<SyncManifest>(endpoint + "manifest", NativeSyncStorage.Json) ?? throw new Exception("Missing manifest");
}
void AssertSynced(NativeSyncClient client) => Check(client.Snapshot.Phase == NativeSyncPhase.Synced, "Unexpected status: " + client.Status);

NativeSyncIncoming StageIncoming(TestWorkspace root, Dictionary<string, string?> updates)
{
    var state = new NativeSyncState { WorkspaceId = Guid.NewGuid().ToString("N"), Endpoint = endpoint, Revision = 1 };
    var files = new Dictionary<string, NativeSyncIncomingFile>(StringComparer.Ordinal);
    foreach (var pair in updates)
    {
        var before = NativeSyncStorage.Hash(root.PathFor(pair.Key));
        if (before != null) state.Files[pair.Key] = new(before, 1);
        var hash = pair.Value == null ? null : HashText(pair.Value);
        if (hash != null) NativeSyncStorage.Write(root.Private("blobs", hash), Encoding.UTF8.GetBytes(pair.Value!));
        files[pair.Key] = new(before, new(hash, 2));
    }
    var plan = new NativeSyncIncoming(state.WorkspaceId, 2, files);
    NativeSyncStorage.Save(root.Private("state.json"), state);
    NativeSyncStorage.Save(root.Private("incoming.json"), plan);
    return plan;
}
void AssertIncomingTemporariesRemoved(TestWorkspace root) => Check(
    !Directory.EnumerateFiles(root.Root, "*.tmp", SearchOption.AllDirectories).Any(), "Incoming preparation left temporary files behind");

async Task OfflineOutbox()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    root.Write(page, "offline");
    using (var client = new NativeSyncClient(root.Root, endpoint, token, new TransportFault { Offline = true }))
    {
        await client.SyncAsync(Idle);
        Check(client.Snapshot.Phase == NativeSyncPhase.Offline, "Offline was not distinguished");
        var outbox = NativeSyncStorage.Read<NativeSyncOutbox>(root.Private("outbox.json"));
        Check(!outbox.Submitted && outbox.Changes.Any(change => change.Path == page), "Offline edits not durably queued");
        Check(File.Exists(root.Private("blobs", HashText("offline"))), "Offline bytes not staged");
    }
    var replay = new TransportFault();
    using var restarted = new NativeSyncClient(root.Root, endpoint, token, replay);
    await restarted.SyncAsync(Idle);
    AssertSynced(restarted);
    Check((await Remote()).Files[page].Hash == HashText("offline"), "Restart lost offline edit");
    Check(root.Read(page) == "offline", "Local file changed");
    Check(!Directory.EnumerateFileSystemEntries(root.Private("blobs")).Any(), "Acknowledged transfer blobs were retained after a clean pass");
}

async Task LostReceipt()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    var fault = new TransportFault();
    string operationId;
    using (var client = new NativeSyncClient(root.Root, endpoint, token, fault))
    {
        await client.SyncAsync(Idle);
        root.Write(page, "accepted before lost response");
        fault.DropCommitReply = true;
        await client.SyncAsync(Idle);
        Check(client.Snapshot.Phase == NativeSyncPhase.Offline, "Lost response not marked offline: " + client.Status);
        var outbox = NativeSyncStorage.Read<NativeSyncOutbox>(root.Private("outbox.json"));
        Check(outbox.Submitted, "Uncertain commit lost submitted marker");
        operationId = outbox.OperationId;
    }
    var revision = (await Remote()).Revision;
    var replay = new TransportFault();
    using var restarted = new NativeSyncClient(root.Root, endpoint, token, replay);
    await restarted.SyncAsync(Idle);
    AssertSynced(restarted);
    Check((await Remote()).Revision == revision, "Retry caused duplicate commit");
    Check(replay.Uploads == 0, "Receipt replay unnecessarily reuploaded acknowledged bytes");
    Check(!File.Exists(root.Private("outbox.json")), "Receipt did not clear outbox");
    Check(!string.IsNullOrEmpty(operationId), "Missing stable operation id");
}

async Task NewerEdit()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    var fault = new TransportFault();
    using (var client = new NativeSyncClient(root.Root, endpoint, token, fault))
    {
        await client.SyncAsync(Idle);
        root.Write(page, "first");
        fault.DropCommitReply = true;
        await client.SyncAsync(Idle);
        root.Write(page, "new local edit after lost reply");
    }
    using var restarted = new NativeSyncClient(root.Root, endpoint, token);
    await restarted.SyncAsync(Idle);
    AssertSynced(restarted);
    Check((await Remote()).Files[page].Hash == HashText("new local edit after lost reply"), "New edit replaced by submitted snapshot");
}

async Task Conflict()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "base");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using (var client = new NativeSyncClient(second.Root, endpoint, token))
    {
        await writer.SyncAsync(Idle);
        await client.SyncAsync(Idle);
        first.Write(page, "remote edit");
        second.Write(page, "local edit");
        await writer.SyncAsync(Idle);
        await client.SyncAsync(Idle);
        Check(client.Snapshot.Phase == NativeSyncPhase.Conflict, "Concurrent edit not blocked");
        Check(second.Read(page) == "local edit", "Conflict overwrote local edit");
        var folder = client.Snapshot.ConflictDirectory!;
        Check(File.ReadAllText(Path.Combine(folder, "local", page)) == "local edit", "Local recovery missing");
        Check(File.ReadAllText(Path.Combine(folder, "remote", page)) == "remote edit", "Remote recovery missing");
        Check((await Remote()).Files[page].Hash == HashText("remote edit"), "Remote overwritten by conflict");
    }
    using var restarted = new NativeSyncClient(second.Root, endpoint, token, new TransportFault { Offline = true });
    await restarted.SyncAsync(Idle);
    Check(restarted.Snapshot.Phase == NativeSyncPhase.Conflict, "Conflict not durable across restart");
}

async Task Tombstones()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "delete me");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    File.Delete(first.PathFor(page));
    await writer.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    AssertSynced(reader);
    Check(!File.Exists(second.PathFor(page)), "Clean remote deletion not applied");
    Check((await Remote()).Files[page].Hash == null, "Tombstone missing");
    Check(File.ReadAllText(second.Private("incoming-backups", page)) == "delete me", "Deleted original not recoverable");
}

async Task DeletionConflict()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "base before deletion");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    File.Delete(second.PathFor(page));
    first.Write(page, "remote edit after local deletion");
    await writer.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    Check(reader.Snapshot.Phase == NativeSyncPhase.Conflict && !File.Exists(second.PathFor(page)), "Deletion/edit conflict overwrote deletion");
    using var metadata = JsonDocument.Parse(File.ReadAllText(Path.Combine(reader.Snapshot.ConflictDirectory!, "conflicts.json")));
    Check(metadata.RootElement.GetProperty("localDeletions").EnumerateArray().Any(value => value.GetString() == page), "Deletion record missing");
}

async Task IncomingRecovery()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var firstPage = NewPage();
    var secondPage = NewPage();
    first.Write(firstPage, "same remote bytes");
    first.Write(secondPage, "same remote bytes");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    using (var reader = new NativeSyncClient(second.Root, endpoint, token))
    {
        await reader.SyncAsync(_ => Task.FromResult(false));
        Check(reader.HasPendingIncoming, "Missing durable incoming journal");
        Check(reader.Snapshot.Phase == NativeSyncPhase.WaitingForIdle, "Busy editor not distinguished");
    }
    var plan = NativeSyncStorage.Read<NativeSyncIncoming>(second.Private("incoming.json"));
    NativeSyncStorage.CopyDurable(second.Private("blobs", plan.Files[firstPage].Target.Hash!), second.PathFor(firstPage));
    var offline = new TransportFault { Offline = true };
    using var restarted = new NativeSyncClient(second.Root, endpoint, token, offline);
    Check(await restarted.RecoverPendingIncomingAsync(Idle), "Offline recovery failed");
    Check(offline.Requests == 0, "Startup recovery performed a network request");
    Check(!restarted.HasPendingIncoming && second.Read(firstPage) == "same remote bytes" && second.Read(secondPage) == "same remote bytes", "Shared hash consumed or partial publication not recovered");
}

async Task IncomingConflict()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "remote expected");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    using (var reader = new NativeSyncClient(second.Root, endpoint, token)) await reader.SyncAsync(_ => Task.FromResult(false));
    second.Write(page, "unexpected offline edit");
    var offline = new TransportFault { Offline = true };
    using var restarted = new NativeSyncClient(second.Root, endpoint, token, offline);
    // Folder items are independent groups: they publish while the edited page waits for review.
    await restarted.RecoverPendingIncomingAsync(Idle);
    Check(restarted.ConflictReview().Any(item => item.Path == page) && restarted.Snapshot.Phase == NativeSyncPhase.Conflict, "Incoming conflict not retained for review");
    Check(second.Read(page) == "unexpected offline edit" && offline.Requests == 0, "Offline recovery changed local data or performed network");
}

async Task DeferredIncomingReview()
{
    using var root = new TestWorkspace();
    const string a = "测试本/分区/页 A.mojian", b = "测试本/分区/页 B.mojian", c = "测试本/分区/页 C.mojian";
    const string asset = ".mojian/assets/a/original.pdf";
    foreach (var (path, id) in new[] { (a, "a"), (b, "b"), (c, "c") }) root.Write(path, PageFile(id, "base"));
    root.Write(asset, "base");
    StageIncoming(root, new() { [a] = PageFile("a", "server-a"), [asset] = "server-asset", [b] = PageFile("b", "server-b"), [c] = PageFile("c", "server-c") });
    NativeSyncStorage.CopyDurable(root.PathFor(asset), root.Private("incoming-backups", asset));
    root.Write(asset, "server-asset"); // Previous process stopped after replacing the first dependency.
    root.Write(a, PageFile("a", "my-a"));
    root.Write(b, PageFile("b", "my-b"));
    using (var client = NativeSyncClient.ForLocalRecovery(root.Root))
    {
        Check(await client.RecoverPendingIncomingAsync(Idle), client.Status);
        Check(root.Read(c) == PageFile("c", "server-c") && root.Read(asset) == "base", "Independent page or dependent asset was misplaced");
        Check(client.ConflictReview().Count == 2 && !client.HasPendingIncoming, "Consistent workspace cannot open for review");
        Check(!await client.ResolveConflictsAsync(new Dictionary<string, NativeSyncChoice> { [a] = NativeSyncChoice.Local }, _ => Task.FromResult(false)), "Busy editor accepted a write");
        Check(client.HasPendingIncoming && client.ConflictReview().Count == 2, "Review intent was lost before publication");
    }
    using var restarted = NativeSyncClient.ForLocalRecovery(root.Root);
    Check(await restarted.RecoverPendingIncomingAsync(Idle), restarted.Status);
    Check(root.Read(a) == PageFile("a", "my-a") && root.Read(b) == PageFile("b", "my-b") && root.Read(asset) == "server-asset", "Review replay changed another conflict or omitted dependencies");
    Check(restarted.ConflictReview().Select(item => item.Path).SequenceEqual([b]), "Remaining conflict was not retained");
    Check(await restarted.ResolveConflictsAsync(new Dictionary<string, NativeSyncChoice> { [b] = NativeSyncChoice.Remote }, Idle), restarted.Status);
    Check(root.Read(b) == PageFile("b", "server-b") && restarted.ConflictReview().Count == 0, "Second choice did not settle");
}

async Task ResumeLargeBlob()
{
    using var root = new TestWorkspace();
    using var http = new HttpClient { BaseAddress = new Uri(endpoint) };
    http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    const int chunk = 8 * 1024 * 1024;
    var bytes = new byte[chunk + 391];
    Random.Shared.NextBytes(bytes);
    var hash = Convert.ToHexStringLower(SHA256.HashData(bytes));
    var source = root.Private("source.bin");
    NativeSyncStorage.Write(source, bytes);
    using var prefix = new ByteArrayContent(bytes, 0, chunk);
    prefix.Headers.ContentRange = new System.Net.Http.Headers.ContentRangeHeaderValue(0, chunk - 1, bytes.Length);
    using var response = await http.PutAsync("uploads/" + hash, prefix);
    response.EnsureSuccessStatusCode();
    var transfer = new NativeSyncBlobTransfer(http);
    await transfer.UploadAsync(hash, source, default);
    var target = root.Private("download.bin");
    NativeSyncStorage.Write(target + ".part", bytes[..chunk]);
    NativeSyncStorage.Save(target + ".progress", new { offset = chunk, total = bytes.Length });
    await transfer.DownloadAsync(hash, target, default);
    Check(NativeSyncStorage.Hash(target) == hash && !File.Exists(target + ".progress"), "Resumed download lost verified bytes");
}

async Task PreparedIncomingPublication()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "before incoming");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await reader.SyncAsync(Idle);
    first.Write(page, "after incoming");
    await writer.SyncAsync(Idle);
    var published = false;
    var preparationThread = 0;
    reader.SnapshotChanged += status => { if (status.Phase == NativeSyncPhase.Receiving) preparationThread = Environment.CurrentManagedThreadId; };
    await reader.SyncAsync(action => OnPublicationThread(() =>
    {
        Check(preparationThread != 0 && preparationThread != Environment.CurrentManagedThreadId, "Preparation ran on the publication thread");
        Check(File.ReadAllText(second.Private("incoming-backups", page)) == "before incoming", "Backup was not prepared before UI publication");
        Check(Directory.EnumerateFiles(second.Private("incoming-replacements"), "*.tmp").Any(), "Replacement was not prepared before UI publication");
        using var immutableBlob = new FileStream(second.Private("blobs", HashText("after incoming")), FileMode.Open, FileAccess.Read, FileShare.None);
        using var oldTarget = new FileStream(second.PathFor(page), FileMode.Open, FileAccess.Read, FileShare.Delete);
        action();
        published = true;
        return true;
    }));
    AssertSynced(reader);
    Check(published && second.Read(page) == "after incoming", "Prepared incoming content was not atomically published");
}

async Task IncomingPublicationRace()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    var page = NewPage();
    first.Write(page, "remote incoming");
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    await writer.SyncAsync(Idle);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await reader.SyncAsync(action =>
    {
        second.Write(page, "intervening local edit");
        action();
        return Task.FromResult(true);
    });
    Check(reader.HasPendingIncoming && second.Read(page) == "intervening local edit", "Prepared incoming overwrote an intervening local edit");
    Check(reader.Snapshot.Phase == NativeSyncPhase.Error, "Failed Action did not signal the host to keep editing locked");
    await reader.RecoverPendingIncomingAsync(Idle); // independent folder items may publish; the page waits for review
    Check(reader.Snapshot.Phase == NativeSyncPhase.Conflict && reader.ConflictReview().Any(item => item.Path == page), "Deferred edit was not preserved as a recoverable conflict");
}

async Task IncomingPreparationThread()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    var content = new string('x', 8 * 1024 * 1024);
    StageIncoming(root, new() { [page] = content });
    var offline = new TransportFault { Offline = true };
    using var client = new NativeSyncClient(root.Root, endpoint, token, offline);
    using var finishPreparation = new ManualResetEventSlim();
    var preparing = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
    var returned = new TaskCompletionSource<Task<bool>>(TaskCreationOptions.RunContinuationsAsynchronously);
    client.SnapshotChanged += status =>
    {
        if (status.Phase != NativeSyncPhase.Receiving) return;
        preparing.TrySetResult(Environment.CurrentManagedThreadId);
        if (!finishPreparation.Wait(TimeSpan.FromSeconds(10))) throw new InvalidOperationException("Timed out releasing incoming preparation");
    };
    var callerId = 0;
    var caller = new Thread(() =>
    {
        callerId = Environment.CurrentManagedThreadId;
        SynchronizationContext.SetSynchronizationContext(new SynchronizationContext());
        try { returned.TrySetResult(client.RecoverPendingIncomingAsync(Idle)); }
        catch (Exception error) { returned.TrySetException(error); }
    });
    caller.Start();
    int preparationId;
    bool returnedBeforePreparationFinished;
    try
    {
        preparationId = await preparing.Task.WaitAsync(TimeSpan.FromSeconds(5));
        returnedBeforePreparationFinished = await Task.WhenAny(returned.Task, Task.Delay(TimeSpan.FromSeconds(2))) == returned.Task;
    }
    finally { finishPreparation.Set(); }
    var recovery = await returned.Task.WaitAsync(TimeSpan.FromSeconds(5));
    Check(await recovery.WaitAsync(TimeSpan.FromSeconds(10)), "Worker preparation failed: " + client.Status);
    Check(caller.Join(TimeSpan.FromSeconds(5)), "Calling context thread did not finish");
    Check(preparationId != callerId && returnedBeforePreparationFinished, "Async recovery hashed or prepared files on the calling context");
    Check(offline.Requests == 0 && !client.HasPendingIncoming && root.Read(page) == content, "Worker recovery changed bytes or contacted the network");
    AssertIncomingTemporariesRemoved(root);
}

async Task IncomingPublicationFailure()
{
    using var root = new TestWorkspace();
    var firstPage = NewPage();
    var secondPage = NewPage();
    root.Write(firstPage, "old first");
    root.Write(secondPage, "old second");
    StageIncoming(root, new() { [Index] = "new index", [firstPage] = "new first", [secondPage] = "new second" });
    var checkpoint = File.ReadAllBytes(root.Private("state.json"));
    var offline = new TransportFault { Offline = true };
    using (var client = new NativeSyncClient(root.Root, endpoint, token, offline))
    {
        var recovered = await client.RecoverPendingIncomingAsync(action =>
        {
            using var lockedTarget = new FileStream(root.PathFor(secondPage), FileMode.Open, FileAccess.Read, FileShare.None);
            action();
            return Task.FromResult(true);
        });
        Check(!recovered && client.HasPendingIncoming && client.Snapshot.Phase == NativeSyncPhase.Error, "Partial publication did not block editable startup");
        Check(root.Read(firstPage) == "new first" && root.Read(secondPage) == "old second", "Test did not reach a real partial replacement boundary");
        Check(File.ReadAllBytes(root.Private("state.json")).SequenceEqual(checkpoint), "Partial publication advanced the checkpoint");
        Check(File.ReadAllText(root.Private("incoming-backups", firstPage)) == "old first", "Original first-page recovery copy missing");
        AssertIncomingTemporariesRemoved(root);
    }
    var restartedOffline = new TransportFault { Offline = true };
    using var restarted = new NativeSyncClient(root.Root, endpoint, token, restartedOffline);
    Check(await restarted.RecoverPendingIncomingAsync(Idle), "Offline replay failed: " + restarted.Status);
    Check(!restarted.HasPendingIncoming && root.Read(firstPage) == "new first" && root.Read(secondPage) == "new second" && root.Read(Index) == "new index",
        "Offline replay did not finish the entire batch");
    var state = NativeSyncStorage.Read<NativeSyncState>(root.Private("state.json"));
    Check(state.Revision == 2 && state.Files.Values.All(entry => entry.Version == 2), "Full replay did not acknowledge the complete batch");
    Check(offline.Requests == 0 && restartedOffline.Requests == 0, "Partial incoming recovery contacted the network");
    AssertIncomingTemporariesRemoved(root);
}

async Task IncomingCallbackBoundary()
{
    foreach (var mode in new[] { "busy", "false-success", "throw-before", "throw-after" })
    {
        using var root = new TestWorkspace();
        var page = NewPage();
        StageIncoming(root, new() { [page] = "incoming callback content" });
        var offline = new TransportFault { Offline = true };
        using var client = new NativeSyncClient(root.Root, endpoint, token, offline);
        var recovered = await client.RecoverPendingIncomingAsync(action =>
        {
            if (mode == "throw-before") throw new IOException("Host refused publication");
            if (mode == "throw-after") { action(); throw new IOException("Host reload failed after publication"); }
            return Task.FromResult(mode == "false-success");
        });
        var published = mode == "throw-after";
        Check(!recovered && client.HasPendingIncoming != published && File.Exists(root.PathFor(page)) == published,
            "Callback crossed the wrong publication boundary: " + mode);
        Check(client.Snapshot.Phase == (mode.StartsWith("throw", StringComparison.Ordinal) ? NativeSyncPhase.Error : NativeSyncPhase.WaitingForIdle),
            "Callback failure status was misclassified: " + mode);
        var state = NativeSyncStorage.Read<NativeSyncState>(root.Private("state.json"));
        Check(state.Files.ContainsKey(page) == published, "Callback advanced the wrong checkpoint: " + mode);
        AssertIncomingTemporariesRemoved(root);
        var callbackInvoked = false;
        Check(await client.RecoverPendingIncomingAsync(action => { callbackInvoked = true; action(); return Task.FromResult(true); }), "Offline recovery could not continue: " + mode);
        Check(callbackInvoked != published && !client.HasPendingIncoming && root.Read(page) == "incoming callback content" && offline.Requests == 0,
            "Callback recovery repeated a committed batch or used the network: " + mode);
    }
}

async Task IncomingPublicationCancellation()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    StageIncoming(root, new() { [page] = "cancelled incoming" });
    var offline = new TransportFault { Offline = true };
    using var client = new NativeSyncClient(root.Root, endpoint, token, offline);
    using var cancellation = new CancellationTokenSource();
    Check(!await client.RecoverPendingIncomingAsync(action =>
    {
        cancellation.Cancel();
        action();
        return Task.FromResult(true);
    }, cancellation.Token), "Cancelled queued Action reported successful recovery");
    Check(client.Snapshot.Phase == NativeSyncPhase.Paused && client.HasPendingIncoming && !File.Exists(root.PathFor(page)),
        "Cancelled queued Action published files or discarded its journal");
    Check(!NativeSyncStorage.Read<NativeSyncState>(root.Private("state.json")).Files.ContainsKey(page), "Cancelled queued Action advanced its checkpoint");
    AssertIncomingTemporariesRemoved(root);
    Check(await client.RecoverPendingIncomingAsync(Idle) && root.Read(page) == "cancelled incoming" && offline.Requests == 0, "Cancelled journal did not remain recoverable offline");
}

async Task CorruptIncoming()
{
    using var root = new TestWorkspace();
    using var client = new NativeSyncClient(root.Root, endpoint, token, new TransportFault { Offline = true });
    var hash = HashText("missing");
    var state = NativeSyncStorage.Read<NativeSyncState>(root.Private("state.json"));
    NativeSyncStorage.Save(root.Private("incoming.json"), new NativeSyncIncoming(state.WorkspaceId, 1, new() { [NewPage()] = new(null, new(hash, 1)) }));
    Check(!client.RecoverPendingIncoming(), "Missing recovery blob accepted");
    Check(client.HasPendingIncoming && client.Snapshot.Phase == NativeSyncPhase.Error, "Invalid startup did not stay blocked");
    await Task.CompletedTask;
}

async Task Authentication()
{
    using var root = new TestWorkspace();
    using var client = new NativeSyncClient(root.Root, endpoint, "wrong token");
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Error, "401 presented as offline");
}

async Task ServerIdentity()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    var fault = new TransportFault();
    using var client = new NativeSyncClient(root.Root, endpoint, token, fault);
    await client.SyncAsync(Idle);
    root.Write(page, "unsubmitted local edit");
    fault.ChangeManifest = manifest => manifest with { WorkspaceId = Guid.NewGuid().ToString() };
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Error && !(await Remote()).Files.ContainsKey(page), "Wrong workspace accepted local edit");
    fault.ChangeManifest = manifest => manifest with { Revision = 0 };
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Error && root.Read(page) == "unsubmitted local edit", "Revision rollback overwrote local data");
}

async Task SameStampEdit()
{
    using var root = new TestWorkspace();
    var page = NewPage();
    root.Write(page, "before");
    using var client = new NativeSyncClient(root.Root, endpoint, token);
    await client.SyncAsync(Idle);
    var stamp = File.GetLastWriteTimeUtc(root.PathFor(page));
    File.WriteAllText(root.PathFor(page), "after!");
    File.SetLastWriteTimeUtc(root.PathFor(page), stamp);
    for (var attempt = 0; attempt < 20; attempt++)
    {
        await Task.Delay(50);
        await client.SyncAsync(Idle);
        if ((await Remote()).Files[page].Hash == HashText("after!")) return;
    }
    throw new Exception("Timestamp-restored edit was permanently lost by scan cache");
}

async Task MigrationGuards()
{
    using var root = new TestWorkspace();
    var target = root.Root + "-clone";
    try
    {
        NativeSyncClient.CloneWorkspace(root.Root, target);
        NativeSyncClient.RequireDedicatedWorkspace(target);
        Check(File.Exists(Path.Combine(target, Index)), "Clone incomplete");
        Check(!NativeSyncConfiguration.Load(target).Enabled, "Clone default enabled sync");
        Directory.CreateDirectory(Path.Combine(target, "nested", ".stfolder"));
        NativeSyncClient.RequireDedicatedWorkspace(target);
        using (var scanner = new NativeSyncClient(target, endpoint, token, new TransportFault { Offline = true }))
        {
            Check(!scanner.Scan().Keys.Any(path => path.StartsWith("nested/", StringComparison.Ordinal)) &&
                scanner.FailedItems.ContainsKey("nested"), "Nested Syncthing folder was synchronized or not reported");
        }
        Directory.Delete(Path.Combine(target, "nested"), true);
        using var first = new NativeSyncClient(target, endpoint, token);
        var blocked = false;
        try { using var second = new NativeSyncClient(target, endpoint, token); } catch (IOException) { blocked = true; }
        Check(blocked, "Two local sync writers accepted");
    }
    finally { if (Directory.Exists(target)) Directory.Delete(target, true); }
    var ancestor = root.PathFor(".stfolder");
    Directory.CreateDirectory(ancestor);
    var rejected = false;
    try { using var client = new NativeSyncClient(root.Root, endpoint, token); } catch (IOException) { rejected = true; }
    Check(rejected, "Syncthing root accepted");
    var unsafeTarget = root.PathFor("new-native-copy");
    using var unrelatedSource = new TestWorkspace();
    rejected = false;
    try { NativeSyncClient.CloneWorkspace(unrelatedSource.Root, unsafeTarget); } catch (IOException) { rejected = true; }
    Check(rejected && !Directory.Exists(unsafeTarget), "Migration wrote into a Syncthing ancestor before refusing it");
    await Task.CompletedTask;
}

async Task DeletionSnapshotBarrier()
{
    using var root = new TestWorkspace();
    var transport = new TransportFault();
    using var client = new NativeSyncClient(root.Root, endpoint, token, transport);
    await client.SyncAsync(Idle);
    AssertSynced(client);
    var page = NewPage();
    root.Write(page, "page before transactional delete");
    var oldLibrary = JsonSerializer.Serialize(new { pages = new[] { page } });
    root.Write(Index, oldLibrary);
    await client.SyncAsync(Idle);
    AssertSynced(client);
    var initialCommits = transport.CommitBodies.Count;
    var initialRequests = transport.Requests;
    using var moved = new ManualResetEventSlim();
    using var publish = new ManualResetEventSlim();
    var checking = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    client.SnapshotChanged += status => { if (status.Phase == NativeSyncPhase.Checking) checking.TrySetResult(); };
    var mutation = Task.Run(() =>
    {
        using var editing = NativeSyncMutationEpoch.ForRoot(root.Root).BeginMutation();
        lock (WorkspaceFileGate.ForRoot(root.Root))
        {
            File.Move(root.PathFor(page), root.Private("deleted-page-backup.json"));
            moved.Set();
            if (!publish.Wait(TimeSpan.FromSeconds(10))) throw new Exception("Timed out publishing test deletion");
            root.Write(Index, "{\"pages\":[]}");
        }
    });
    Check(await Task.Run(() => moved.Wait(TimeSpan.FromSeconds(5))), "Deletion test did not reach move-before-index boundary");
    var checkedNetworkGate = false;
    transport.BeforeSend = async () =>
    {
        await Task.Run(() =>
        {
            var gate = WorkspaceFileGate.ForRoot(root.Root);
            var entered = Monitor.TryEnter(gate, TimeSpan.FromSeconds(1));
            Check(entered, "HTTP transport is holding the workspace file gate");
            try { checkedNetworkGate = true; }
            finally { Monitor.Exit(gate); }
        });
    };
    var synchronization = Task.Run(() => client.SyncAsync(Idle));
    bool blockedBeforePublish;
    try
    {
        await checking.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await synchronization.WaitAsync(TimeSpan.FromSeconds(2));
        blockedBeforePublish = client.Snapshot.Phase == NativeSyncPhase.WaitingForIdle && transport.Requests == initialRequests &&
            transport.CommitBodies.Count == initialCommits && !File.Exists(root.Private("outbox.json"));
    }
    finally { publish.Set(); }
    await mutation;
    Check(blockedBeforePublish, "Sync captured old library plus page tombstone during deletion transaction");
    await client.SyncAsync(Idle);
    AssertSynced(client);
    Check(checkedNetworkGate, "Network phase did not exercise unlocked file gate");
    Check(transport.CommitBodies.Count == initialCommits + 1, "Deletion was split across separate commits");
    var commit = JsonNode.Parse(transport.CommitBodies[^1])!;
    var changes = commit["changes"]!.AsArray();
    Check(changes.Count == 2 && changes.Any(change => change!["path"]!.GetValue<string>() == page && change["hash"] == null) &&
        changes.Any(change => change!["path"]!.GetValue<string>() == Index && change["hash"]!.GetValue<string>() == HashText("{\"pages\":[]}")),
        "Deletion commit did not contain matching library and page tombstone");
    var remote = await Remote();
    Check(remote.Files[page].Hash == null && remote.Files[page].Version == remote.Files[Index].Version,
        "Server observed a partial deletion transaction");
}

Task MutationEpochBarrier()
{
    using var root = new TestWorkspace();
    var epoch = NativeSyncMutationEpoch.ForRoot(root.Root);
    Check(ReferenceEquals(epoch, NativeSyncMutationEpoch.ForRoot(root.Root + Path.DirectorySeparatorChar)), "Workspace epoch is not normalized");
    var before = epoch.Capture() ?? throw new Exception("Epoch unexpectedly busy");
    var writes = 0;
    using (epoch.BeginMutation())
    {
        Check(epoch.Capture() == null, "Active mutation did not exclude snapshot");
        using (epoch.BeginMutation()) Check(!epoch.PublishIfUnchanged(before, () => writes++), "Nested mutation allowed publication");
        Check(epoch.Capture() == null, "Inner mutation ended outer exclusion");
    }
    Check(!epoch.PublishIfUnchanged(before, () => writes++), "Completed intervening transaction allowed stale publication");
    var current = epoch.Capture() ?? throw new Exception("Mutation did not release epoch");
    Check(epoch.PublishIfUnchanged(current, () => writes++), "Stable snapshot was refused");
    Check(writes == 1, "Rejected snapshot executed its publication callback");
    var lease = epoch.BeginMutation();
    lease.Dispose();
    lease.Dispose();
    Check(epoch.Capture() != null, "Repeated dispose corrupted epoch activity");
    return Task.CompletedTask;
}

async Task SnapshotAfterNetworkMutation()
{
    using var root = new TestWorkspace();
    root.Write(Index, "{\"pages\":[]}");
    var transport = new TransportFault();
    using var client = new NativeSyncClient(root.Root, endpoint, token, transport);
    await client.SyncAsync(Idle);
    AssertSynced(client);
    var page = NewPage();
    root.Write(page, "captured-before-network");
    root.Write(Index, "{\"version\":\"before-network\"}");
    var network = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    transport.BeforeSend = async () =>
    {
        if (network.TrySetResult()) await release.Task.WaitAsync(TimeSpan.FromSeconds(10));
    };
    var synchronization = Task.Run(() => client.SyncAsync(Idle));
    await network.Task.WaitAsync(TimeSpan.FromSeconds(5));
    try
    {
        await Task.Run(() =>
        {
            using var editing = NativeSyncMutationEpoch.ForRoot(root.Root).BeginMutation();
            lock (WorkspaceFileGate.ForRoot(root.Root))
            {
                root.Write(page, "edited-during-network");
                root.Write(Index, "{\"version\":\"during-network\"}");
            }
        }).WaitAsync(TimeSpan.FromSeconds(2));
    }
    finally { release.TrySetResult(); }
    await synchronization;
    var remote = await Remote();
    Check(remote.Files[page].Hash == HashText("captured-before-network") &&
        remote.Files[Index].Hash == HashText("{\"version\":\"before-network\"}"), "Network phase read changing source bytes instead of immutable snapshot");
    Check(client.Snapshot.Phase == NativeSyncPhase.Pending, "Newer local edit was not kept pending");
    await client.SyncAsync(Idle);
    AssertSynced(client);
    remote = await Remote();
    Check(remote.Files[page].Hash == HashText("edited-during-network") &&
        remote.Files[Index].Hash == HashText("{\"version\":\"during-network\"}"), "Next pass lost the concurrent local transaction");
}

async Task UnsupportedContentPaths()
{
    foreach (var unsupported in new[] { "测试本/分区/photo.png", "测试本/分区/名字:冒号.mojian", ".mojian/assets/page/deeper/asset.png" })
    {
        using var root = new TestWorkspace();
        var page = NewPage();
        root.Write(page, "canonical");
        root.Write(unsupported, "original content");
        var target = root.Root + "-clone";
        try
        {
            NativeSyncClient.CloneWorkspace(root.Root, target);
            Check(!File.Exists(Path.Combine(target, unsupported)) && File.Exists(Path.Combine(target, page)), "Clone copied unsupported content or skipped canonical pages");
            Check(root.Read(unsupported) == "original content", "Clone changed original data");
            var transport = new TransportFault();
            using var client = new NativeSyncClient(root.Root, endpoint, token, transport);
            await client.SyncAsync(Idle);
            Check(client.Snapshot.Phase == NativeSyncPhase.Error && client.Status.Contains(unsupported.Split('/')[^1].Split('/')[0], StringComparison.Ordinal) ||
                client.FailedItems.Keys.Any(path => unsupported.StartsWith(path, StringComparison.Ordinal)), "Unsupported content was not reported: " + client.Status);
            var remote = await Remote();
            Check(remote.Files.TryGetValue(page, out var uploaded) && uploaded.Hash == HashText("canonical") && !remote.Files.ContainsKey(unsupported),
                "One unsupported item stopped the other pages or was uploaded");
        }
        finally { if (Directory.Exists(target)) Directory.Delete(target, true); }
    }
    using var ignored = new TestWorkspace();
    var kept = NewPage();
    ignored.Write(kept, "canonical");
    foreach (var path in new[] { kept + ".previous", kept + ".tmp", "测试本/分区/page-sync-conflict-device.mojian", ".mojian-deletions.previous/archive/测试本/中文.mojian",
        ".mojian-corrupt.previous/测试本/page.mojian.corrupt-20261102-000000-0123456789abcdef0123456789abcdef", ".mojian/previous/测试本/a.mojian.previous", ".mojian/navigation.json" })
        ignored.Write(path, "local-only recovery");
    using var safe = new NativeSyncClient(ignored.Root, endpoint, token, new TransportFault { Offline = true });
    // `.mojian` itself is a folder item (it holds synchronized assets); its device-local contents are excluded.
    Check(safe.Scan().Keys.ToHashSet().SetEquals(new[] { "测试本", Index, "测试本/分区", kept, ".mojian" }) && safe.FailedItems.Count == 0,
        "Explicit local recovery exclusions were rejected, reported or uploaded");
}

async Task WireFixture()
{
    var fixture = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "v1-wire.json")))!;
    var expected = fixture["commit"]!.DeepClone();
    var page = expected["changes"]![0]!["path"]!.GetValue<string>();
    using var root = new TestWorkspace();
    root.Write(page, fixture["utf8"]!.GetValue<string>());
    var capture = new TransportFault();
    using var client = new NativeSyncClient(root.Root, endpoint, token, capture);
    await client.SyncAsync(Idle);
    AssertSynced(client);
    Check(capture.CommitBodies.Count == 1, "Golden fixture emitted an unexpected operation count");
    var actual = JsonNode.Parse(capture.CommitBodies[0])!;
    var pageChange = actual["changes"]!.AsArray().Single(change => change!["path"]!.GetValue<string>() == page);
    Check(JsonNode.DeepEquals(pageChange, expected["changes"]![0]), "Windows page change JSON does not match shared wire fixture");
    Check(actual.AsObject().Select(pair => pair.Key).Order().SequenceEqual(expected.AsObject().Select(pair => pair.Key).Order()), "Commit envelope changed");
    // Folder items travel in the same operation as {"hash":null,"directory":true}; the descriptor of the test notebook is a file.
    Check(actual["changes"]!.AsArray().Where(change => change != pageChange && change!["path"]!.GetValue<string>() != Index)
        .All(change => change!["hash"] == null && change["directory"]?.GetValue<bool>() == true), "Folder items are not wire folder entries");
    var remote = await Remote();
    Check(remote.Files[page].Hash == fixture["hash"]!.GetValue<string>(), "Golden fixture hash rejected by server");
    File.Delete(root.PathFor(page));
    await client.SyncAsync(Idle);
    AssertSynced(client);
    Check((await Remote()).Files[page].Hash == null, "Golden deletion fixture not interoperable");
}

async Task OrderedAddresses()
{
    using var root = new TestWorkspace();
    using (var initial = new HttpClient())
    {
        initial.DefaultRequestHeaders.Authorization = new("Bearer", token);
        var manifest = await Remote();
        root.Write(Index, await initial.GetStringAsync(endpoint + "blobs/" + manifest.Files[Index].Hash));
    }
    var requests = new List<string>();
    using var first = new AddressForwarder(endpoint, "first", requests) { Unavailable = true };
    using var second = new AddressForwarder(endpoint, "second", requests);
    using var unused = new AddressForwarder(endpoint, "unused", requests);
    var config = new NativeSyncConfiguration { Endpoint = endpoint, Token = token, Addresses = [first.Endpoint, second.Endpoint, unused.Endpoint] };
    using var client = new NativeSyncClient(root.Root, config);
    var page = NewPage(); root.Write(page, "ordered-first");
    await client.SyncAsync(Idle); AssertSynced(client);
    Check(requests[0] == "first GET" && requests.Any(r => r == "second POST") && !requests.Any(r => r.StartsWith("unused")), "Addresses were reordered or continued after success");
    requests.Clear(); first.Unavailable = false;
    root.Write(page, "ordered-prefer-first");
    await client.SyncAsync(Idle); AssertSynced(client);
    Check(requests[0] == "first GET" && requests.All(r => r.StartsWith("first")), "A new pass stuck to the previous successful fallback");
    requests.Clear(); first.DropNextCommitReply = true;
    root.Write(page, "ordered-lost-receipt");
    await client.SyncAsync(Idle); AssertSynced(client);
    Check(first.CommitBodies.Count >= 2 && second.CommitBodies.Count >= 2 && first.CommitBodies.Last() == second.CommitBodies.Last(), "Failover replaced the uncertain operation instead of replaying it");
    Check((await Remote()).Files[page].Hash == HashText("ordered-lost-receipt"), "Failover lost the saved edit");
    Check(!File.Exists(root.Private("outbox.json")) && !File.Exists(root.Private("incoming.json")), "Successful failover retained pending work");
    var serialized = File.ReadAllText(root.Private("state.json"));
    Check(serialized.Contains(endpoint) && !serialized.Contains(first.Endpoint), "Route address replaced the stable checkpoint binding");
    requests.Clear(); first.Unavailable = true; second.WrongIdentity = true;
    root.Write(page, "do-not-publish-to-other-workspace");
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Error && !requests.Any(r => r == "second POST") && !requests.Any(r => r.StartsWith("unused")), "Different workspace identity was treated as a route failure");
}

Task DecisionFixture()
{
    using var json = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "sync-decisions.json")));
    foreach (var row in json.RootElement.EnumerateArray())
    {
        string? Value(string key) => row.GetProperty(key).ValueKind == JsonValueKind.Null ? null : row.GetProperty(key).GetString();
        Check(NativeSyncDecisions.Decide(Value("base"), Value("local"), Value("remote")).ToString() == Value("decision"), "Decision table diverged");
    }
    var tombstone = new SyncEntry(null, 3);
    var local = new string('a', 64);
    Check(NativeSyncDecisions.DecideEntry(null, local, tombstone) == NativeSyncDecision.Conflict, "Unknown tombstone ancestry");
    Check(NativeSyncDecisions.DecideEntry(new(null, 2), local, tombstone) == NativeSyncDecision.Conflict, "Stale tombstone ancestry");
    Check(NativeSyncDecisions.DecideEntry(tombstone, local, tombstone) == NativeSyncDecision.Upload, "Acknowledged tombstone allows reviewed recreation");
    return Task.CompletedTask;
}

Task SharedCorePlanFixture()
{
    var fixture = JsonNode.Parse(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "sync-golden-traces.json")))!;
    foreach (var trace in fixture["traces"]!.AsArray())
    foreach (var step in trace!["steps"]!.AsArray())
    {
        var input = step!["event"]!;
        if (input["type"]!.GetValue<string>() != "plan") continue;
        var basis = input["base"]?.Deserialize<SyncEntry>(NativeSyncStorage.Json);
        var remote = input["remote"]?.Deserialize<SyncEntry>(NativeSyncStorage.Json);
        var local = input["localHash"]?.GetValue<string>();
        var actual = input["blockedGroups"]!.AsArray().Any(group => group!.GetValue<string>() == input["group"]!.GetValue<string>())
            ? "blocked" : NativeSyncDecisions.DecideEntry(basis, local, remote) switch
            {
                NativeSyncDecision.Equal => basis == remote ? "no_change" : "adopt",
                NativeSyncDecision.Receive => "download",
                NativeSyncDecision.Upload => "upload",
                NativeSyncDecision.Conflict => "conflict",
                _ => throw new InvalidOperationException()
            };
        Check(actual == step["expected"]!["decision"]!.GetValue<string>(), trace["id"]!.GetValue<string>());
        if (actual == "upload")
            Check((remote?.Version ?? 0) == step["expected"]!["expectedVersion"]!.GetValue<long>(), "Upload CAS");
    }
    return Task.CompletedTask;
}

Task MergeFixture()
{
    var cases = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "json-merge.json")))!.AsArray();
    string Canonical(JsonNode? node) => node switch
    {
        JsonObject o => "{" + string.Join(",", o.OrderBy(p => p.Key, StringComparer.Ordinal).Select(p => JsonSerializer.Serialize(p.Key) + ":" + Canonical(p.Value))) + "}",
        JsonArray a => "[" + string.Join(",", a.Select(Canonical)) + "]",
        JsonValue v when v.GetValueKind() == JsonValueKind.Number => decimal.Parse(v.ToJsonString(), System.Globalization.CultureInfo.InvariantCulture).ToString("0.############################", System.Globalization.CultureInfo.InvariantCulture),
        null => "null",
        _ => node.ToJsonString(),
    };
    foreach (var item in cases)
    {
        var name = item!["name"]!.GetValue<string>();
        var result = NativeSyncMerge.Merge(item["base"]!.AsObject(), item["local"]!.AsObject(), item["remote"]!.AsObject());
        var expected = item["result"];
        if (expected == null) Check(result == null, name);
        else if (expected is JsonValue text && text.GetValueKind() == JsonValueKind.String) Check(result is NativeSyncMerge.Result.Remote, name);
        else Check(result is NativeSyncMerge.Result.Merged merged && Canonical(merged.Document) == Canonical(expected), name + ": " + (result as NativeSyncMerge.Result.Merged)?.Document.ToJsonString());
    }
    return Task.CompletedTask;
}

async Task ExpiredMergeAncestor()
{
    using var first = new TestWorkspace(); using var second = new TestWorkspace();
    using var http = new HttpClient(); http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    var library = (await Remote()).Files.TryGetValue(Index, out var existingLibrary)
        ? await http.GetStringAsync(endpoint + "blobs/" + existingLibrary.Hash) : "{\"notebooks\":[]}";
    first.Write(Index, library); second.Write(Index, library);
    var page = NewPage(); var independent = NewPage();
    first.Write(page, "{\"revision\":1,\"strokes\":[{\"id\":\"a\"}]}");
    first.Write(independent, "base q");
    var fault = new TransportFault();
    using var one = new NativeSyncClient(first.Root, endpoint, token);
    using var two = new NativeSyncClient(second.Root, endpoint, token, fault);
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    first.Write(Index, "{\"notebooks\":[]}");
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    fault.MissingBlobHash = (await Remote()).Files[page].Hash;
    first.Write(page, "{\"revision\":2,\"strokes\":[{\"id\":\"a\"},{\"id\":\"remote\"}]}");
    second.Write(page, "{\"revision\":2,\"strokes\":[{\"id\":\"a\"},{\"id\":\"local\"}]}");
    first.Write(independent, "independent page");
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    Check(two.Snapshot.Phase == NativeSyncPhase.Conflict, "Expired ancestor did not enter review: " + two.Status);
    Check(second.Read(page).Contains("local"), "Local conflict was overwritten");
    Check(second.Read(independent) == "independent page", "Expired ancestor blocked unrelated incoming content");
}

async Task StrokeMerge()
{
    using var first = new TestWorkspace(); using var second = new TestWorkspace();
    using var http = new HttpClient(); http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    async Task<string> Blob(string path) => await http.GetStringAsync(endpoint + "blobs/" + (await Remote()).Files[path].Hash);
    first.Write(Index, await Blob(Index)); second.Write(Index, await Blob(Index));
    var page = NewPage();
    first.Write(page, "{\"revision\":1,\"updatedAt\":\"1\",\"strokes\":[{\"id\":\"a\"}]}");
    using var one = new NativeSyncClient(first.Root, endpoint, token); using var two = new NativeSyncClient(second.Root, endpoint, token);
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    first.Write(page, "{\"revision\":2,\"updatedAt\":\"2\",\"strokes\":[{\"id\":\"a\"},{\"id\":\"pc\"}]}");
    second.Write(page, "{\"revision\":2,\"updatedAt\":\"3\",\"strokes\":[{\"id\":\"a\"},{\"id\":\"tablet\"}]}");
    await one.SyncAsync(Idle);
    await two.SyncAsync(Idle);
    Check(two.Snapshot.Phase != NativeSyncPhase.Conflict, "Disjoint stroke additions became a conflict: " + two.Status);
    var merged = JsonNode.Parse(second.Read(page))!;
    Check(string.Join(",", merged["strokes"]!.AsArray().Select(s => s!["id"]!.GetValue<string>())) == "a,tablet,pc", "Merged page lost strokes: " + second.Read(page));
    await two.SyncAsync(Idle); await one.SyncAsync(Idle);
    Check(first.Read(page) == second.Read(page), "Merged page did not propagate");
    // A resave that only bumps revision/updatedAt takes the server page without an upload.
    var server = first.Read(page);
    var resaved = JsonNode.Parse(server)!.AsObject();
    resaved["revision"] = resaved["revision"]!.GetValue<long>() + 1; resaved["updatedAt"] = "9";
    second.Write(page, resaved.ToJsonString());
    var later = JsonNode.Parse(server)!.AsObject();
    later["strokes"]!.AsArray().Add(new JsonObject { ["id"] = "later" }); later["revision"] = later["revision"]!.GetValue<long>() + 1;
    first.Write(page, later.ToJsonString());
    await one.SyncAsync(Idle);
    await two.SyncAsync(Idle);
    Check(two.Snapshot.Phase != NativeSyncPhase.Conflict && second.Read(page) == first.Read(page), "Volatile-only resave did not fast-forward: " + two.Status);
}

string CanonicalJson(JsonNode? node) => node switch
{
    JsonObject o => "{" + string.Join(",", o.OrderBy(p => p.Key, StringComparer.Ordinal).Select(p => JsonSerializer.Serialize(p.Key) + ":" + CanonicalJson(p.Value))) + "}",
    JsonArray a => "[" + string.Join(",", a.Select(CanonicalJson)) + "]",
    null => "null",
    _ => node.ToJsonString(),
};

Task ReviewFixture()
{
    foreach (var item in JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "json-review.json")))!.AsArray())
    {
        var name = item!["name"]!.GetValue<string>();
        var (local, remote) = (item["local"]!.AsObject(), item["remote"]!.AsObject());
        Check(CanonicalJson(NativeSyncMerge.Union(local, remote)) == CanonicalJson(item["union"]), name + ": " + NativeSyncMerge.Union(local, remote).ToJsonString());
        var summary = NativeSyncMerge.Summarize(local, remote); var expected = item["summary"]!;
        Check(summary == new NativeSyncMerge.Summary(expected["localOnly"]!.GetValue<int>(), expected["remoteOnly"]!.GetValue<int>(), expected["changed"]!.GetValue<int>()), name + ": " + summary);
    }
    return Task.CompletedTask;
}

async Task ConflictReviewFlow()
{
    using var first = new TestWorkspace(); using var second = new TestWorkspace();
    using var http = new HttpClient(); http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    var library = await http.GetStringAsync(endpoint + "blobs/" + (await Remote()).Files[Index].Hash);
    first.Write(Index, library); second.Write(Index, library);
    var (p, q) = (NewPage(), NewPage());
    first.Write(p, "{\"revision\":1,\"strokes\":[{\"id\":\"a\",\"color\":1}]}"); first.Write(q, "{\"revision\":1,\"strokes\":[{\"id\":\"b\",\"color\":1}]}");
    using var one = new NativeSyncClient(first.Root, endpoint, token); using var two = new NativeSyncClient(second.Root, endpoint, token);
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    first.Write(Index, """{"notebooks":[]}""");
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    first.Write(p, "{\"revision\":2,\"strokes\":[{\"id\":\"a\",\"color\":3},{\"id\":\"theirs\"}]}"); first.Write(q, "{\"revision\":2,\"strokes\":[{\"id\":\"b\",\"color\":3}]}");
    second.Write(p, "{\"revision\":2,\"strokes\":[{\"id\":\"a\",\"color\":2},{\"id\":\"mine\"}]}"); second.Write(q, "{\"revision\":2,\"strokes\":[{\"id\":\"b\",\"color\":2}]}");
    await one.SyncAsync(Idle); await two.SyncAsync(Idle);
    var review = two.ConflictReview().ToDictionary(item => item.Path);
    Check(two.Snapshot.Phase == NativeSyncPhase.Conflict && review.Count == 2, "Expected two reviewed conflicts: " + two.Status);
    Check(review[p] is { Action: "modify", LocalOnly: 1, RemoteOnly: 1, Changed: 1 }, "Wrong summary: " + review[p]);
    Check(await two.ResolveConflictsAsync(new Dictionary<string, NativeSyncChoice> { [p] = NativeSyncChoice.Both }, Idle), "Keep both failed: " + two.Status);
    Check(string.Join(",", JsonNode.Parse(second.Read(p))!["strokes"]!.AsArray().Select(s => s!["id"]!.GetValue<string>())) == "a,mine,theirs", "Keep both lost strokes: " + second.Read(p));
    Check(two.ConflictReview().Select(item => item.Path).SequenceEqual([q]) && two.Snapshot.Phase == NativeSyncPhase.Conflict, "Partial resolution dropped the other conflict");
    Check(await two.ResolveConflictsAsync(new Dictionary<string, NativeSyncChoice> { [q] = NativeSyncChoice.Remote }, Idle), "Remote choice failed: " + two.Status);
    Check(second.Read(q) == first.Read(q) && two.ConflictReview().Count == 0, "Remote choice did not take the server page");
    await two.SyncAsync(Idle); await one.SyncAsync(Idle);
    Check(two.Snapshot.Phase != NativeSyncPhase.Conflict && first.Read(p) == second.Read(p), "Resolved page did not upload: " + two.Status);
}

async Task RejectedCommit()
{
    using var root = new TestWorkspace();
    // Earlier scenarios changed the shared server index; start from it so only the CAS is under test.
    using (var initial = new HttpClient())
    {
        initial.DefaultRequestHeaders.Authorization = new("Bearer", token);
        root.Write(Index, await initial.GetStringAsync(endpoint + "blobs/" + (await Remote()).Files[Index].Hash));
    }
    var fault = new TransportFault { RejectCommitCode = "version_conflict" };
    using var client = new NativeSyncClient(root.Root, endpoint, token, fault);
    var page = NewPage(); root.Write(page, "racing edit");
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Pending && !File.Exists(root.Private("outbox.json")), $"Rejected CAS became a permanent conflict: {client.Snapshot.Phase} {client.Status} outbox={File.Exists(root.Private("outbox.json"))}");
    Check(!Directory.Exists(root.Private("conflicts")), "Rejected CAS captured unrelated paths");
    await client.SyncAsync(Idle); AssertSynced(client);
    root.Write(page, "uncertain identity"); fault.RejectCommitCode = "operation_id_reused";
    await client.SyncAsync(Idle);
    Check(client.Snapshot.Phase == NativeSyncPhase.Error && File.Exists(root.Private("outbox.json")), "Non-CAS rejection discarded the original operation");
    var operation = NativeSyncStorage.Read<NativeSyncOutbox>(root.Private("outbox.json")).OperationId;
    await client.SyncAsync(Idle); AssertSynced(client);
    Check((await Remote()).Files[page].Hash == HashText("uncertain identity"), "Retried operation lost local data");
}

async Task DeletedLibrary()
{
    using var root = new TestWorkspace();
    await AdoptSharedIndex(root);
    var page = NewPage();
    root.Write(page, "kept page");
    using (var client = new NativeSyncClient(root.Root, endpoint, token))
    {
        await client.SyncAsync(Idle); AssertSynced(client);
    }
    // An unmounted or emptied library folder must not publish the deletion of every notebook.
    var notebooks = Directory.EnumerateDirectories(root.Root).Select(Path.GetFileName).Where(name => !name!.StartsWith('.')).ToArray();
    Directory.CreateDirectory(root.Private("moved-away"));
    foreach (var name in notebooks) Directory.Move(root.PathFor(name!), root.Private("moved-away", name!));
    using (var client = new NativeSyncClient(root.Root, endpoint, token))
    {
        await client.SyncAsync(Idle);
        Check(client.Snapshot.Phase == NativeSyncPhase.Error && client.Status.Contains("笔记库缺失"), "Missing library was not refused: " + client.Status);
    }
    Check((await Remote()).Files[page].Hash == HashText("kept page"), "A missing local library deleted remote pages");
    foreach (var name in notebooks) Directory.Move(root.Private("moved-away", name!), root.PathFor(name!));
}

async Task RecoveryWithoutCredentials()
{
    using var root = new TestWorkspace();
    var page = NewPage(); StageIncoming(root, new() { [page] = "downloaded page" });
    using var recovery = NativeSyncClient.ForLocalRecovery(root.Root);
    Check(await recovery.RecoverPendingIncomingAsync(Idle), "Credential-free recovery failed");
    Check(root.Read(page) == "downloaded page", "Recovery lost the staged page");
    try { await recovery.SyncAsync(Idle); throw new Exception("Recovery client permitted networking"); }
    catch (InvalidOperationException) { }
}

async Task<string> UniqueNotebook() => "本-" + Guid.NewGuid().ToString("N")[..8];

// Scenarios share one server: a new workspace first takes the server's copy of the shared test notebook descriptor.
async Task AdoptSharedIndex(params TestWorkspace[] roots)
{
    if ((await Remote()).Files.GetValueOrDefault(Index)?.Hash is not { } shared || !NativeSyncIdentity.IsBlob(shared)) return;
    using var http = new HttpClient();
    http.DefaultRequestHeaders.Authorization = new("Bearer", token);
    var text = await http.GetStringAsync(endpoint + "blobs/" + shared);
    foreach (var root in roots) root.Write(Index, text);
}

async Task FolderItems()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    await AdoptSharedIndex(first, second);
    var book = await UniqueNotebook();
    Directory.CreateDirectory(first.PathFor(book + "/空分区/子分区"));
    first.Write(book + "/旧分区/页.mojian", PageFile(Guid.NewGuid().ToString("N"), "page"));
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await writer.SyncAsync(Idle); await reader.SyncAsync(Idle);
    Check(Directory.Exists(second.PathFor(book + "/空分区/子分区")), "Empty folders did not replicate");
    Directory.Move(first.PathFor(book + "/旧分区"), first.PathFor(book + "/新分区"));
    Directory.Delete(first.PathFor(book + "/空分区"), recursive: true);
    await writer.SyncAsync(Idle); AssertSynced(writer);
    await reader.SyncAsync(Idle); AssertSynced(reader);
    Check(File.Exists(second.PathFor(book + "/新分区/页.mojian")) && !Directory.Exists(second.PathFor(book + "/旧分区")) &&
        !Directory.Exists(second.PathFor(book + "/空分区")), "Folder rename or deletion left the old folder behind");
}

async Task FolderWithLocalContent()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    await AdoptSharedIndex(first, second);
    var book = await UniqueNotebook();
    first.Write(book + "/共享/a.mojian", PageFile(Guid.NewGuid().ToString("N"), "a"));
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await writer.SyncAsync(Idle); await reader.SyncAsync(Idle);
    second.Write(book + "/共享/离线新页.mojian", PageFile(Guid.NewGuid().ToString("N"), "offline"));
    Directory.Delete(first.PathFor(book + "/共享"), recursive: true);
    await writer.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    await reader.SyncAsync(Idle);
    Check(File.Exists(second.PathFor(book + "/共享/离线新页.mojian")) && !File.Exists(second.PathFor(book + "/共享/a.mojian")), "Local content in a deleted folder was lost");
    await writer.SyncAsync(Idle);
    Check(File.Exists(first.PathFor(book + "/共享/离线新页.mojian")), "Kept folder was not uploaded again");
}

async Task CaseOnlyRename()
{
    using var first = new TestWorkspace();
    using var second = new TestWorkspace();
    await AdoptSharedIndex(first, second);
    var book = await UniqueNotebook();
    first.Write(book + "/Folder/Note.mojian", PageFile(Guid.NewGuid().ToString("N"), "note body"));
    using var writer = new NativeSyncClient(first.Root, endpoint, token);
    using var reader = new NativeSyncClient(second.Root, endpoint, token);
    await writer.SyncAsync(Idle); await reader.SyncAsync(Idle);
    File.Move(first.PathFor(book + "/Folder/Note.mojian"), first.PathFor(book + "/Folder/note.tmp-case"));
    File.Move(first.PathFor(book + "/Folder/note.tmp-case"), first.PathFor(book + "/Folder/note.mojian"));
    Directory.Move(first.PathFor(book + "/Folder"), first.PathFor(book + "/folder-tmp"));
    Directory.Move(first.PathFor(book + "/folder-tmp"), first.PathFor(book + "/folder"));
    await writer.SyncAsync(Idle); AssertSynced(writer);
    await reader.SyncAsync(Idle); AssertSynced(reader);
    Check(Directory.EnumerateFileSystemEntries(second.PathFor(book)).Select(Path.GetFileName).SequenceEqual(["folder"]), "Folder case did not follow");
    Check(Directory.EnumerateFiles(second.PathFor(book + "/folder")).Select(Path.GetFileName).SequenceEqual(["note.mojian"]), "Page case did not follow");
    Check(second.Read(book + "/folder/note.mojian").Contains("note body"), "Case-only rename lost content");
}

async Task BatchedUpload()
{
    using var root = new TestWorkspace();
    await AdoptSharedIndex(root);
    var book = await UniqueNotebook();
    for (var i = 0; i < 7; i++) root.Write($"{book}/批量/{i}.mojian", PageFile(Guid.NewGuid().ToString("N"), "p" + i));
    using var client = new NativeSyncClient(root.Root, endpoint, token) { BatchLimit = 3 };
    for (var pass = 0; pass < 5 && client.Snapshot.Phase != NativeSyncPhase.Synced; pass++) await client.SyncAsync(Idle);
    AssertSynced(client);
    var remote = await Remote();
    Check(Enumerable.Range(0, 7).All(i => remote.Files.GetValueOrDefault($"{book}/批量/{i}.mojian")?.Hash != null), "Batched upload lost changes");
}

sealed class AddressForwarder : IDisposable
{
    private readonly System.Net.HttpListener listener = new();
    private readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false });
    private readonly string target, name;
    private readonly List<string> requests;
    private readonly Task worker;
    public string Endpoint { get; }
    public bool Unavailable { get; set; }
    public bool WrongIdentity { get; set; }
    public bool DropNextCommitReply { get; set; }
    public List<string> CommitBodies { get; } = new();
    public AddressForwarder(string target, string name, List<string> requests)
    {
        this.target = target; this.name = name; this.requests = requests;
        var port = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        port.Start(); var number = ((System.Net.IPEndPoint)port.LocalEndpoint).Port; port.Stop();
        var origin = $"http://127.0.0.1:{number}/"; Endpoint = origin + "mojian-sync/v1/";
        listener.Prefixes.Add(origin); listener.Start();
        worker = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                System.Net.HttpListenerContext context;
                try { context = await listener.GetContextAsync(); } catch (System.Net.HttpListenerException) { break; } catch (ObjectDisposedException) { break; }
                var incoming = context.Request;
                requests.Add(name + " " + incoming.HttpMethod);
                if (Unavailable) { context.Response.StatusCode = 503; context.Response.Close(); continue; }
                var relative = incoming.Url!.PathAndQuery.Split("/mojian-sync/v1/", 2).Last();
                using var message = new HttpRequestMessage(new HttpMethod(incoming.HttpMethod), target + relative);
                if (incoming.Headers["Authorization"] is { } authorization) message.Headers.TryAddWithoutValidation("Authorization", authorization);
                if (incoming.Headers["Range"] is { } range) message.Headers.TryAddWithoutValidation("Range", range);
                if (incoming.HasEntityBody)
                {
                    using var memory = new MemoryStream(); await incoming.InputStream.CopyToAsync(memory);
                    var body = memory.ToArray();
                    if (incoming.HttpMethod == "POST") CommitBodies.Add(Encoding.UTF8.GetString(body));
                    message.Content = new ByteArrayContent(body);
                    if (incoming.ContentType != null) message.Content.Headers.ContentType = System.Net.Http.Headers.MediaTypeHeaderValue.Parse(incoming.ContentType);
                    if (incoming.Headers["Content-Range"] is { } contentRange) message.Content.Headers.TryAddWithoutValidation("Content-Range", contentRange);
                }
                using var reply = await http.SendAsync(message);
                var bytes = await reply.Content.ReadAsByteArrayAsync();
                if (WrongIdentity && (relative.StartsWith("manifest", StringComparison.Ordinal) || relative.StartsWith("changes", StringComparison.Ordinal)))
                { var node = JsonNode.Parse(bytes)!; node["workspaceId"] = Guid.NewGuid().ToString("N"); bytes = Encoding.UTF8.GetBytes(node.ToJsonString()); }
                if (DropNextCommitReply && incoming.HttpMethod == "POST")
                { DropNextCommitReply = false; context.Response.StatusCode = 503; context.Response.Close(); continue; }
                context.Response.StatusCode = (int)reply.StatusCode; context.Response.ContentType = "application/json";
                if (reply.Content.Headers.ContentRange != null) context.Response.Headers["Content-Range"] = reply.Content.Headers.ContentRange.ToString();
                context.Response.ContentLength64 = bytes.Length;
                await context.Response.OutputStream.WriteAsync(bytes); context.Response.Close();
            }
        });
    }
    public void Dispose() { listener.Close(); http.Dispose(); worker.GetAwaiter().GetResult(); }
}

sealed class TestWorkspace : IDisposable
{
    public string Root { get; } = Path.Combine(Path.GetTempPath(), ".agents", "mojian-native-client-" + Guid.NewGuid().ToString("N"));
    public TestWorkspace()
    {
        Directory.CreateDirectory(Root);
        File.WriteAllText(Path.Combine(Root, ".mojian-native-workspace"), "isolated test copy");
        Directory.CreateDirectory(Path.Combine(Root, "测试本"));
        File.WriteAllText(Path.Combine(Root, "测试本", ".mojian-folder.json"), "{}");
    }
    public string PathFor(string relative) => Path.Combine(Root, relative.Replace('/', Path.DirectorySeparatorChar));
    public string Private(params string[] parts) => Path.Combine(new[] { Root, ".mojian-sync" }.Concat(parts).ToArray());
    public void Write(string path, string value)
    {
        var file = PathFor(path);
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        NativeSyncStorage.Write(file, Encoding.UTF8.GetBytes(value));
    }
    public string Read(string path) => File.ReadAllText(PathFor(path));
    public void Dispose() => Directory.Delete(Root, true);
}

sealed class TransportFault : HttpMessageHandler
{
    private readonly HttpMessageInvoker _inner = new(new HttpClientHandler { AllowAutoRedirect = false });
    public string? RejectCommitCode { get; set; }
    public bool Offline { get; set; }
    public bool DropCommitReply { get; set; }
    public string? MissingBlobHash { get; set; }
    public int Requests { get; private set; }
    public int Uploads { get; private set; }
    public Func<SyncManifest, SyncManifest>? ChangeManifest { get; set; }
    public Func<Task>? BeforeSend { get; set; }
    public List<string> CommitBodies { get; } = new();
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (ChangeManifest != null && (request.RequestUri!.AbsolutePath.EndsWith("/manifest/pages") || request.RequestUri.AbsolutePath.EndsWith("/changes")))
            return new HttpResponseMessage(System.Net.HttpStatusCode.NotFound);
        if (BeforeSend != null) await BeforeSend();
        Requests++;
        if (MissingBlobHash != null && request.RequestUri!.AbsolutePath.EndsWith("/blobs/" + MissingBlobHash, StringComparison.Ordinal))
            return new HttpResponseMessage(System.Net.HttpStatusCode.NotFound);
        if (RejectCommitCode is { } rejected && request.Method == HttpMethod.Post)
        {
            RejectCommitCode = null;
            return new HttpResponseMessage(System.Net.HttpStatusCode.Conflict) { Content = JsonContent.Create(new { error = rejected }) };
        }
        if (Offline) throw new HttpRequestException("Simulated offline transport");
        if (request.Method == HttpMethod.Put) Uploads++;
        if (request.Method == HttpMethod.Post) CommitBodies.Add(await request.Content!.ReadAsStringAsync(cancellationToken));
        var response = await _inner.SendAsync(request, cancellationToken);
        if (ChangeManifest != null && request.RequestUri!.AbsolutePath.EndsWith("/manifest", StringComparison.Ordinal))
        {
            var manifest = await response.Content.ReadFromJsonAsync<SyncManifest>(NativeSyncStorage.Json, cancellationToken) ?? throw new Exception("Missing test manifest");
            response.Dispose();
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = JsonContent.Create(ChangeManifest(manifest), options: NativeSyncStorage.Json) };
        }
        if (DropCommitReply && request.Method == HttpMethod.Post)
        {
            DropCommitReply = false;
            response.Dispose();
            throw new HttpRequestException("Simulated response loss after durable commit");
        }
        return response;
    }
    protected override void Dispose(bool disposing) { if (disposing) _inner.Dispose(); base.Dispose(disposing); }
}
