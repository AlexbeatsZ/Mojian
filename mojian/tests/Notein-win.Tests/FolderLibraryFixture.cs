using System.IO;
using System.Text;
using System.Text.Json;
using Notein_win.Services;

namespace Notein_win.Tests;

/// <summary>Shared v3 folder-library fixture and an observable form of the synchronized library state.</summary>
internal static class FolderLibraryFixture
{
    public static void CopyTo(string root)
    {
        var source = Path.Combine(AppContext.BaseDirectory, "Fixtures", "workspace-v3");
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(root, Path.GetRelativePath(source, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target);
        }
    }

    /// <summary>Folder descriptors and page heads: everything other devices see except page content.</summary>
    public static string State(string root)
    {
        var builder = new StringBuilder();
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories).Order(StringComparer.Ordinal))
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            if (relative.StartsWith(".mojian", StringComparison.Ordinal) || relative.Split('/').Any(part => part.StartsWith(".mojian-move"))) continue;
            if (Path.GetFileName(file) == FolderNames.Descriptor) builder.Append(relative).Append('\n').Append(File.ReadAllText(file)).Append('\n');
            else if (file.EndsWith(FolderNames.PageExtension, StringComparison.Ordinal))
            {
                var head = PageFileFormat.ReadHead(file);
                builder.Append(relative).Append('|').Append(head.Id).Append('|').Append(head.SortKey).Append('|').Append(head.Page?.ToJsonString()).Append('\n');
            }
        }
        return builder.ToString();
    }

    public static JsonElement Content(JsonDocument page) => page.RootElement.GetProperty("content");

    /// <summary>The first notebook's descriptor: editing it is how another device's folder change looks locally.</summary>
    public static string Descriptor(string root) => Directory.EnumerateFiles(root, FolderNames.Descriptor, SearchOption.AllDirectories)
        .Where(path => !Path.GetRelativePath(root, path).StartsWith(".mojian", StringComparison.Ordinal)).Order(StringComparer.Ordinal).First();

    public static void SimulateRemoteChange(string root) => File.AppendAllText(Descriptor(root), " ");
}
