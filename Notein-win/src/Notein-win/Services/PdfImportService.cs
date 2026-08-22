using System.IO;
using Windows.Data.Pdf;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Notein_win.Services;

public sealed record RenderedPdfPage(int PageNumber, int PageCount, double WidthPoints, double HeightPoints, byte[] PngBytes);

public static class PdfImportService
{
    public static async Task<IReadOnlyList<RenderedPdfPage>> RenderAsync(string path, uint targetWidth = 1800)
    {
        var file = await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path));
        var document = await PdfDocument.LoadFromFileAsync(file);
        var pages = new List<RenderedPdfPage>((int)document.PageCount);
        for (uint index = 0; index < document.PageCount; index++)
        {
            using var page = document.GetPage(index);
            using var stream = new InMemoryRandomAccessStream();
            var width = targetWidth;
            var height = (uint)Math.Max(1, Math.Round(targetWidth * page.Size.Height / Math.Max(1, page.Size.Width)));
            await page.RenderToStreamAsync(stream, new PdfPageRenderOptions { DestinationWidth = width, DestinationHeight = height });
            if (stream.Size > int.MaxValue) throw new InvalidDataException("PDF 页面渲染结果过大。");
            stream.Seek(0);
            using var reader = new DataReader(stream.GetInputStreamAt(0));
            await reader.LoadAsync((uint)stream.Size);
            var bytes = new byte[(int)stream.Size];
            reader.ReadBytes(bytes);
            pages.Add(new RenderedPdfPage(
                (int)index + 1,
                (int)document.PageCount,
                page.Size.Width * 72d / 96d,
                page.Size.Height * 72d / 96d,
                bytes));
        }
        return pages;
    }
}
