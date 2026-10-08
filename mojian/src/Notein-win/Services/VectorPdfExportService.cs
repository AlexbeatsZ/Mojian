using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Notein_win.Services;

public sealed record PdfVectorLayer(string Name, Drawing Drawing);
public sealed record PdfVectorPage(Rect Bounds, IReadOnlyList<PdfVectorLayer> Layers,
    double? WidthPoints = null, double? HeightPoints = null);

/// <summary>Writes native ink and glyph outlines as PDF paths; only actual images become image XObjects.</summary>
public static class VectorPdfExportService
{
    public static void Write(string path, IReadOnlyList<PdfVectorPage> pages) => new Writer().Write(path, pages);

    private sealed class Writer
    {
        private readonly List<byte[]> _objects = [];
        private readonly Dictionary<string, int> _layers = [];
        private readonly Dictionary<int, int> _alphas = [];
        private readonly Dictionary<BitmapSource, int> _images = [];
        private int Add(string text) { _objects.Add(Encoding.ASCII.GetBytes(text)); return _objects.Count; }
        private void Set(int id, string text) => _objects[id - 1] = Encoding.ASCII.GetBytes(text);
        private int Stream(string dictionary, byte[] bytes)
        {
            using var output = new MemoryStream();
            using (var zip = new ZLibStream(output, CompressionLevel.SmallestSize, true)) zip.Write(bytes);
            var payload = output.ToArray();
            var head = Encoding.ASCII.GetBytes($"<< {dictionary} /Filter /FlateDecode /Length {payload.Length} >>\nstream\n");
            var tail = Encoding.ASCII.GetBytes("\nendstream");
            _objects.Add([.. head, .. payload, .. tail]);
            return _objects.Count;
        }

        public void Write(string path, IReadOnlyList<PdfVectorPage> pages)
        {
            if (pages.Count == 0) throw new ArgumentException("至少需要一个 PDF 页面。");
            var catalog = Add(""); var tree = Add("");
            foreach (var layer in pages.SelectMany(p => p.Layers))
                if (!_layers.ContainsKey(layer.Name)) _layers[layer.Name] = Add("<< /Type /OCG /Name <FEFF" + Convert.ToHexString(Encoding.BigEndianUnicode.GetBytes(layer.Name)) + "> >>");
            var pageRecords = new List<(int Id, int Content, double Width, double Height)>();
            foreach (var page in pages)
            {
                if (page.Bounds.IsEmpty || page.Bounds.Width <= 0 || page.Bounds.Height <= 0) throw new ArgumentException("无效的 PDF 页面范围。");
                var width = page.WidthPoints ?? page.Bounds.Width * .75;
                var height = page.HeightPoints ?? page.Bounds.Height * .75;
                var sx = width / page.Bounds.Width; var sy = height / page.Bounds.Height;
                var commands = new StringBuilder();
                commands.AppendLine($"q {N(sx)} 0 0 {N(-sy)} {N(-page.Bounds.Left * sx)} {N(page.Bounds.Bottom * sy)} cm");
                commands.AppendLine($"{N(page.Bounds.X)} {N(page.Bounds.Y)} {N(page.Bounds.Width)} {N(page.Bounds.Height)} re W n");
                foreach (var layer in page.Layers)
                {
                    commands.AppendLine($"/OC /OC{_layers[layer.Name]} BDC");
                    Draw(commands, layer.Drawing, 1);
                    commands.AppendLine("EMC");
                }
                commands.AppendLine("Q");
                var content = Stream("", Encoding.ASCII.GetBytes(commands.ToString()));
                pageRecords.Add((Add(""), content, width, height));
            }
            var properties = string.Join(' ', _layers.Values.Select(id => $"/OC{id} {id} 0 R"));
            var alphaResources = string.Join(' ', _alphas.Values.Select(id => $"/GS{id} {id} 0 R"));
            var imageResources = string.Join(' ', _images.Values.Select(id => $"/Im{id} {id} 0 R"));
            foreach (var page in pageRecords)
                Set(page.Id, $"<< /Type /Page /Parent {tree} 0 R /MediaBox [0 0 {N(page.Width)} {N(page.Height)}] /Resources << /Properties << {properties} >> /ExtGState << {alphaResources} >> /XObject << {imageResources} >> >> /Contents {page.Content} 0 R >>");
            var refs = string.Join(' ', _layers.Values.Select(id => $"{id} 0 R"));
            Set(catalog, $"<< /Type /Catalog /Pages {tree} 0 R /OCProperties << /OCGs [{refs}] /D << /Order [{refs}] /ON [{refs}] >> >> >>");
            Set(tree, $"<< /Type /Pages /Kids [{string.Join(' ', pageRecords.Select(p => $"{p.Id} 0 R"))}] /Count {pageRecords.Count} >>");
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(System.IO.Path.GetFullPath(path))!);
            using var file = File.Create(path);
            void Ascii(string value) => file.Write(Encoding.ASCII.GetBytes(value));
            Ascii("%PDF-1.7\n%Mojian-vector\n");
            var offsets = new List<long>();
            for (var i = 0; i < _objects.Count; i++)
            {
                offsets.Add(file.Position); Ascii($"{i + 1} 0 obj\n"); file.Write(_objects[i]); Ascii("\nendobj\n");
            }
            var xref = file.Position;
            Ascii($"xref\n0 {_objects.Count + 1}\n0000000000 65535 f \n");
            foreach (var offset in offsets) Ascii(offset.ToString("0000000000", CultureInfo.InvariantCulture) + " 00000 n \n");
            Ascii($"trailer\n<< /Size {_objects.Count + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        }

        private void Draw(StringBuilder output, Drawing drawing, double opacity)
        {
            output.AppendLine("q");
            switch (drawing)
            {
                case DrawingGroup group:
                    if (group.OpacityMask != null) throw new NotSupportedException("PDF 矢量导出不支持不透明度蒙版。");
                    Matrix(output, group.Transform?.Value ?? System.Windows.Media.Matrix.Identity);
                    if (group.ClipGeometry != null) { Path(output, group.ClipGeometry); output.AppendLine(group.ClipGeometry.GetFlattenedPathGeometry().FillRule == FillRule.EvenOdd ? "W* n" : "W n"); }
                    foreach (var child in group.Children) Draw(output, child, opacity * group.Opacity);
                    break;
                case GeometryDrawing geometry:
                    if (geometry.Brush != null) Fill(output, geometry.Geometry, geometry.Brush, opacity);
                    if (geometry.Pen != null) Fill(output, geometry.Geometry.GetWidenedPathGeometry(geometry.Pen, .001, ToleranceType.Absolute), geometry.Pen.Brush, opacity);
                    break;
                case GlyphRunDrawing glyph:
                    Fill(output, glyph.GlyphRun.BuildGeometry(), glyph.ForegroundBrush, opacity);
                    break;
                case ImageDrawing image when image.ImageSource is BitmapSource bitmap:
                    Alpha(output, opacity);
                    var id = Image(bitmap);
                    var r = image.Rect;
                    output.AppendLine($"{N(r.Width)} 0 0 {N(-r.Height)} {N(r.X)} {N(r.Bottom)} cm /Im{id} Do");
                    break;
                default: throw new NotSupportedException($"PDF 矢量导出不支持 {drawing.GetType().Name}。");
            }
            output.AppendLine("Q");
        }

        private void Fill(StringBuilder output, Geometry geometry, Brush brush, double opacity)
        {
            if (geometry.IsEmpty()) return;
            if (brush is not SolidColorBrush solid) throw new NotSupportedException("PDF 矢量导出需要纯色画笔。");
            var color = solid.Color;
            Alpha(output, opacity * solid.Opacity * color.A / 255d);
            output.AppendLine($"{N(color.R / 255d)} {N(color.G / 255d)} {N(color.B / 255d)} rg");
            var rule = Path(output, geometry);
            output.AppendLine(rule == FillRule.EvenOdd ? "f*" : "f");
        }

        private void Alpha(StringBuilder output, double value)
        {
            var key = (int)Math.Round(Math.Clamp(value, 0, 1) * 1000000);
            if (!_alphas.TryGetValue(key, out var id)) _alphas[key] = id = Add($"<< /Type /ExtGState /ca {N(key / 1000000d)} /CA {N(key / 1000000d)} >>");
            output.AppendLine($"/GS{id} gs");
        }

        private int Image(BitmapSource bitmap)
        {
            if (_images.TryGetValue(bitmap, out var existing)) return existing;
            var converted = new FormatConvertedBitmap(bitmap, PixelFormats.Bgra32, null, 0);
            var count = bitmap.PixelWidth * bitmap.PixelHeight;
            var bytes = new byte[count * 4]; converted.CopyPixels(bytes, bitmap.PixelWidth * 4, 0);
            var rgb = new byte[count * 3]; var alpha = new byte[count];
            for (var i = 0; i < count; i++) { rgb[i * 3] = bytes[i * 4 + 2]; rgb[i * 3 + 1] = bytes[i * 4 + 1]; rgb[i * 3 + 2] = bytes[i * 4]; alpha[i] = bytes[i * 4 + 3]; }
            var mask = alpha.All(a => a == 255) ? "" : $" /SMask {Stream($"/Type /XObject /Subtype /Image /Width {bitmap.PixelWidth} /Height {bitmap.PixelHeight} /ColorSpace /DeviceGray /BitsPerComponent 8", alpha)} 0 R";
            var id = Stream($"/Type /XObject /Subtype /Image /Width {bitmap.PixelWidth} /Height {bitmap.PixelHeight} /ColorSpace /DeviceRGB /BitsPerComponent 8{mask}", rgb);
            _images[bitmap] = id; return id;
        }
    }

    private static string N(double value) => value.ToString("0.######", CultureInfo.InvariantCulture);
    private static void Matrix(StringBuilder output, Matrix matrix)
    {
        if (!matrix.IsIdentity) output.AppendLine($"{N(matrix.M11)} {N(matrix.M12)} {N(matrix.M21)} {N(matrix.M22)} {N(matrix.OffsetX)} {N(matrix.OffsetY)} cm");
    }

    private static FillRule Path(StringBuilder output, Geometry geometry)
    {
        var path = PathGeometry.CreateFromGeometry(geometry);
        var transform = path.Transform?.Value ?? System.Windows.Media.Matrix.Identity;
        string P(Point point) { var p = transform.Transform(point); return $"{N(p.X)} {N(p.Y)}"; }
        void Line(Point point) => output.AppendLine(P(point) + " l");
        void Cubic(Point c1, Point c2, Point end) => output.AppendLine($"{P(c1)} {P(c2)} {P(end)} c");
        foreach (var figure in path.Figures)
        {
            var current = figure.StartPoint;
            output.AppendLine(P(current) + " m");
            foreach (var segment in figure.Segments)
            {
                switch (segment)
                {
                    case LineSegment line: Line(line.Point); current = line.Point; break;
                    case PolyLineSegment lines: foreach (var point in lines.Points) { Line(point); current = point; } break;
                    case BezierSegment curve: Cubic(curve.Point1, curve.Point2, curve.Point3); current = curve.Point3; break;
                    case PolyBezierSegment curves:
                        for (var i = 0; i < curves.Points.Count; i += 3) { Cubic(curves.Points[i], curves.Points[i + 1], curves.Points[i + 2]); current = curves.Points[i + 2]; } break;
                    case QuadraticBezierSegment curve:
                        Cubic(current + (curve.Point1 - current) * (2d / 3), curve.Point2 + (curve.Point1 - curve.Point2) * (2d / 3), curve.Point2); current = curve.Point2; break;
                    case PolyQuadraticBezierSegment curves:
                        for (var i = 0; i < curves.Points.Count; i += 2) { var control = curves.Points[i]; var end = curves.Points[i + 1]; Cubic(current + (control - current) * (2d / 3), end + (control - end) * (2d / 3), end); current = end; } break;
                    case ArcSegment arc: Arc(current, arc, Cubic, Line); current = arc.Point; break;
                    default: throw new NotSupportedException($"PDF 路径不支持 {segment.GetType().Name}。");
                }
            }
            if (figure.IsClosed) output.AppendLine("h");
        }
        return path.FillRule;
    }

    private static void Arc(Point start, ArcSegment arc, Action<Point, Point, Point> cubic, Action<Point> line)
    {
        var end = arc.Point; var rx = Math.Abs(arc.Size.Width); var ry = Math.Abs(arc.Size.Height);
        if (rx < 1e-12 || ry < 1e-12) { line(end); return; }
        if ((end - start).LengthSquared < 1e-20) return;
        var phi = arc.RotationAngle * Math.PI / 180; var cos = Math.Cos(phi); var sin = Math.Sin(phi);
        var dx = (start.X - end.X) / 2; var dy = (start.Y - end.Y) / 2;
        var x = cos * dx + sin * dy; var y = -sin * dx + cos * dy;
        var radiusScale = x * x / (rx * rx) + y * y / (ry * ry);
        if (radiusScale > 1) { var scale = Math.Sqrt(radiusScale); rx *= scale; ry *= scale; }
        var sweep = arc.SweepDirection == SweepDirection.Clockwise;
        var numerator = rx * rx * ry * ry - rx * rx * y * y - ry * ry * x * x;
        var denominator = rx * rx * y * y + ry * ry * x * x;
        var factor = (arc.IsLargeArc == sweep ? -1 : 1) * Math.Sqrt(Math.Max(0, numerator / denominator));
        var cxLocal = factor * rx * y / ry; var cyLocal = -factor * ry * x / rx;
        var cx = cos * cxLocal - sin * cyLocal + (start.X + end.X) / 2;
        var cy = sin * cxLocal + cos * cyLocal + (start.Y + end.Y) / 2;
        var theta = Math.Atan2((y - cyLocal) / ry, (x - cxLocal) / rx);
        var finish = Math.Atan2((-y - cyLocal) / ry, (-x - cxLocal) / rx);
        var delta = finish - theta;
        if (sweep && delta < 0) delta += Math.PI * 2;
        if (!sweep && delta > 0) delta -= Math.PI * 2;
        var pieces = (int)Math.Ceiling(Math.Abs(delta) / (Math.PI / 2));
        var step = delta / pieces;
        Point At(double t) => new(cx + rx * Math.Cos(t) * cos - ry * Math.Sin(t) * sin, cy + rx * Math.Cos(t) * sin + ry * Math.Sin(t) * cos);
        Vector Tangent(double t) => new(-rx * Math.Sin(t) * cos - ry * Math.Cos(t) * sin, -rx * Math.Sin(t) * sin + ry * Math.Cos(t) * cos);
        for (var i = 0; i < pieces; i++)
        {
            var next = theta + step; var k = 4d / 3 * Math.Tan(step / 4);
            cubic(At(theta) + Tangent(theta) * k, At(next) - Tangent(next) * k, i == pieces - 1 ? end : At(next)); theta = next;
        }
    }
}
