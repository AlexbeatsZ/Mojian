using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Ink;
using System.Windows.Media;

namespace MojianMigration
{
    // Keep the millions of ISF point conversions out of PowerShell's pipeline.
    public static class OneNoteInkDecoder
    {
        public static List<Dictionary<string, object>> Decode(StrokeCollection strokes, string drawingId, double anchorX, double anchorY, long zOrder)
        {
            var result = new List<Dictionary<string, object>>();
            var bounds = strokes.GetBounds();
            var matrix = new Matrix(1,0,0,1,anchorX-bounds.Left,anchorY-bounds.Top);
            foreach (var stroke in strokes)
            {
                var points = new List<Dictionary<string, object>>(stroke.StylusPoints.Count);
                var index = 0;
                foreach (var point in stroke.StylusPoints)
                {
                    points.Add(new Dictionary<string, object> {
                        {"x",Math.Round(point.X+matrix.OffsetX,4)}, {"y",Math.Round(point.Y+matrix.OffsetY,4)},
                        {"pressure",Math.Round(Math.Max(0,Math.Min(1,point.PressureFactor)),5)}, {"elapsedMilliseconds",index++*8}
                    });
                }
                var a = stroke.DrawingAttributes;
                stroke.Transform(matrix,false);
                var geometry = PathGeometry.CreateFromGeometry(stroke.GetGeometry());
                var box = geometry.Bounds;
                string id;
                using (var sha=SHA256.Create())
                    id="stroke-onenote-"+BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(drawingId+":"+result.Count)),0,12).Replace("-","").ToLowerInvariant();
                result.Add(new Dictionary<string,object> {
                    {"id",id}, {"kind",a.IsHighlighter?"Highlighter":"Pen"},
                    {"colorHex",string.Format("#{0:X2}{1:X2}{2:X2}{3:X2}",a.Color.A,a.Color.R,a.Color.G,a.Color.B)},
                    {"widthMm",Math.Round(Math.Max(a.Width,a.Height)*25.4/96,4)},
                    {"opacityPercent",a.IsHighlighter?32:(int)Math.Round(a.Color.A*100.0/255)},
                    {"points",points}, {"vectorPath",geometry.ToString(CultureInfo.InvariantCulture)},
                    {"vectorBounds",new[]{box.Left,box.Top,box.Right,box.Bottom}}, {"layerId","onenote-content"}, {"zOrder",zOrder},
                    {"sourceDrawingAttributes",new Dictionary<string,object> {
                        {"width",a.Width},{"height",a.Height},{"fitToCurve",a.FitToCurve},{"ignorePressure",a.IgnorePressure},
                        {"stylusTip",a.StylusTip.ToString()},{"stylusTipTransform",a.StylusTipTransform.ToString(CultureInfo.InvariantCulture)}
                    }}
                });
            }
            return result;
        }
    }
}
