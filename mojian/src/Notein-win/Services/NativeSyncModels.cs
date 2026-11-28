using System.Text;
using System.Text.Json;
using System.Security.Cryptography;
using System.IO;

namespace Notein_win.Services;

/// <summary>
/// A file (<c>Hash</c> is its SHA-256), a folder (<c>Hash</c> is <see cref="NativeSyncIdentity.Directory"/>) or a tombstone
/// (<c>Hash</c> null). On the wire a folder is <c>{"hash":null,"directory":true}</c> (file-sync-core 0.4.0); inside the
/// client it is one identity string so every comparison treats files and folders alike.
/// </summary>
[System.Text.Json.Serialization.JsonConverter(typeof(SyncEntryJsonConverter))]
public sealed record SyncEntry(string? Hash, long Version);

public static class NativeSyncIdentity
{
    public const string Directory = "directory";
    /// <summary>True when the identity names stored bytes (a blob), not a folder or tombstone.</summary>
    public static bool IsBlob(string? hash) => hash != null && hash != Directory;
}

internal sealed class SyncEntryJsonConverter : System.Text.Json.Serialization.JsonConverter<SyncEntry>
{
    public override SyncEntry Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        using var document = JsonDocument.ParseValue(ref reader);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object) throw new JsonException("同步条目无效");
        string? hash = null; long version = 0; var directory = false;
        foreach (var property in root.EnumerateObject())
        {
            if (property.NameEquals("hash")) hash = property.Value.ValueKind == JsonValueKind.Null ? null : property.Value.GetString();
            else if (property.NameEquals("version")) version = property.Value.GetInt64();
            else if (property.NameEquals("directory")) directory = property.Value.ValueKind == JsonValueKind.True;
        }
        if (directory && hash != null) throw new JsonException("文件夹条目不能带哈希");
        if (hash == NativeSyncIdentity.Directory) throw new JsonException("同步条目无效");
        return new SyncEntry(directory ? NativeSyncIdentity.Directory : hash, version);
    }

    public override void Write(Utf8JsonWriter writer, SyncEntry value, JsonSerializerOptions options)
    {
        writer.WriteStartObject();
        if (value.Hash == NativeSyncIdentity.Directory) { writer.WriteNull("hash"); writer.WriteNumber("version", value.Version); writer.WriteBoolean("directory", true); }
        else { if (value.Hash == null) writer.WriteNull("hash"); else writer.WriteString("hash", value.Hash); writer.WriteNumber("version", value.Version); }
        writer.WriteEndObject();
    }
}

internal sealed class SyncChangeJsonConverter : System.Text.Json.Serialization.JsonConverter<SyncChange>
{
    public override SyncChange Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        using var document = JsonDocument.ParseValue(ref reader);
        var root = document.RootElement;
        var path = root.GetProperty("path").GetString() ?? throw new JsonException("同步变更无效");
        var version = root.GetProperty("expectedVersion").GetInt64();
        var hash = root.TryGetProperty("hash", out var value) && value.ValueKind != JsonValueKind.Null ? value.GetString() : null;
        var directory = root.TryGetProperty("directory", out var flag) && flag.ValueKind == JsonValueKind.True;
        if (hash == NativeSyncIdentity.Directory || (directory && hash != null)) throw new JsonException("同步变更无效");
        return new SyncChange(path, version, directory ? NativeSyncIdentity.Directory : hash);
    }

    public override void Write(Utf8JsonWriter writer, SyncChange value, JsonSerializerOptions options)
    {
        writer.WriteStartObject();
        writer.WriteString("path", value.Path);
        writer.WriteNumber("expectedVersion", value.ExpectedVersion);
        if (NativeSyncIdentity.IsBlob(value.Hash)) writer.WriteString("hash", value.Hash); else writer.WriteNull("hash");
        // `directory` is omitted for files so v1 operation fingerprints stay byte-identical.
        if (value.Hash == NativeSyncIdentity.Directory) writer.WriteBoolean("directory", true);
        writer.WriteEndObject();
    }
}
public enum NativeSyncChoice { Local, Remote, Both }
/// <summary>One conflicted file; Action is what taking the server's version does here: add, delete or modify.</summary>
public sealed record NativeSyncConflictItem(string Path, string Action, string? LocalFile, string? RemoteFile, int? LocalOnly, int? RemoteOnly, int? Changed);
public sealed record SyncManifest(string WorkspaceId, long Revision, Dictionary<string, SyncEntry> Files);
[System.Text.Json.Serialization.JsonConverter(typeof(SyncChangeJsonConverter))]
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

/// <summary>Content, when set, is a merge result published instead of Target's bytes; the checkpoint still records Target.</summary>
internal sealed record NativeSyncIncomingFile(string? ExpectedHash, SyncEntry Target, string? Content = null, bool KeepLocal = false)
{
    [System.Text.Json.Serialization.JsonIgnore]
    public string? WantedHash => KeepLocal ? ExpectedHash : Content ?? Target.Hash;
}
internal sealed record NativeSyncIncoming(string WorkspaceId, long Revision, Dictionary<string, NativeSyncIncomingFile> Files,
    string[]? ReviewPaths = null);

internal sealed record NativeSyncConflictRecord(string[] Paths, SyncManifest Remote);
