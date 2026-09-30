using System.IO.Compression;
using System.IO;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Notein_win.Services;

public sealed record PdfLayer(string Name, BitmapSource Bitmap);

public static class LayeredPdfExportService
{
    public static void Write(
        string path,
        IReadOnlyList<PdfLayer> layers,
        double? mediaBoxWidthPoints = null,
        double? mediaBoxHeightPoints = null)
    {
        if (layers.Count == 0) throw new ArgumentException("至少需要一个 PDF 图层。", nameof(layers));
        var width = layers[0].Bitmap.PixelWidth;
        var height = layers[0].Bitmap.PixelHeight;
        if (layers.Any(layer => layer.Bitmap.PixelWidth != width || layer.Bitmap.PixelHeight != height))
            throw new InvalidOperationException("PDF 图层尺寸必须一致。");

        var objects = new List<byte[]>();
        int Add(string text) { objects.Add(Encoding.ASCII.GetBytes(text)); return objects.Count; }
        int AddBytes(byte[] bytes) { objects.Add(bytes); return objects.Count; }

        var catalogId = Add(string.Empty);
        var pagesId = Add(string.Empty);
        var pageId = Add(string.Empty);
        var ocgIds = layers.Select(layer => Add($"<< /Type /OCG /Name ({EscapePdfString(layer.Name)}) >>")).ToArray();
        var imageIds = new List<int>();
        var maskIds = new List<int>();
        foreach (var layer in layers)
        {
            ExtractPixels(layer.Bitmap, out var rgb, out var alpha);
            var rgbCompressed = Compress(rgb);
            var alphaCompressed = Compress(alpha);
            var maskId = AddBytes(StreamObject($"<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length {alphaCompressed.Length} >>", alphaCompressed));
            var imageId = AddBytes(StreamObject($"<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /SMask {maskId} 0 R /Length {rgbCompressed.Length} >>", rgbCompressed));
            maskIds.Add(maskId);
            imageIds.Add(imageId);
        }

        var contentBuilder = new StringBuilder();
        var pageWidth = mediaBoxWidthPoints is > 0 ? mediaBoxWidthPoints.Value : width * 72d / 96d;
        var pageHeight = mediaBoxHeightPoints is > 0 ? mediaBoxHeightPoints.Value : height * 72d / 96d;
        for (var index = 0; index < layers.Count; index++)
        {
            contentBuilder.AppendLine($"/OC{index} BDC q {pageWidth:0.###} 0 0 {pageHeight:0.###} 0 0 cm /Im{index} Do Q EMC");
        }
        var contentBytes = Encoding.ASCII.GetBytes(contentBuilder.ToString());
        var contentId = AddBytes(StreamObject($"<< /Length {contentBytes.Length} >>", contentBytes));

        var properties = string.Join(' ', ocgIds.Select((id, index) => $"/OC{index} {id} 0 R"));
        var images = string.Join(' ', imageIds.Select((id, index) => $"/Im{index} {id} 0 R"));
        var ocgRefs = string.Join(' ', ocgIds.Select(id => $"{id} 0 R"));
        objects[catalogId - 1] = Encoding.ASCII.GetBytes($"<< /Type /Catalog /Pages {pagesId} 0 R /OCProperties << /OCGs [{ocgRefs}] /D << /Order [{ocgRefs}] /ON [{ocgRefs}] >> >> >>");
        objects[pagesId - 1] = Encoding.ASCII.GetBytes($"<< /Type /Pages /Kids [{pageId} 0 R] /Count 1 >>");
        objects[pageId - 1] = Encoding.ASCII.GetBytes($"<< /Type /Page /Parent {pagesId} 0 R /MediaBox [0 0 {pageWidth:0.###} {pageHeight:0.###}] /Resources << /Properties << {properties} >> /XObject << {images} >> >> /Contents {contentId} 0 R >>");

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        using var output = File.Create(path);
        WriteAscii(output, "%PDF-1.7\n%Mojian\n");
        var offsets = new List<long> { 0 };
        for (var index = 0; index < objects.Count; index++)
        {
            offsets.Add(output.Position);
            WriteAscii(output, $"{index + 1} 0 obj\n");
            output.Write(objects[index]);
            WriteAscii(output, "\nendobj\n");
        }
        var xref = output.Position;
        WriteAscii(output, $"xref\n0 {objects.Count + 1}\n0000000000 65535 f \n");
        foreach (var offset in offsets.Skip(1)) WriteAscii(output, $"{offset:0000000000} 00000 n \n");
        WriteAscii(output, $"trailer\n<< /Size {objects.Count + 1} /Root {catalogId} 0 R >>\nstartxref\n{xref}\n%%EOF\n");
    }

    private static void ExtractPixels(BitmapSource source, out byte[] rgb, out byte[] alpha)
    {
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var stride = converted.PixelWidth * 4;
        var pixels = new byte[stride * converted.PixelHeight];
        converted.CopyPixels(pixels, stride, 0);
        rgb = new byte[converted.PixelWidth * converted.PixelHeight * 3];
        alpha = new byte[converted.PixelWidth * converted.PixelHeight];
        for (var pixel = 0; pixel < alpha.Length; pixel++)
        {
            var sourceOffset = pixel * 4;
            var targetOffset = pixel * 3;
            rgb[targetOffset] = pixels[sourceOffset + 2];
            rgb[targetOffset + 1] = pixels[sourceOffset + 1];
            rgb[targetOffset + 2] = pixels[sourceOffset];
            alpha[pixel] = pixels[sourceOffset + 3];
        }
    }

    private static byte[] Compress(byte[] bytes)
    {
        using var output = new MemoryStream();
        using (var stream = new ZLibStream(output, CompressionLevel.SmallestSize, leaveOpen: true)) stream.Write(bytes);
        return output.ToArray();
    }

    private static byte[] StreamObject(string dictionary, byte[] content)
    {
        using var output = new MemoryStream();
        WriteAscii(output, dictionary + "\nstream\n");
        output.Write(content);
        WriteAscii(output, "\nendstream");
        return output.ToArray();
    }

    private static void WriteAscii(Stream output, string value) => output.Write(Encoding.ASCII.GetBytes(value));
    private static string EscapePdfString(string value) => value.Replace("\\", "\\\\").Replace("(", "\\(").Replace(")", "\\)");
}
