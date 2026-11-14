using System.IO;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Notein_win.Services;

public sealed record RenderedPdfPage(int PageNumber, int PageCount, double WidthPoints, double HeightPoints, byte[] PngBytes)
{
    internal string? PreviewPath { get; init; }
}

public sealed class PreparedPdfDocument : IDisposable
{
    private readonly string _directory;
    public IReadOnlyList<RenderedPdfPage> Pages { get; }
    internal PreparedPdfDocument(string directory, IReadOnlyList<RenderedPdfPage> pages) { _directory = directory; Pages = pages; }
    public void Dispose() { if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true); }
}

public static class PdfRenderSizing
{
    public const uint MinimumInteractiveWidth = 2048;
    public const uint MaximumInteractiveWidth = 4096;
    private const uint BucketSize = 512;

    public static uint ForDisplay(double documentWidth, double viewportScale, double dpiScale)
    {
        var requested = Math.Ceiling(Math.Max(1, documentWidth) * Math.Max(0.01, viewportScale) * Math.Max(1, dpiScale));
        requested = Math.Clamp(requested, MinimumInteractiveWidth, MaximumInteractiveWidth);
        return Math.Min(MaximumInteractiveWidth, (uint)(Math.Ceiling(requested / BucketSize) * BucketSize));
    }
}

public static class PdfImportService
{
    private static readonly SemaphoreSlim DocumentGate = new(1, 1);
    private static readonly Dictionary<string, (long Length, long Modified, PdfDocument Document)> Documents = new(StringComparer.OrdinalIgnoreCase);

    private static async Task<PdfDocument> OpenAsync(string path)
    {
        path = Path.GetFullPath(path);
        var info = new FileInfo(path);
        if (Documents.TryGetValue(path, out var cached) && cached.Length == info.Length && cached.Modified == info.LastWriteTimeUtc.Ticks)
            return cached.Document;
        var file = await StorageFile.GetFileFromPathAsync(path);
        var document = await PdfDocument.LoadFromFileAsync(file);
        if (Documents.Count >= 2) Documents.Remove(Documents.Keys.First());
        Documents[path] = (info.Length, info.LastWriteTimeUtc.Ticks, document);
        return document;
    }

    public static async Task<PreparedPdfDocument> PrepareAsync(string path, uint targetWidth = PdfRenderSizing.MinimumInteractiveWidth)
    {
        var directory = Path.Combine(Path.GetTempPath(), ".agents", "Mojian", "pdf-import", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        await DocumentGate.WaitAsync();
        try
        {
            var document = await OpenAsync(path);
            var pages = new List<RenderedPdfPage>((int)document.PageCount);
            for (uint index = 0; index < document.PageCount; index++)
            {
                using var source = document.GetPage(index);
                var rendered = await RenderPageAsync(source, (int)index + 1, (int)document.PageCount, targetWidth);
                var preview = Path.Combine(directory, $"{index + 1}.png");
                await File.WriteAllBytesAsync(preview, rendered.PngBytes);
                pages.Add(rendered with { PngBytes = [], PreviewPath = preview });
            }
            return new PreparedPdfDocument(directory, pages);
        }
        catch { Directory.Delete(directory, recursive: true); throw; }
        finally { DocumentGate.Release(); }
    }

    public static async Task<IReadOnlyList<RenderedPdfPage>> RenderAsync(string path, uint targetWidth = PdfRenderSizing.MinimumInteractiveWidth)
    {
        await DocumentGate.WaitAsync();
        try
        {
            var document = await OpenAsync(path);
            var pages = new List<RenderedPdfPage>((int)document.PageCount);
            for (uint index = 0; index < document.PageCount; index++)
            {
                using var page = document.GetPage(index);
                pages.Add(await RenderPageAsync(page, (int)index + 1, (int)document.PageCount, targetWidth));
            }
            return pages;
        }
        finally { DocumentGate.Release(); }
    }

    public static async Task<RenderedPdfPage> RenderPageAsync(string path, int pageNumber, uint targetWidth)
    {
        await DocumentGate.WaitAsync();
        try
        {
            var document = await OpenAsync(path);
            if (pageNumber < 1 || pageNumber > document.PageCount)
                throw new ArgumentOutOfRangeException(nameof(pageNumber), pageNumber, "PDF 页码超出范围。");
            using var page = document.GetPage((uint)(pageNumber - 1));
            return await RenderPageAsync(page, pageNumber, (int)document.PageCount, targetWidth);
        }
        finally { DocumentGate.Release(); }
    }

    private static async Task<RenderedPdfPage> RenderPageAsync(PdfPage page, int pageNumber, int pageCount, uint targetWidth)
    {
        using var stream = new InMemoryRandomAccessStream();
        var width = Math.Clamp(targetWidth, 1u, PdfRenderSizing.MaximumInteractiveWidth);
        var requestedHeight = Math.Max(1, width * page.Size.Height / Math.Max(1, page.Size.Width));
        var scale = Math.Min(1, Math.Min(16384 / requestedHeight, Math.Sqrt(24d * 1024 * 1024 / (width * requestedHeight))));
        width = (uint)Math.Max(1, Math.Floor(width * scale));
        var height = (uint)Math.Max(1, Math.Floor(requestedHeight * scale));
        await page.RenderToStreamAsync(stream, new PdfPageRenderOptions { DestinationWidth = width, DestinationHeight = height });
        if (stream.Size > int.MaxValue) throw new InvalidDataException("PDF 页面渲染结果过大。");
        stream.Seek(0);
        using var reader = new DataReader(stream.GetInputStreamAt(0));
        await reader.LoadAsync((uint)stream.Size);
        var bytes = new byte[(int)stream.Size];
        reader.ReadBytes(bytes);
        return new RenderedPdfPage(
            pageNumber,
            pageCount,
            page.Size.Width * 72d / 96d,
            page.Size.Height * 72d / 96d,
            bytes);
    }
}
