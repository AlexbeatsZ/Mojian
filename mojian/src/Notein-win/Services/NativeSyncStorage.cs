using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Notein_win.Services;

internal static class NativeSyncStorage
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNameCaseInsensitive = true };
    // v2 layout: clients no longer create it, but its tombstones stay in the server manifest.
    private static readonly Regex LegacyPath = new(@"^(library\.json|notebooks/[A-Za-z0-9_-][A-Za-z0-9._-]*/(pages/[A-Za-z0-9_-][A-Za-z0-9._-]*\.json|assets/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*))\z", RegexOptions.CultureInvariant);
    private static readonly Regex Reserved = new(@"^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static bool StorableName(string part) => part.Length is > 0 and <= 240 && part is not ("." or "..") &&
        !part.Any(character => character < 0x20 || character == 0x7f || "\\:*?\"<>|".Contains(character)) &&
        !part.EndsWith('.') && !part.EndsWith(' ') && !Reserved.IsMatch(part);

    /// <summary>The folder-library v3 path policy, identical to sync-server/protocol.mjs.</summary>
    internal static bool ValidPath(string path)
    {
        if (string.IsNullOrEmpty(path) || path.Length >= 1000 || path.Contains('\r') || path.Contains('\n') ||
            path.EndsWith(".previous", StringComparison.Ordinal) || path.EndsWith(".tmp", StringComparison.Ordinal) ||
            path.Contains(".tmp-", StringComparison.Ordinal) || path.Contains("-sync-conflict-", StringComparison.Ordinal)) return false;
        var parts = path.Split('/');
        if (!parts.All(StorableName)) return false;
        if (LegacyPath.IsMatch(path)) return true;
        if (parts[0] == ".mojian") return parts.Length <= 4 && (parts.Length < 2 || parts[1] == "assets");
        return parts.All(part => !part.StartsWith('.') || part == FolderNames.Descriptor);
    }

    /// <summary>Files that may exist on the server: pages, folder descriptors and page assets (legacy names only as tombstones).</summary>
    internal static bool IsLibraryFile(string path) => path.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal) ||
        path == FolderNames.Descriptor || path.EndsWith("/" + FolderNames.Descriptor, StringComparison.Ordinal) ||
        (path.StartsWith(".mojian/assets/", StringComparison.Ordinal) && path.Split('/').Length == 4);

    internal static bool ValidHash(string? hash) => hash == null || hash == NativeSyncIdentity.Directory ||
        Regex.IsMatch(hash, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant);

    /// <summary>Device-local or temporary names that never synchronize and are not reported (hidden state, copies in flight).</summary>
    internal static bool IsExcluded(string path)
    {
        var parts = path.Split('/');
        if (parts[0] == ".mojian" && (parts.Length < 2 || parts[1] != "assets")) return parts.Length > 1;
        return parts.Any(part => part is ".mojian-sync" or ".DS_Store" or "desktop.ini" or "Thumbs.db" ||
            (part.StartsWith('.') && part != FolderNames.Descriptor && part != ".mojian") ||
            part.EndsWith(".previous", StringComparison.OrdinalIgnoreCase) || part.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) ||
            part.Contains(".tmp-", StringComparison.Ordinal) || part.Contains("-sync-conflict-", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Why an item that is neither excluded nor valid cannot synchronize, or null when it can.</summary>
    internal static string? Failure(string path, bool directory)
    {
        if (IsExcluded(path)) return null;
        if (!ValidPath(path)) return "名称含有 \\ : * ? \" < > | 或以空格、句点结尾，其他设备无法保存";
        if (!directory && !IsLibraryFile(path)) return "不是墨笺页面或附件，未同步";
        return null;
    }

    internal static bool ValidId(string id) => !string.IsNullOrEmpty(id) && Regex.IsMatch(id, "\\A[A-Za-z0-9_-]{16,80}\\z", RegexOptions.CultureInvariant);
    internal static T Read<T>(string path)
    {
        try { return JsonSerializer.Deserialize<T>(File.ReadAllBytes(path), Json) ?? throw new IOException("同步记录损坏，请保留目录并恢复备份"); }
        catch (JsonException error) { throw new IOException("同步记录损坏，请保留目录并恢复备份", error); }
    }
    internal static void Save<T>(string path, T value) => Write(path, JsonSerializer.SerializeToUtf8Bytes(value, Json));
    internal static void Write(string target, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var temporary = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            { output.Write(bytes); output.Flush(true); }
            File.Move(temporary, target, true);
        }
        finally { File.Delete(temporary); }
    }
    internal static string? Hash(string path)
    {
        try
        {
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant();
        }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
    }
    internal static void CopyDurable(string source, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var temporary = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            { input.CopyTo(output); output.Flush(true); }
            File.Move(temporary, target, true);
        }
        finally { File.Delete(temporary); }
    }
    internal static void NoLinks(string root)
    {
        for (var directory = new DirectoryInfo(root); directory != null; directory = directory.Parent)
            if (directory.Exists && (directory.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("同步目录不能经过链接或目录联接");
    }
    /// <summary>One item below the library root; <c>Failure</c> explains why it is skipped (Syncthing's failed items).</summary>
    internal sealed record Item(string Path, bool Directory, string? Failure);

    /// <summary>
    /// Every library item, folders included. Links, nested Syncthing folders and unreadable entries are reported as
    /// failed items and skipped; they never stop the rest of the library from synchronizing.
    /// </summary>
    internal static IEnumerable<Item> EnumerateItems(string root, string relative = "")
    {
        string[] entries;
        string? unreadable = null;
        try { entries = Directory.EnumerateFileSystemEntries(System.IO.Path.Combine(root, relative)).ToArray(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { entries = []; unreadable = error.Message; }
        if (unreadable != null) { yield return new(relative, true, "无法读取此文件夹：" + unreadable); yield break; }
        foreach (var entry in entries)
        {
            var name = System.IO.Path.GetFileName(entry);
            var path = relative.Length == 0 ? name : relative + "/" + name;
            if (IsExcluded(path)) continue;
            FileAttributes? attributes = null;
            string? failure = null;
            try { attributes = File.GetAttributes(entry); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { failure = error.Message; }
            if (attributes == null) { yield return new(path, false, "无法读取：" + failure); continue; }
            var directory = (attributes.Value & FileAttributes.Directory) != 0;
            if ((attributes.Value & FileAttributes.ReparsePoint) != 0) { yield return new(path, directory, "链接或目录联接不同步，已跳过"); continue; }
            if (directory && (File.Exists(System.IO.Path.Combine(entry, ".stfolder")) || Directory.Exists(System.IO.Path.Combine(entry, ".stfolder"))))
            { yield return new(path, true, "此文件夹由 Syncthing 管理，已跳过"); continue; }
            yield return new(path, directory, Failure(path, directory));
            if (directory) foreach (var child in EnumerateItems(root, path)) yield return child;
        }
    }

    internal static void ValidateEntries(Dictionary<string, SyncEntry> entries, long revision)
    {
        if (entries == null || revision < 0 || revision > 9007199254740991L) throw new IOException("同步版本无效");
        // Like Syncthing, a tombstone keeps its exact spelling; only items that exist must be unique ignoring case.
        var live = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var pair in entries)
            if (!ValidPath(pair.Key) || pair.Value == null || !ValidHash(pair.Value.Hash) || pair.Value.Version <= 0 || pair.Value.Version > revision ||
                (pair.Value.Hash != null && !live.Add(pair.Key)))
                throw new IOException("同步记录包含无效或冲突路径：" + pair.Key);
    }
}
