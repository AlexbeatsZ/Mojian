using System.IO.Compression;
using System.IO;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class DocumentServicesTests
{
    [Fact]
    public void LayeredPdf_WritesOptionalContentGroups()
    {
        using var temp = new TemporaryDirectory();
        var path = System.IO.Path.Combine(temp.Path, "layers.pdf");
        var bitmap = BitmapSource.Create(2, 2, 96, 96, PixelFormats.Bgra32, null,
            new byte[] { 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255 }, 8);
        bitmap.Freeze();

        LayeredPdfExportService.Write(path, new[]
        {
            new PdfLayer("页面背景", bitmap),
            new PdfLayer("插入内容", bitmap),
            new PdfLayer("墨迹与文字", bitmap),
        });

        var bytes = File.ReadAllBytes(path);
        Assert.Equal("%PDF-1.7", Encoding.ASCII.GetString(bytes, 0, 8));
        Assert.Contains("/OCProperties", Encoding.ASCII.GetString(bytes));
        Assert.Contains("/OC0 BDC", Encoding.ASCII.GetString(bytes));
    }

    [Fact]
    public void LayeredPdf_UsesExplicitSourceMediaBoxForImportedPdfPages()
    {
        using var temp = new TemporaryDirectory();
        var path = System.IO.Path.Combine(temp.Path, "source-size.pdf");
        var bitmap = BitmapSource.Create(2, 2, 96, 96, PixelFormats.Bgra32, null,
            new byte[] { 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255 }, 8);
        bitmap.Freeze();

        LayeredPdfExportService.Write(path, new[] { new PdfLayer("页面背景", bitmap) }, 612, 792);

        Assert.Contains("/MediaBox [0 0 612 792]", Encoding.ASCII.GetString(File.ReadAllBytes(path)));
    }

    [Fact]
    public async Task PdfImporter_RendersAnUnpackagedLocalPdf()
    {
        using var temp = new TemporaryDirectory();
        var path = System.IO.Path.Combine(temp.Path, "source.pdf");
        var bitmap = BitmapSource.Create(2, 2, 96, 96, PixelFormats.Bgra32, null,
            new byte[] { 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255 }, 8);
        bitmap.Freeze();
        LayeredPdfExportService.Write(path, new[] { new PdfLayer("页面背景", bitmap) });

        var pages = await PdfImportService.RenderAsync(path, 64);

        Assert.Single(pages);
        Assert.Equal(1, pages[0].PageNumber);
        Assert.True(pages[0].PngBytes.Length > 8);
        Assert.Equal(new byte[] { 137, 80, 78, 71 }, pages[0].PngBytes[..4]);

        var rerendered = await PdfImportService.RenderPageAsync(path, 1, 128);
        using var stream = new MemoryStream(rerendered.PngBytes);
        var decoder = new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        Assert.True(decoder.Frames[0].PixelWidth >= 128);
    }

    [Theory]
    [InlineData(793.7, 1.0, 1.0, 2048)]
    [InlineData(793.7, 2.0, 1.5, 2560)]
    [InlineData(793.7, 8.0, 1.5, 4096)]
    public void PdfRenderSizing_TracksZoomAndCapsMemory(double width, double zoom, double dpi, uint expected)
    {
        Assert.Equal(expected, PdfRenderSizing.ForDisplay(width, zoom, dpi));
    }

    [Fact]
    public void XlsxPreview_ReadsFirstSheetAndSharedStrings()
    {
        using var temp = new TemporaryDirectory();
        var path = System.IO.Path.Combine(temp.Path, "sample.xlsx");
        using (var archive = ZipFile.Open(path, ZipArchiveMode.Create))
        {
            Write(archive, "xl/workbook.xml", "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Sheet1' sheetId='1' r:id='rId1'/></sheets></workbook>");
            Write(archive, "xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>");
            Write(archive, "xl/sharedStrings.xml", "<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><si><t>标题</t></si></sst>");
            Write(archive, "xl/worksheets/sheet1.xml", "<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c><c r='B1'><v>42</v></c></row></sheetData></worksheet>");
        }

        var rows = XlsxPreviewService.ReadFirstSheet(path);
        Assert.Equal("标题", rows[0][0]);
        Assert.Equal("42", rows[0][1]);
    }

    private static void Write(ZipArchive archive, string name, string content)
    {
        var entry = archive.CreateEntry(name);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(content);
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-win-tests", Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() { if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true); }
    }
}
