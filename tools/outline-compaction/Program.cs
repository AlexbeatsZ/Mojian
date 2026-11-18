using System.IO;
using Notein_win.Services;

// Rewrites oversized 0.7.21-0.7.33 Windows pen outlines in a canonical Mojian library, and with
// --pack-points stores plain point arrays as `pointsCoded` (needs Windows/Android/macOS readers >= 0.7.36).
// Usage: mojian-outline-compaction <library root> [--min-mb N] [--skip <page id>]... [--pack-points] [--write]
// Without --write it only reports. Close Mojian on this device first; sync uploads the result.
// Only rewrite pages that every other device has already synced unchanged (compare hashes first).
static int Run(string[] args)
{
    if (args.Length == 0 || args[0].StartsWith("--", StringComparison.Ordinal))
    {
        Console.Error.WriteLine("usage: mojian-outline-compaction <library root> [--min-mb N] [--write]");
        return 2;
    }
    var root = Path.GetFullPath(args[0]);
    if (!File.Exists(Path.Combine(root, "library.json")) || !Directory.Exists(Path.Combine(root, "notebooks")))
    {
        Console.Error.WriteLine($"not a Mojian library: {root}");
        return 2;
    }
    var write = args.Contains("--write");
    var minimumMb = 0d;
    var index = Array.IndexOf(args, "--min-mb");
    if (index >= 0 && index + 1 < args.Length) minimumMb = double.Parse(args[index + 1], System.Globalization.CultureInfo.InvariantCulture);
    if (write && System.Diagnostics.Process.GetProcessesByName("Mojian").Length > 0)
    {
        Console.Error.WriteLine("Mojian is running; close it before writing so it cannot overwrite the compacted pages.");
        return 3;
    }
    // --skip <page id> (repeatable): pages another device has edited but not yet synced must not be
    // rewritten here, or both sides change the same file and native sync records a conflict.
    var skip = args.Select((value, i) => (value, i)).Where(pair => pair.value == "--skip" && pair.i + 1 < args.Length)
        .Select(pair => args[pair.i + 1] + ".json").ToHashSet(StringComparer.Ordinal);
    var packPoints = args.Contains("--pack-points");
    var results = OutlineCompaction.CompactLibrary(root, (long)(minimumMb * 1024 * 1024), write,
        path => !skip.Contains(Path.GetFileName(path)), packPoints);
    long before = 0, after = 0; int pages = 0, strokes = 0, unmatched = 0, packed = 0;
    foreach (var (path, result, bytesBefore, bytesAfter) in results.OrderByDescending(r => r.BytesBefore))
    {
        before += bytesBefore; after += bytesAfter;
        if (result.Compacted > 0 || result.PointsPacked > 0) pages++;
        strokes += result.Compacted; unmatched += result.Unmatched; packed += result.PointsPacked;
        if (result.Candidates > 0 || result.PointsPacked > 0 && bytesBefore > 4 << 20)
            Console.WriteLine($"{bytesBefore / 1048576d,8:F1} MB -> {bytesAfter / 1048576d,7:F1} MB  outlines {result.Compacted,5}  unmatched {result.Unmatched,4}  points {result.PointsPacked,5}  {Path.GetRelativePath(root, path)}");
    }
    Console.WriteLine($"{(write ? "WROTE" : "DRY RUN")}: {pages} pages, {strokes} outlines compacted, {unmatched} unmatched, {packed} point arrays packed; scanned {before / 1048576d:F1} MB -> {after / 1048576d:F1} MB");
    return 0;
}

// WPF geometry is created on an STA thread, as in the application.
return RunSta(args);

static int RunSta(string[] args)
{
    var code = 0;
    var thread = new Thread(() => code = Run(args));
    thread.SetApartmentState(ApartmentState.STA);
    thread.Start();
    thread.Join();
    return code;
}
