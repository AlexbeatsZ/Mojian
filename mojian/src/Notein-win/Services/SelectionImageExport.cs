using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;

namespace Notein_win.Services;

/// <summary>Exports only selected document content, with transparent margins and native ink paths.</summary>
public static class SelectionImageExport
{
    private static readonly XNamespace Svg = "http://www.w3.org/2000/svg";
    private static readonly XNamespace Xlink = "http://www.w3.org/1999/xlink";
    private static string N(double value) => value.ToString("0.######", CultureInfo.InvariantCulture);

    public static BitmapSource PngBitmap(Drawing drawing, Rect bounds)
    {
        var scale = Math.Min(2, Math.Min(8192 / Math.Max(1, Math.Max(bounds.Width, bounds.Height)),
            Math.Sqrt(16000000 / Math.Max(1, bounds.Width * bounds.Height))));
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.PushTransform(new ScaleTransform(scale, scale));
            dc.PushTransform(new TranslateTransform(-bounds.X, -bounds.Y));
            dc.DrawDrawing(drawing);
            dc.Pop(); dc.Pop();
        }
        var bitmap = new RenderTargetBitmap(Math.Max(1, (int)Math.Ceiling(bounds.Width * scale)),
            Math.Max(1, (int)Math.Ceiling(bounds.Height * scale)), 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual); bitmap.Freeze(); return bitmap;
    }

    public static byte[] PngBytes(BitmapSource bitmap)
    {
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = new MemoryStream(); encoder.Save(stream); return stream.ToArray();
    }

    public static string SvgText(Drawing drawing, Rect bounds)
    {
        var root = new XElement(Svg + "svg", new XAttribute("version", "1.1"),
            new XAttribute(XNamespace.Xmlns + "xlink", Xlink),
            new XAttribute("width", N(bounds.Width)), new XAttribute("height", N(bounds.Height)),
            new XAttribute("viewBox", $"{N(bounds.X)} {N(bounds.Y)} {N(bounds.Width)} {N(bounds.Height)}"));
        var definitions = new XElement(Svg + "defs"); root.Add(definitions);
        var clipId = 0;
        void Fill(XElement parent, Geometry geometry, Brush? brush)
        {
            if (brush == null || geometry.IsEmpty()) return;
            if (brush is not SolidColorBrush solid) throw new NotSupportedException("SVG 导出需要纯色画笔。");
            var path = geometry.GetOutlinedPathGeometry();
            var data = path.ToString(CultureInfo.InvariantCulture);
            if (data.StartsWith("F0", StringComparison.Ordinal) || data.StartsWith("F1", StringComparison.Ordinal)) data = data[2..].TrimStart();
            var element = new XElement(Svg + "path", new XAttribute("d", data),
                new XAttribute("fill", $"#{solid.Color.R:X2}{solid.Color.G:X2}{solid.Color.B:X2}"),
                new XAttribute("fill-opacity", N(solid.Opacity * solid.Color.A / 255d)),
                new XAttribute("fill-rule", path.FillRule == FillRule.EvenOdd ? "evenodd" : "nonzero"));
            var transform = path.Transform?.Value ?? Matrix.Identity;
            if (!transform.IsIdentity) element.SetAttributeValue("transform", MatrixText(transform));
            parent.Add(element);
        }
        void Write(XElement parent, Drawing item)
        {
            switch (item)
            {
                case DrawingGroup group:
                    var node = new XElement(Svg + "g", new XAttribute("opacity", N(group.Opacity)));
                    var matrix = group.Transform?.Value ?? Matrix.Identity;
                    if (!matrix.IsIdentity) node.SetAttributeValue("transform", MatrixText(matrix));
                    if (group.ClipGeometry != null)
                    {
                        var id = $"clip{++clipId}";
                        var clip = new XElement(Svg + "clipPath", new XAttribute("id", id), new XAttribute("clipPathUnits", "userSpaceOnUse"));
                        Fill(clip, group.ClipGeometry, Brushes.Black); definitions.Add(clip);
                        node.SetAttributeValue("clip-path", $"url(#{id})");
                    }
                    foreach (var child in group.Children) Write(node, child);
                    parent.Add(node); break;
                case GeometryDrawing geometry:
                    Fill(parent, geometry.Geometry, geometry.Brush);
                    if (geometry.Pen != null) Fill(parent, geometry.Geometry.GetWidenedPathGeometry(geometry.Pen), geometry.Pen.Brush);
                    break;
                case GlyphRunDrawing glyph: Fill(parent, glyph.GlyphRun.BuildGeometry(), glyph.ForegroundBrush); break;
                case ImageDrawing image when image.ImageSource is BitmapSource bitmap:
                    parent.Add(new XElement(Svg + "image", new XAttribute("x", N(image.Rect.X)), new XAttribute("y", N(image.Rect.Y)),
                        new XAttribute("width", N(image.Rect.Width)), new XAttribute("height", N(image.Rect.Height)),
                        new XAttribute("preserveAspectRatio", "none"),
                        new XAttribute(Xlink + "href", "data:image/png;base64," + Convert.ToBase64String(PngBytes(bitmap)))));
                    break;
                default: throw new NotSupportedException($"SVG 导出不支持 {item.GetType().Name}。");
            }
        }
        Write(root, drawing); return root.ToString(SaveOptions.DisableFormatting);
    }

    private static string MatrixText(Matrix m) => $"matrix({N(m.M11)} {N(m.M12)} {N(m.M21)} {N(m.M22)} {N(m.OffsetX)} {N(m.OffsetY)})";
}
