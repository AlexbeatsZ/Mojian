using System.Net;
using System.Net.Http.Json;

namespace Notein_win.Services;

/// <summary>Durable remote discovery, independent of the per-path applied baseline.</summary>
internal sealed class NativeSyncManifestFeed(HttpClient transport, string directory)
{
    private const int PageSize = 512;
    private string ViewPath => Path.Combine(directory, "remote-view.json");
    private string PartialPath => Path.Combine(directory, "remote-pages.json");

    private sealed record Partial(string WorkspaceId, long Revision, long? Since, Dictionary<string, SyncEntry> Files, string NextCursor);
    private sealed record Page(string WorkspaceId, long Revision, Dictionary<string, SyncEntry> Files, string? NextCursor);

    public async Task<SyncManifest> ReadAsync(string workspace, long minimumRevision, CancellationToken cancellation)
    {
        var view = File.Exists(ViewPath) ? NativeSyncStorage.Read<SyncManifest>(ViewPath) : null;
        if (view != null)
        {
            VerifyIdentity(view.WorkspaceId, view.Revision, workspace, 0);
            NativeSyncStorage.ValidateEntries(view.Files, view.Revision);
        }

        var partial = File.Exists(PartialPath) ? NativeSyncStorage.Read<Partial>(PartialPath) : null;
        if (partial != null)
        {
            VerifyIdentity(partial.WorkspaceId, partial.Revision, workspace, 0);
            NativeSyncStorage.ValidateEntries(partial.Files, partial.Revision);
            if (partial.Revision < minimumRevision || (view != null && partial.Revision <= view.Revision)
                || (partial.Since != null && partial.Since != view?.Revision)) partial = null;
        }

        var since = partial != null ? partial.Since : view?.Revision;
        var restarted = false;
        while (true)
        {
            var route = since == null ? "manifest/pages" : "changes";
            var query = partial == null
                ? since == null ? $"limit={PageSize}" : $"since={since.Value}&limit={PageSize}"
                : "cursor=" + Uri.EscapeDataString(partial.NextCursor);
            using var response = await transport.GetAsync(route + "?" + query, cancellation).ConfigureAwait(false);
            if (response.StatusCode == HttpStatusCode.NotFound)
            {
                File.Delete(PartialPath);
                using var fallback = await transport.GetAsync("manifest", cancellation).ConfigureAwait(false);
                fallback.EnsureSuccessStatusCode();
                return await fallback.Content.ReadFromJsonAsync<SyncManifest>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false)
                    ?? throw new IOException("同步清单为空");
            }
            if (response.StatusCode == HttpStatusCode.Gone && (partial != null || since != null) && !restarted)
            {
                restarted = true;
                File.Delete(PartialPath);
                partial = null;
                since = null;
                continue;
            }
            response.EnsureSuccessStatusCode();
            var page = await response.Content.ReadFromJsonAsync<Page>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false)
                ?? throw new IOException("同步清单分页为空");
            VerifyIdentity(page.WorkspaceId, page.Revision, workspace, minimumRevision);
            NativeSyncStorage.ValidateEntries(page.Files, page.Revision);
            if (partial != null && (page.Revision != partial.Revision || page.WorkspaceId != partial.WorkspaceId))
                throw new IOException("同步分页锚点变化");
            if (since != null && (view?.WorkspaceId != page.WorkspaceId || page.Revision < since
                || page.Files.Values.Any(entry => entry.Version <= since)))
                throw new IOException("同步增量超出约定范围");
            var collected = partial?.Files ?? new Dictionary<string, SyncEntry>(StringComparer.Ordinal);
            foreach (var pair in page.Files)
                if (!collected.TryAdd(pair.Key, pair.Value)) throw new IOException("同步清单分页包含重复项");
            if (page.NextCursor != null)
            {
                if (page.NextCursor.Length is < 1 or > 2048 || page.NextCursor == partial?.NextCursor || page.Files.Count == 0)
                    throw new IOException("同步分页游标无效");
                partial = new(page.WorkspaceId, page.Revision, since, collected, page.NextCursor);
                NativeSyncStorage.Save(PartialPath, partial);
                continue;
            }

            var files = since == null ? collected : new Dictionary<string, SyncEntry>(view!.Files, StringComparer.Ordinal);
            if (since != null)
                foreach (var pair in collected) files[pair.Key] = pair.Value;
            NativeSyncStorage.ValidateEntries(files, page.Revision);
            var result = new SyncManifest(page.WorkspaceId, page.Revision, files);
            NativeSyncStorage.Save(ViewPath, result);
            File.Delete(PartialPath);
            return result;
        }
    }

    private static void VerifyIdentity(string id, long revision, string workspace, long minimum)
    {
        if (string.IsNullOrEmpty(id) || (workspace.Length > 0 && id != workspace) || revision < minimum)
            throw new IOException("服务器身份变化或版本回退，已暂停同步");
    }
}
