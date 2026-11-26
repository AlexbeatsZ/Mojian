using System.IO;
using System.Net.Http;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;

namespace Notein_win.Services;

internal sealed class NativeSyncBlobTransfer(HttpClient transport)
{
    private const int ChunkSize = 8 * 1024 * 1024;
    private const long MaxBlobSize = 1024L * 1024 * 1024;
    private sealed record Progress(long Offset, long Total, bool Complete = false);

    public async Task UploadAsync(string hash, string source, CancellationToken cancellation)
    {
        await using var file = File.OpenRead(source);
        if (file.Length <= ChunkSize)
        {
            await UploadLegacy(hash, file, cancellation).ConfigureAwait(false);
            return;
        }

        var route = "uploads/" + hash;
        using var status = await transport.GetAsync(route, cancellation).ConfigureAwait(false);
        long offset;
        if (status.StatusCode == HttpStatusCode.NotFound)
        {
            var code = await status.Content.ReadAsStringAsync(cancellation).ConfigureAwait(false);
            if (!code.Contains("\"upload_missing\"", StringComparison.Ordinal))
            {
                await UploadLegacy(hash, file, cancellation).ConfigureAwait(false);
                return;
            }
            offset = 0;
        }
        else offset = (await ReadProgress(status, file.Length, cancellation).ConfigureAwait(false)).Offset;
        var buffer = new byte[ChunkSize];
        var corrections = 0;
        while (offset < file.Length)
        {
            var count = (int)Math.Min(file.Length - offset, buffer.Length);
            file.Position = offset;
            await file.ReadExactlyAsync(buffer.AsMemory(0, count), cancellation).ConfigureAwait(false);
            using var content = new ByteArrayContent(buffer, 0, count);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            content.Headers.ContentRange = new ContentRangeHeaderValue(offset, offset + count - 1, file.Length);
            using var result = await transport.PutAsync(route, content, cancellation).ConfigureAwait(false);
            if (result.StatusCode == HttpStatusCode.NotFound)
            {
                file.Position = 0;
                await UploadLegacy(hash, file, cancellation).ConfigureAwait(false);
                return;
            }
            if (result.StatusCode == HttpStatusCode.Conflict)
            {
                var error = await result.Content.ReadFromJsonAsync<UploadOffsetMismatch>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false);
                if (error is not { Error: "upload_offset_mismatch" } || error.Offset < 0 || error.Offset > file.Length || error.Total != file.Length
                    || ++corrections > 3) throw new IOException("同步分段上传进度不一致");
                offset = error.Offset;
                continue;
            }
            var next = await ReadProgress(result, file.Length, cancellation).ConfigureAwait(false);
            if (!next.Complete && next.Offset != offset + count) throw new IOException("同步分段上传未确认");
            offset = next.Offset;
        }
    }

    private sealed record UploadOffsetMismatch(string Error, long Offset, long Total);

    private async Task UploadLegacy(string hash, Stream file, CancellationToken cancellation)
    {
        using var content = new StreamContent(file);
        using var result = await transport.PutAsync("blobs/" + hash, content, cancellation).ConfigureAwait(false);
        result.EnsureSuccessStatusCode();
    }

    private static async Task<Progress> ReadProgress(HttpResponseMessage response, long total, CancellationToken cancellation)
    {
        response.EnsureSuccessStatusCode();
        var progress = await response.Content.ReadFromJsonAsync<Progress>(NativeSyncStorage.Json, cancellation).ConfigureAwait(false);
        if (progress == null || progress.Offset < 0 || progress.Offset > total || progress.Total != total
            || progress.Complete != (progress.Offset == total)) throw new IOException("同步分段上传进度无效");
        return progress;
    }

    public async Task DownloadAsync(string hash, string target, CancellationToken cancellation)
    {
        if (NativeSyncStorage.Hash(target) == hash) return;
        var part = target + ".part";
        var journal = target + ".progress";
        if (new FileInfo(part).LinkTarget != null || new FileInfo(journal).LinkTarget != null)
            throw new IOException("无效同步临时文件");
        var progress = File.Exists(journal) ? NativeSyncStorage.Read<Progress>(journal) : new Progress(0, 0);
        if (progress.Offset < 0 || progress.Offset > progress.Total || progress.Total > MaxBlobSize
            || (progress.Offset > 0 && (!File.Exists(part) || new FileInfo(part).Length < progress.Offset)))
        {
            File.Delete(journal);
            progress = new(0, 0);
        }
        while (progress.Total == 0 || progress.Offset < progress.Total)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, "blobs/" + hash);
            request.Headers.Range = new RangeHeaderValue(progress.Offset, progress.Offset + ChunkSize - 1);
            using var response = await transport.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation).ConfigureAwait(false);
            if (response.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable && progress.Offset == 0)
            {
                using var full = await transport.GetAsync("blobs/" + hash, HttpCompletionOption.ResponseHeadersRead, cancellation).ConfigureAwait(false);
                await ReadFull(full, part, cancellation).ConfigureAwait(false);
                break;
            }
            response.EnsureSuccessStatusCode();
            if (response.StatusCode == HttpStatusCode.OK)
            {
                File.Delete(journal);
                await ReadFull(response, part, cancellation).ConfigureAwait(false);
                break;
            }
            var range = response.Content.Headers.ContentRange;
            if (response.StatusCode != HttpStatusCode.PartialContent || range?.Unit != "bytes" || range.From != progress.Offset
                || range.To is not long last || range.Length is not long total || last < progress.Offset || last >= total
                || last - progress.Offset >= ChunkSize || total > MaxBlobSize || (progress.Total != 0 && total != progress.Total))
                throw new IOException("同步下载分段范围无效");
            await SaveRange(response, part, progress.Offset, last - progress.Offset + 1, cancellation).ConfigureAwait(false);
            progress = new(last + 1, total);
            NativeSyncStorage.Save(journal, progress);
        }
        if (NativeSyncStorage.Hash(part) != hash)
        {
            File.Delete(part);
            File.Delete(journal);
            throw new IOException("下载校验失败，已保留本地内容");
        }
        File.Move(part, target, true);
        File.Delete(journal);
    }

    private static async Task ReadFull(HttpResponseMessage response, string part, CancellationToken cancellation)
    {
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength is not long size || size < 0 || size > MaxBlobSize)
            throw new IOException("同步下载长度无效");
        await SaveRange(response, part, 0, size, cancellation).ConfigureAwait(false);
    }

    private static async Task SaveRange(HttpResponseMessage response, string part, long offset, long count, CancellationToken cancellation)
    {
        if (response.Content.Headers.ContentLength is long declared && declared != count)
            throw new IOException("同步下载长度不一致");
        await using var input = await response.Content.ReadAsStreamAsync(cancellation).ConfigureAwait(false);
        await using var output = new FileStream(part, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
        output.SetLength(offset);
        output.Position = offset;
        var buffer = new byte[64 * 1024];
        while (count > 0)
        {
            int read = await input.ReadAsync(buffer.AsMemory(0, (int)Math.Min(count, buffer.Length)), cancellation).ConfigureAwait(false);
            if (read == 0) throw new HttpRequestException("同步下载分段不完整");
            await output.WriteAsync(buffer.AsMemory(0, read), cancellation).ConfigureAwait(false);
            count -= read;
        }
        if (await input.ReadAsync(buffer.AsMemory(0, 1), cancellation).ConfigureAwait(false) != 0)
            throw new IOException("同步下载分段超出声明长度");
        output.Flush(true);
    }
}
