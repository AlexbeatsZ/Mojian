using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Xunit.Abstractions;

namespace Notein_win.Tests;

// Optional read-only probe of a supplied page in a real window. It drives pen samples at 240 Hz
// and reports UI and WPF render-thread CPU, which isolated code-path timings cannot see.
public sealed class LiveInkCostProbe(ITestOutputHelper output)
{
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();

    private static void Call(NoteCanvas canvas, string name, params object[] args) =>
        typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);

    internal static void SetSceneCache(NoteCanvas canvas, bool enabled) => typeof(NoteCanvas)
        .GetProperty("StaticSceneCacheEnabled", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(canvas, enabled);

    private static Dictionary<int, TimeSpan> ThreadTimes()
    {
        using var process = Process.GetCurrentProcess();
        var result = new Dictionary<int, TimeSpan>();
        foreach (ProcessThread thread in process.Threads)
        {
            try { result[thread.Id] = thread.TotalProcessorTime; } catch (Exception) { }
        }
        return result;
    }

    [Fact]
    public void SuppliedPageLiveWritingAndErasingCost()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_LIVE_INK_PAGE_JSON");
        if (string.IsNullOrEmpty(path)) return;
        WpfTestExecutor.Run(() =>
        {
            var json = File.ReadAllText(path);
            var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            foreach (var cached in new[] { false, true })
            {
                var document = JsonSerializer.Deserialize<PageDocument>(json, options)!;
                if (Environment.GetEnvironmentVariable("MOJIAN_LIVE_INK_VIEWPORT") is { Length: > 0 } viewport)
                {
                    var parts = viewport.Split(',').Select(float.Parse).ToArray();
                    document.Viewport = new PageViewport { Scale = parts[0], TranslationX = parts[1], TranslationY = parts[2] };
                }
                var canvas = new NoteCanvas
                {
                    Width = 1900, Height = 1100, Document = document,
                    Appearance = new PageAppearance { ShowTitle = false, Theme = PageTheme.Dark },
                    SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen),
                };
                SetSceneCache(canvas, cached);
                var window = new Window { Title = "Mojian live ink probe", Content = canvas, SizeToContent = SizeToContent.WidthAndHeight, Left = 0, Top = 0 };
                window.Show(); window.UpdateLayout();
                Pump(TimeSpan.FromMilliseconds(800));
                var uiThread = (int)GetCurrentThreadId();
                foreach (var phase in new[] { "write", "erase" })
                {
                    canvas.SelectedTool = phase == "write"
                        ? new ToolPreset("pen", DrawingToolKind.Pen)
                        : new ToolPreset("eraser", DrawingToolKind.Eraser) { EraserRadiusMm = 4.5f };
                    Pump(TimeSpan.FromMilliseconds(300));
                    SampleTimes.Clear();
                    var gc0 = GC.CollectionCount(0); var gc2 = GC.CollectionCount(2);
                    var before = ThreadTimes();
                    var (frames, gaps, wall, inputMs, liftMs) = Drive(canvas, strokes: 4, millisecondsPerStroke: 1500, phase == "erase");
                    var after = ThreadTimes();
                    var deltas = after.Where(entry => before.ContainsKey(entry.Key))
                        .Select(entry => (Id: entry.Key, Ms: (entry.Value - before[entry.Key]).TotalMilliseconds))
                        .OrderByDescending(entry => entry.Ms).ToArray();
                    var ui = deltas.FirstOrDefault(entry => entry.Id == uiThread).Ms;
                    var others = string.Join(",", deltas.Where(entry => entry.Id != uiThread).Take(3).Select(entry => $"{entry.Ms:F0}"));
                    var ordered = gaps.Order().ToArray();
                    output.WriteLine($"cache={cached} {phase}: strokes={document.Strokes.Count} frames={frames} wall_ms={wall:F0} " +
                                     $"frame_mean={wall / Math.Max(1, frames):F2} gap_p95={ordered[(int)(ordered.Length * .95)]:F1} gap_max={ordered[^1]:F1} " +
                                     $"ui_cpu_ms={ui:F0} other_cpu_ms=[{others}] input_ms={inputMs:F0} lift_ms={liftMs:F1} " +
                                     $"sample_p50={Pct(.5):F3} sample_p99={Pct(.99):F2} sample_max={Pct(1):F1} over4ms={SampleTimes.Count(t => t > 4)} tier={RenderCapability.Tier >> 16} gc0={GC.CollectionCount(0) - gc0} gc2={GC.CollectionCount(2) - gc2}");
                    SampleTimes.Clear();
                }
                window.Close();
                Pump(TimeSpan.FromMilliseconds(300));
            }
        });
    }

    private static double Pct(double q)
    {
        var sorted = SampleTimes.Order().ToArray();
        return sorted.Length == 0 ? 0 : sorted[Math.Min(sorted.Length - 1, (int)(sorted.Length * q))];
    }

    private static void Pump(TimeSpan duration)
    {
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer { Interval = duration };
        timer.Tick += (_, _) => { timer.Stop(); frame.Continue = false; };
        timer.Start();
        Dispatcher.PushFrame(frame);
    }

    private static readonly List<double> SampleTimes = [];

    private static (int Frames, List<double> Gaps, double Wall, double InputMs, double LiftMs) Drive(
        NoteCanvas canvas, int strokes, double millisecondsPerStroke, bool erase)
    {
        var frame = new DispatcherFrame();
        var gaps = new List<double>();
        var clock = Stopwatch.StartNew();
        var input = new Stopwatch();
        var lift = 0d;
        var frames = 0; var stroke = 0; var fed = 0; var strokeStart = 0d; var previous = 0d; var down = false;
        Point Sample(int index)
        {
            // A zig-zag across the dense middle of the view, offset per stroke.
            var t = index / 240d;
            var x = 120 + (t * 900) % 1600;
            var y = 160 + stroke * 220 + Math.Sin(t * 40) * 60;
            return new Point(x, y);
        }
        EventHandler handler = (_, _) =>
        {
            var now = clock.Elapsed.TotalMilliseconds;
            if (frames > 0) gaps.Add(now - previous);
            previous = now; frames++;
            if (stroke >= strokes) { frame.Continue = false; return; }
            input.Start();
            if (!down)
            {
                Call(canvas, "BeginPointer", Sample(0), .5f, 0f, 0f, true, false, erase);
                down = true; fed = 1; strokeStart = now;
            }
            var target = (int)((now - strokeStart) * .24);
            for (; fed < target; fed++)
            {
                var one = Stopwatch.StartNew();
                Call(canvas, "ContinuePointer", Sample(fed), .5f, 0f, 0f, true);
                SampleTimes.Add(one.Elapsed.TotalMilliseconds);
            }
            input.Stop();
            if (now - strokeStart >= millisecondsPerStroke)
            {
                var watch = Stopwatch.StartNew();
                Call(canvas, "EndPointer", true);
                lift += watch.Elapsed.TotalMilliseconds;
                down = false; stroke++;
            }
        };
        CompositionTarget.Rendering += handler;
        try { Dispatcher.PushFrame(frame); }
        finally { CompositionTarget.Rendering -= handler; }
        return (frames, gaps, clock.Elapsed.TotalMilliseconds, input.Elapsed.TotalMilliseconds, lift);
    }
}
