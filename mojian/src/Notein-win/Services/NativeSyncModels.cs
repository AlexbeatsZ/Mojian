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
    public const string DefaultEndpoint = "https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/";
    public string Endpoint { get; set; } = DefaultEndpoint;
    public string Token { get; set; } = "";
    public bool Enabled { get; set; }
    public string[] Addresses { get; set; } = [];
    public Dictionary<string, string> CertificatePins { get; set; } = new(StringComparer.Ordinal);
    [System.Text.Json.Serialization.JsonIgnore]
    public string[] ServerAddresses => (Addresses.Length == 0 ? [Endpoint] : Addresses).Select(NormalizeAddress).ToArray();
    public static string NormalizeAddress(string address)
    {
        address = address.Trim();
        if (!address.Contains("://", StringComparison.Ordinal)) address = "https://" + address;
        var uri = new Uri(address, UriKind.Absolute);
        if ((uri.Scheme != "https" && !(uri.Scheme == "http" && uri.IsLoopback)) ||
            uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0)
            throw new ArgumentException("同步地址必须使用 HTTPS，且不能包含凭据、查询或片段");
        if (uri.AbsolutePath == "/") uri = new Uri(uri, "mojian-sync/v1/");
        return uri.AbsoluteUri.TrimEnd('/') + "/";
    }
    public static string[] ParseAddresses(string text)
    {
        var addresses = text.Split(['\r', '\n'], StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).Select(NormalizeAddress).ToArray();
        if (addresses.Length == 0) throw new ArgumentException("请至少输入一个同步地址");
        return addresses;
    }
    public static string FilePath(string root) => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Mojian", "native-sync", Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(root).ToUpperInvariant()))).ToLowerInvariant() + ".json");
    public static NativeSyncConfiguration Load(string root) => File.Exists(FilePath(root))
        ? NativeSyncStorage.Read<NativeSyncConfiguration>(FilePath(root)) : new();
    public void Save(string root)
    {
        if (Enabled) { _ = ServerAddresses; if (string.IsNullOrWhiteSpace(Token) || Token.Contains('\r') || Token.Contains('\n')) throw new ArgumentException("请输入有效访问令牌"); }
        NativeSyncStorage.Write(FilePath(root), JsonSerializer.SerializeToUtf8Bytes(this, NativeSyncStorage.Json));
    }
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
