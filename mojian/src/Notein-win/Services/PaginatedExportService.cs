using System.IO;
using System.IO.Compression;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Xps.Packaging;
using Notein_win.Controls;
using Notein_win.Models;

namespace Notein_win.Services;

public static class PaginatedExportService
{
    public static IReadOnlyList<PdfVectorPage> Capture(NoteCanvas canvas, NotePage page, ExportOptions options)
    {
        if (canvas.Document == null) throw new InvalidOperationException("没有可导出的页面。");
        return ExportLayout.Plan(page, canvas.Document, options).Select(sheet =>
        {
            var kinds = canvas.Document.Layers.Count > 0
                ? new[] { ("页面与内容（原始图层顺序）", CanvasRenderLayer.Composite) }
                : new[] { ("页面背景", CanvasRenderLayer.Background), ("插入内容", CanvasRenderLayer.Inserts), ("墨迹与文字", CanvasRenderLayer.InkAndText) };
            var layers = kinds.Select(pair =>
            {
                var drawing = new DrawingGroup();
                using (var dc = drawing.Open())
                {
                    if (pair.Item2 is CanvasRenderLayer.Background or CanvasRenderLayer.Composite)
                        dc.DrawRectangle(Brushes.White, null, sheet.Output);
                    dc.PushClip(new RectangleGeometry(new Rect(sheet.Margin, sheet.Margin,
                        sheet.Width - 2 * sheet.Margin, sheet.Height - 2 * sheet.Margin)));
                    dc.PushTransform(new MatrixTransform(sheet.Scale, 0, 0, sheet.Scale,
                        sheet.Margin - sheet.Source.Left * sheet.Scale, sheet.Margin - sheet.Source.Top * sheet.Scale));
                    dc.PushClip(new RectangleGeometry(sheet.Source));
                    dc.DrawDrawing(canvas.RenderLayerDrawing(pair.Item2, sheet.Source));
                    dc.Pop(); dc.Pop(); dc.Pop();
                }
                drawing.Freeze();
                return new PdfVectorLayer(pair.Item1, drawing);
            }).ToArray();
            return new PdfVectorPage(sheet.Output, layers, sheet.Width * .75, sheet.Height * .75);
        }).ToArray();
    }

    public static FixedDocument FixedDocument(IReadOnlyList<PdfVectorPage> pages)
    {
        var document = new FixedDocument();
        foreach (var page in pages)
        {
            var surface = new FixedPage { Width = page.Bounds.Width, Height = page.Bounds.Height, Background = Brushes.White };
            var drawing = Composite(page);
            surface.Children.Add(new Image { Source = new DrawingImage(drawing), Width = surface.Width, Height = surface.Height, Stretch = Stretch.Fill });
            var content = new PageContent { Child = surface };
            document.Pages.Add(content);
        }
        return document;
    }

    public static DrawingGroup Composite(PdfVectorPage page)
    {
        var drawing = new DrawingGroup();
        using (var dc = drawing.Open())
        {
            dc.DrawRectangle(Brushes.White, null, page.Bounds);
            foreach (var layer in page.Layers) dc.DrawDrawing(layer.Drawing);
        }
        drawing.Freeze(); return drawing;
    }

    public static void WritePngZip(string path, IReadOnlyList<PdfVectorPage> pages)
    {
        using var file = File.Create(path);
        using var zip = new ZipArchive(file, ZipArchiveMode.Create);
        for (var i = 0; i < pages.Count; i++)
        {
            var page = pages[i];
            var visual = new DrawingVisual();
            const double scale = 200d / 96;
            using (var dc = visual.RenderOpen()) { dc.PushTransform(new ScaleTransform(scale, scale)); dc.DrawDrawing(Composite(page)); }
            var bitmap = new RenderTargetBitmap((int)Math.Ceiling(page.Bounds.Width * scale), (int)Math.Ceiling(page.Bounds.Height * scale), 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var png = new MemoryStream(); encoder.Save(png); png.Position = 0;
            using var output = zip.CreateEntry($"page-{i + 1:0000}.png").Open(); png.CopyTo(output);
        }
    }

    public static void WriteXps(string path, IReadOnlyList<PdfVectorPage> pages)
    {
        using var xps = new XpsDocument(path, FileAccess.Write);
        XpsDocument.CreateXpsDocumentWriter(xps).Write(FixedDocument(pages));
    }
}
