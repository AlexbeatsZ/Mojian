using System.IO;
using System.Text.Json;

namespace Notein_win.Services;

public static class WorkspaceDeletionTransaction
{
    public static void Execute(string root, IEnumerable<string> files, Action commit)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(root).BeginMutation();
        lock (WorkspaceFileGate.ForRoot(root)) ExecuteCore(root, files, commit);
    }

    private static void ExecuteCore(string root, IEnumerable<string> files, Action commit)
    {
        var directory = Path.Combine(root, ".mojian-deletions.previous", Guid.NewGuid().ToString("N"));
        var paths = files.Where(File.Exists).Select(path => Path.GetRelativePath(root, path)).Distinct().ToArray();
        Directory.CreateDirectory(directory);
        WorkspaceStore.AtomicWrite(Path.Combine(directory, "manifest.json"), JsonSerializer.Serialize(paths));
        try
        {
            foreach (var relative in paths)
            {
                var destination = Path.Combine(directory, "files", relative);
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                File.Move(Path.Combine(root, relative), destination);
            }
            commit();
        }
        catch (Exception original)
        {
            try { Restore(root, directory, _ => true); }
            catch (Exception rollback) { throw new IOException($"删除失败，恢复尚未完成。请保留恢复目录：{directory}", new AggregateException(original, rollback)); }
            throw;
        }
    }

    public static void Recover(string root, Func<string, bool> referenced)
    {
        using var mutation = NativeSyncMutationEpoch.ForRoot(root).BeginMutation();
        lock (WorkspaceFileGate.ForRoot(root)) RecoverCore(root, referenced);
    }

    private static void RecoverCore(string root, Func<string, bool> referenced)
    {
        var parent = Path.Combine(root, ".mojian-deletions.previous");
        if (!Directory.Exists(parent)) return;
        foreach (var directory in Directory.EnumerateDirectories(parent).OrderByDescending(Directory.GetCreationTimeUtc))
            Restore(root, directory, referenced);
    }

    private static void Restore(string root, string directory, Func<string, bool> referenced)
    {
        var manifest = Path.Combine(directory, "manifest.json");
        if (!File.Exists(manifest)) return;
        foreach (var relative in JsonSerializer.Deserialize<string[]>(File.ReadAllText(manifest)) ?? throw new IOException("删除恢复清单损坏"))
        {
            var target = Path.GetFullPath(Path.Combine(root, relative));
            if (!target.StartsWith(Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new IOException("删除恢复清单路径无效");
            var backup = Path.Combine(directory, "files", relative);
            if (!referenced(relative) || !File.Exists(backup) || File.Exists(target)) continue;
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(backup, target);
        }
    }
}
