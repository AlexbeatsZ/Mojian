using System.Text;
using System.Text.Json;
using System.Security.Cryptography;
using System.IO;

namespace Notein_win.Services;

public sealed record SyncEntry(string? Hash, long Version);
public sealed record SyncManifest(string WorkspaceId, long Revision, Dictionary<string, SyncEntry> Files);
public sealed record SyncChange(string Path, long ExpectedVersion, string? Hash);
public sealed record SyncReceipt(string WorkspaceId, string OperationId, long Revision, Dictionary<string, SyncEntry> Files);
public sealed record SyncFile(string Hash, long Length, long Modified);

public enum NativeSyncPhase { Pending, Checking, Uploading, Receiving, Synced, Offline, Conflict, Paused, WaitingForIdle, Error }
public sealed record NativeSyncStatus(NativeSyncPhase Phase, string Message, int PendingFiles = 0,
    string? ConflictDirectory = null, DateTimeOffset? LastSuccessUtc = null);

public sealed class NativeSyncState
{
    public int Format { get; set; } = 1;
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public string Endpoint { get; set; } = "";
    public string WorkspaceId { get; set; } = "";
    public long Revision { get; set; }
    public Dictionary<string, SyncEntry> Files { get; set; } = new(StringComparer.Ordinal);
    public string? ConflictDirectory { get; set; }
    public DateTimeOffset? LastSuccessUtc { get; set; }
}

public sealed class NativeSyncConfiguration
{
    public string Endpoint { get; set; } = "";
    public string Token { get; set; } = "";
    public bool Enabled { get; set; }
    public static string FilePath(string root) => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Mojian", "native-sync", Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(root).ToUpperInvariant()))).ToLowerInvariant() + ".json");
    public static NativeSyncConfiguration Load(string root) => File.Exists(FilePath(root))
        ? NativeSyncStorage.Read<NativeSyncConfiguration>(FilePath(root)) : new();
    public void Save(string root) => NativeSyncStorage.Write(FilePath(root), JsonSerializer.SerializeToUtf8Bytes(this, NativeSyncStorage.Json));
}

internal sealed class NativeSyncOutbox
{
    public string WorkspaceId { get; set; } = "";
    public string DeviceId { get; set; } = "";
    public string OperationId { get; set; } = Guid.NewGuid().ToString("N");
    public List<SyncChange> Changes { get; set; } = new();
    public bool Submitted { get; set; }
}

internal sealed record NativeSyncIncomingFile(string? ExpectedHash, SyncEntry Target);
internal sealed record NativeSyncIncoming(string WorkspaceId, long Revision, Dictionary<string, NativeSyncIncomingFile> Files);
