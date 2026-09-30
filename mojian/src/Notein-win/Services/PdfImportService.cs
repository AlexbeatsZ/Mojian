using System.IO;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Notein_win.Services;

public sealed record RenderedPdfPage(int PageNumber, int PageCount, double WidthPoints, double HeightPoints, byte[] PngBytes);

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
    public static async Task<IReadOnlyList<RenderedPdfPage>> RenderAsync(string path, uint targetWidth = PdfRenderSizing.MinimumInteractiveWidth)
    {
        var file = await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path));
        var document = await PdfDocument.LoadFromFileAsync(file);
        var pages = new List<RenderedPdfPage>((int)document.PageCount);
        for (uint index = 0; index < document.PageCount; index++)
        {
            using var page = document.GetPage(index);
            pages.Add(await RenderPageAsync(page, (int)index + 1, (int)document.PageCount, targetWidth));
        }
        return pages;
    }

    public static async Task<RenderedPdfPage> RenderPageAsync(string path, int pageNumber, uint targetWidth)
    {
        var file = await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path));
        var document = await PdfDocument.LoadFromFileAsync(file);
        if (pageNumber < 1 || pageNumber > document.PageCount)
            throw new ArgumentOutOfRangeException(nameof(pageNumber), pageNumber, "PDF 页码超出范围。");
        using var page = document.GetPage((uint)(pageNumber - 1));
        return await RenderPageAsync(page, pageNumber, (int)document.PageCount, targetWidth);
    }

    private static async Task<RenderedPdfPage> RenderPageAsync(PdfPage page, int pageNumber, int pageCount, uint targetWidth)
    {
        using var stream = new InMemoryRandomAccessStream();
        var width = Math.Max(1u, targetWidth);
        var height = (uint)Math.Max(1, Math.Round(width * page.Size.Height / Math.Max(1, page.Size.Width)));
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
