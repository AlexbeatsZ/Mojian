using System.Collections.Concurrent;
using System.IO;

namespace Notein_win.Services;

public static class WorkspaceFileGate
{
    private static readonly ConcurrentDictionary<string, object> Gates = new(StringComparer.OrdinalIgnoreCase);

    public static object ForRoot(string root) => Gates.GetOrAdd(Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)), _ => new object());
}
