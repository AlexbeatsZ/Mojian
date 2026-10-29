using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Services;

Console.OutputEncoding = Encoding.UTF8;
var endpoint = Environment.GetEnvironmentVariable("MOJIAN_TEST_SYNC_URL") ?? throw new InvalidOperationException("Loopback test server required");
var token = Environment.GetEnvironmentVariable("MOJIAN_TEST_SYNC_TOKEN") ?? throw new InvalidOperationException("Test token required");
var passed = 0;
await Run("offline outbox survives restart and uploads without touching local content", OfflineOutbox);
await Run("lost commit response replays stable operation without a second revision", LostReceipt);
await Run("newer local edits survive lost-response retry", NewerEdit);
await Run("two writers preserve recoverable conflict copies", Conflict);
await Run("local deletion versus remote edit remains a recoverable conflict", DeletionConflict);
await Run("remote tombstones delete only clean local content", Tombstones);
await Run("partly published incoming journal replays offline with duplicate hashes", IncomingRecovery);
await Run("incoming recovery refuses unexpected local edits and never networks", IncomingConflict);
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
Console.WriteLine($"Windows native sync: {passed} isolated scenarios passed");

async Task Run(string name, Func<Task> scenario)
{
    await scenario();
    passed++;
    Console.WriteLine("PASS " + name);
}
void Check(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}
string NewPage() => "notebooks/testbook/pages/" + Guid.NewGuid().ToString("N") + ".json";
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
    Check(!await restarted.RecoverPendingIncomingAsync(Idle), "Conflicting partial library allowed startup");
    Check(restarted.HasPendingIncoming && restarted.Snapshot.Phase == NativeSyncPhase.Conflict, "Incoming conflict not blocked");
    Check(second.Read(page) == "unexpected offline edit" && offline.Requests == 0, "Offline recovery changed local data or performed network");
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
        Check(Directory.EnumerateFiles(Path.GetDirectoryName(second.PathFor(page))!, "*.tmp").Any(), "Replacement was not prepared before UI publication");
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
    Check(!await reader.RecoverPendingIncomingAsync(Idle) && reader.Snapshot.Phase == NativeSyncPhase.Conflict, "Deferred edit was not preserved as a recoverable conflict");
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
    StageIncoming(root, new() { ["library.json"] = "new index", [firstPage] = "new first", [secondPage] = "new second" });
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
        Check(root.Read("library.json") == "{}", "Library was published before all page replacements completed");
        Check(File.ReadAllBytes(root.Private("state.json")).SequenceEqual(checkpoint), "Partial publication advanced the checkpoint");
        Check(File.ReadAllText(root.Private("incoming-backups", firstPage)) == "old first", "Original first-page recovery copy missing");
        AssertIncomingTemporariesRemoved(root);
    }
    var restartedOffline = new TransportFault { Offline = true };
    using var restarted = new NativeSyncClient(root.Root, endpoint, token, restartedOffline);
    Check(await restarted.RecoverPendingIncomingAsync(Idle), "Offline replay failed: " + restarted.Status);
    Check(!restarted.HasPendingIncoming && root.Read(firstPage) == "new first" && root.Read(secondPage) == "new second" && root.Read("library.json") == "new index",
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
        Check(File.Exists(Path.Combine(target, "library.json")), "Clone incomplete");
        Check(!NativeSyncConfiguration.Load(target).Enabled, "Clone default enabled sync");
        Directory.CreateDirectory(Path.Combine(target, "nested", ".stfolder"));
        var blocked = false;
        try { NativeSyncClient.RequireDedicatedWorkspace(target); } catch (IOException) { blocked = true; }
        Check(blocked, "Nested Syncthing folder accepted");
        Directory.Delete(Path.Combine(target, "nested"), true);
        using var first = new NativeSyncClient(target, endpoint, token);
        blocked = false;
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
    root.Write("library.json", oldLibrary);
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
            root.Write("library.json", "{\"pages\":[]}");
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
        changes.Any(change => change!["path"]!.GetValue<string>() == "library.json" && change["hash"]!.GetValue<string>() == HashText("{\"pages\":[]}")),
        "Deletion commit did not contain matching library and page tombstone");
    var remote = await Remote();
    Check(remote.Files[page].Hash == null && remote.Files[page].Version == remote.Files["library.json"].Version,
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
    root.Write("library.json", "{\"pages\":[]}");
    var transport = new TransportFault();
    using var client = new NativeSyncClient(root.Root, endpoint, token, transport);
    await client.SyncAsync(Idle);
    AssertSynced(client);
    var page = NewPage();
    root.Write(page, "captured-before-network");
    root.Write("library.json", "{\"version\":\"before-network\"}");
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
                root.Write("library.json", "{\"version\":\"during-network\"}");
            }
        }).WaitAsync(TimeSpan.FromSeconds(2));
    }
    finally { release.TrySetResult(); }
    await synchronization;
    var remote = await Remote();
    Check(remote.Files[page].Hash == HashText("captured-before-network") &&
        remote.Files["library.json"].Hash == HashText("{\"version\":\"before-network\"}"), "Network phase read changing source bytes instead of immutable snapshot");
    Check(client.Snapshot.Phase == NativeSyncPhase.Pending, "Newer local edit was not kept pending");
    await client.SyncAsync(Idle);
    AssertSynced(client);
    remote = await Remote();
    Check(remote.Files[page].Hash == HashText("edited-during-network") &&
        remote.Files["library.json"].Hash == HashText("{\"version\":\"during-network\"}"), "Next pass lost the concurrent local transaction");
}

async Task UnsupportedContentPaths()
{
    foreach (var unsupported in new[] { "notebooks/book/assets/page/asset.中文", "notebooks/book/pages/中文.json", "notebooks/book/assets/page/deeper/asset.png" })
    {
        using var root = new TestWorkspace();
        root.Write(unsupported, "original content");
        root.Write("library.json", JsonSerializer.Serialize(new { referenced = unsupported }));
        var originalLibrary = root.Read("library.json");
        var target = root.Root + "-clone";
        try
        {
            var rejected = false;
            try { NativeSyncClient.CloneWorkspace(root.Root, target); } catch (IOException) { rejected = true; }
            Check(rejected && !File.Exists(Path.Combine(target, ".mojian-native-workspace")), "Unsupported content silently cloned with activation marker");
            Check(root.Read(unsupported) == "original content" && root.Read("library.json") == originalLibrary, "Rejected clone changed original data");
            var transport = new TransportFault();
            using var client = new NativeSyncClient(root.Root, endpoint, token, transport);
            await client.SyncAsync(Idle);
            Check(client.Snapshot.Phase == NativeSyncPhase.Error && transport.Requests == 0 && !File.Exists(root.Private("outbox.json")), "Unsupported content was silently omitted from outgoing snapshot");
        }
        finally { if (Directory.Exists(target)) Directory.Delete(target, true); }
    }
    using var ignored = new TestWorkspace();
    var page = NewPage();
    ignored.Write(page, "canonical");
    foreach (var path in new[] { page + ".previous", page + ".tmp", "notebooks/book/pages/page-sync-conflict-device.json", ".mojian-deletions.previous/archive/notebooks/book/pages/中文.json" })
        ignored.Write(path, "local-only recovery");
    using var safe = new NativeSyncClient(ignored.Root, endpoint, token, new TransportFault { Offline = true });
    Check(safe.Scan().Keys.ToHashSet().SetEquals(new[] { "library.json", page }), "Explicit local recovery exclusions were rejected or uploaded");
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
    foreach (var field in new[] { "workspaceId", "deviceId", "operationId" }) expected[field] = actual[field]!.DeepClone();
    Check(JsonNode.DeepEquals(actual, expected), "Windows commit JSON does not match shared wire fixture");
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
        root.Write("library.json", await initial.GetStringAsync(endpoint + "blobs/" + manifest.Files["library.json"].Hash));
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
                var relative = incoming.Url!.AbsolutePath.Split("/mojian-sync/v1/", 2).Last();
                using var message = new HttpRequestMessage(new HttpMethod(incoming.HttpMethod), target + relative);
                if (incoming.Headers["Authorization"] is { } authorization) message.Headers.TryAddWithoutValidation("Authorization", authorization);
                if (incoming.HasEntityBody)
                {
                    using var memory = new MemoryStream(); await incoming.InputStream.CopyToAsync(memory);
                    var body = memory.ToArray();
                    if (incoming.HttpMethod == "POST") CommitBodies.Add(Encoding.UTF8.GetString(body));
                    message.Content = new ByteArrayContent(body);
                    if (incoming.ContentType != null) message.Content.Headers.ContentType = System.Net.Http.Headers.MediaTypeHeaderValue.Parse(incoming.ContentType);
                }
                using var reply = await http.SendAsync(message);
                var bytes = await reply.Content.ReadAsByteArrayAsync();
                if (WrongIdentity && relative == "manifest")
                { var node = JsonNode.Parse(bytes)!; node["workspaceId"] = Guid.NewGuid().ToString("N"); bytes = Encoding.UTF8.GetBytes(node.ToJsonString()); }
                if (DropNextCommitReply && incoming.HttpMethod == "POST")
                { DropNextCommitReply = false; context.Response.StatusCode = 503; context.Response.Close(); continue; }
                context.Response.StatusCode = (int)reply.StatusCode; context.Response.ContentType = "application/json";
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
        File.WriteAllText(Path.Combine(Root, "library.json"), "{}");
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
    public bool Offline { get; set; }
    public bool DropCommitReply { get; set; }
    public int Requests { get; private set; }
    public int Uploads { get; private set; }
    public Func<SyncManifest, SyncManifest>? ChangeManifest { get; set; }
    public Func<Task>? BeforeSend { get; set; }
    public List<string> CommitBodies { get; } = new();
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (BeforeSend != null) await BeforeSend();
        Requests++;
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
