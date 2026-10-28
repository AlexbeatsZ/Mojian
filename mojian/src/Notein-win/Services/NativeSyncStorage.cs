using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Notein_win.Services;

internal static class NativeSyncStorage
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNameCaseInsensitive = true };
    private static readonly Regex PathPattern = new(@"^notebooks/[A-Za-z0-9_-][A-Za-z0-9._-]*/(pages/[A-Za-z0-9_-][A-Za-z0-9._-]*\.json|assets/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*)\z", RegexOptions.CultureInvariant);
    private static readonly Regex Reserved = new(@"^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    internal static bool ValidPath(string path) => !string.IsNullOrEmpty(path) && path.Length < 500 &&
        (path == "library.json" || PathPattern.IsMatch(path)) &&
        !path.EndsWith(".previous", StringComparison.Ordinal) && !path.EndsWith(".tmp", StringComparison.Ordinal) &&
        !path.Contains("-sync-conflict-", StringComparison.Ordinal) &&
        path.Split('/').All(component => !component.EndsWith('.') && !Reserved.IsMatch(component));
    internal static bool ValidHash(string? hash) => hash == null || Regex.IsMatch(hash, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant);
    internal static bool ShouldSyncPath(string path)
    {
        var parts = path.Split('/');
        if (parts.Any(part => part == ".mojian-sync" || part.EndsWith(".previous", StringComparison.OrdinalIgnoreCase) ||
            part.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) || part.Contains("-sync-conflict-", StringComparison.OrdinalIgnoreCase))) return false;
        if (ValidPath(path)) return true;
        if (path.Equals("library.json", StringComparison.OrdinalIgnoreCase) ||
            (parts.Length >= 3 && parts[0].Equals("notebooks", StringComparison.OrdinalIgnoreCase) &&
                (parts[2].Equals("pages", StringComparison.OrdinalIgnoreCase) || parts[2].Equals("assets", StringComparison.OrdinalIgnoreCase))))
            throw new IOException("原生同步 v1 不支持此内容路径，已停止以免遗漏笔记或附件：" + path);
        return false;
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
    internal static IEnumerable<string> Enumerate(string root, bool checkSyncthing = false)
    {
        foreach (var entry in Directory.EnumerateFileSystemEntries(root))
        {
            var name = Path.GetFileName(entry);
            if (checkSyncthing && name is ".stfolder" or ".stignore" or ".stversions") throw new IOException("检测到 Syncthing 标记，请选择独立副本");
            var attributes = File.GetAttributes(entry);
            if ((attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("同步目录不能包含链接或目录联接");
            if ((attributes & FileAttributes.Directory) != 0)
            {
                if (name == ".mojian-sync") continue;
                foreach (var path in Enumerate(entry, checkSyncthing)) yield return path;
            }
            else yield return entry;
        }
    }
    internal static void ValidateEntries(Dictionary<string, SyncEntry> entries, long revision)
    {
        if (entries == null || revision < 0 || revision > 9007199254740991L) throw new IOException("同步版本无效");
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var pair in entries)
            if (!ValidPath(pair.Key) || !paths.Add(pair.Key) || pair.Value == null || !ValidHash(pair.Value.Hash) || pair.Value.Version <= 0 || pair.Value.Version > revision)
                throw new IOException("同步记录包含无效或冲突路径");
    }
}
