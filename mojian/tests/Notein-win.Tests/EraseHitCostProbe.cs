using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using Notein_win.Models;
using Notein_win.Services;
using Xunit.Abstractions;

namespace Notein_win.Tests;

// Optional read-only check of a supplied page: modeled eraser hits against stored outlines.
public sealed class EraseHitCostProbe(ITestOutputHelper output)
{
    [Fact]
    public void SuppliedPageModeledHitsMatchStoredOutlines()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_ERASE_HIT_PAGE_JSON");
        if (string.IsNullOrEmpty(path)) return;
        WpfTestExecutor.Run(() =>
        {
            var document = JsonSerializer.Deserialize<PageDocument>(File.ReadAllText(path), new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;
            var strokes = document.Strokes.Where(StrokeEraserHitTest.UsesModeledOutline).Where(s => !string.IsNullOrEmpty(s.VectorPath)).Take(40).ToArray();
            const double radius = 17;
            var checks = 0; var mismatches = 0; var beyond = 0; double exactMs = 0, modeledMs = 0;
            foreach (var stroke in strokes)
            {
                var geometry = stroke.VectorGeometry!;
                bool Exact(Point c, double r) => geometry.FillContainsWithDetail(new EllipseGeometry(c, r, r)) != IntersectionDetail.Empty;
                var b = stroke.Bounds;
                for (var x = b.Left - radius * 1.2; x <= b.Right + radius * 1.2; x += radius / 2)
                for (var y = b.Top - radius * 1.2; y <= b.Bottom + radius * 1.2; y += radius / 2)
                {
                    var c = new Point(x, y);
                    var w = Stopwatch.StartNew(); var exact = Exact(c, radius); exactMs += w.Elapsed.TotalMilliseconds;
                    w.Restart(); var modeled = StrokeEraserHitTest.ModeledOutlineHitsCircle(stroke, c, radius); modeledMs += w.Elapsed.TotalMilliseconds;
                    checks++;
                    if (exact == modeled) continue;
                    mismatches++;
                    if (modeled ? !Exact(c, radius * 1.03) : Exact(c, radius * .97)) beyond++;
                }
            }
            output.WriteLine($"strokes={strokes.Length} checks={checks} mismatches={mismatches} beyond_3pct={beyond} exact_ms={exactMs:F0} modeled_ms={modeledMs:F1}");
        });
    }
}
