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

// Optional: compares actual on-screen pixels of a supplied page with and without the scene cache.
public sealed class LiveSceneCachePixelProbe(ITestOutputHelper output)
{
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr window);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr window, IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleBitmap(IntPtr dc, int width, int height);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr item);
    [DllImport("gdi32.dll")] private static extern bool BitBlt(IntPtr target, int x, int y, int width, int height, IntPtr source, int sx, int sy, int op);
    [DllImport("gdi32.dll")] private static extern int GetDIBits(IntPtr dc, IntPtr bitmap, uint start, uint lines, uint[] bits, int[] info, uint usage);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr item);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr dc);

    private static uint[] Capture(int left, int top, int width, int height)
    {
        var screen = GetDC(IntPtr.Zero);
        var memory = CreateCompatibleDC(screen);
        var bitmap = CreateCompatibleBitmap(screen, width, height);
        var old = SelectObject(memory, bitmap);
        try
        {
            BitBlt(memory, 0, 0, width, height, screen, left, top, 0x00CC0020);
            SelectObject(memory, old);
            var info = new int[10]; info[0] = 40; info[1] = width; info[2] = -height; info[3] = 1 | (32 << 16);
            var pixels = new uint[width * height];
            GetDIBits(memory, bitmap, 0, (uint)height, pixels, info, 0);
            return pixels;
        }
        finally { DeleteObject(bitmap); DeleteDC(memory); ReleaseDC(IntPtr.Zero, screen); }
    }

    [Fact]
    public void SuppliedPageScreenPixelsMatchWithSceneCache()
    {
        var path = Environment.GetEnvironmentVariable("MOJIAN_LIVE_INK_PAGE_JSON");
        if (string.IsNullOrEmpty(path)) return;
        WpfTestExecutor.Run(() =>
        {
            var json = File.ReadAllText(path);
            var captures = new List<uint[]>();
            foreach (var cached in new[] { false, true, false })
            {
                var canvas = new NoteCanvas
                {
                    Width = 600, Height = 400,
                    Document = JsonSerializer.Deserialize<PageDocument>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!,
                    Appearance = new PageAppearance { ShowTitle = false, Theme = PageTheme.Dark },
                };
                LiveInkCostProbe.SetSceneCache(canvas, cached);
                var window = new Window { Content = canvas, SizeToContent = SizeToContent.WidthAndHeight, Left = 40, Top = 40, Topmost = true, WindowStyle = WindowStyle.None };
                window.Show(); window.UpdateLayout();
                Pump(TimeSpan.FromMilliseconds(1200));
                var origin = canvas.PointToScreen(new Point(0, 0));
                var corner = canvas.PointToScreen(new Point(canvas.ActualWidth, canvas.ActualHeight));
                int width = (int)(corner.X - origin.X) - 2, height = (int)(corner.Y - origin.Y) - 2;
                var pixels = Capture((int)origin.X + 1, (int)origin.Y + 1, width, height);
                captures.Add(pixels);
                output.WriteLine($"cached={cached} capture={width}x{height} dpi={VisualTreeHelper.GetDpi(canvas).PixelsPerDip}");
                window.Close();
                Pump(TimeSpan.FromMilliseconds(200));
            }
            Report("vector-vs-vector", captures[0], captures[2]);
            Report("vector-vs-cache", captures[0], captures[1]);
        });
    }

    private void Report(string name, uint[] a, uint[] b)
    {
        double sum = 0; var large = 0;
        for (var i = 0; i < a.Length; i++)
        {
            var d = 0;
            for (var shift = 0; shift < 24; shift += 8) d = Math.Max(d, Math.Abs((int)((a[i] >> shift) & 255) - (int)((b[i] >> shift) & 255)));
            sum += d; if (d > 48) large++;
        }
        output.WriteLine($"{name}: distinct={a.Distinct().Count()} non_background={a.Count(p => p != a[0])} mean_max_channel_diff={sum / a.Length:F3} pixels_over48={large} of {a.Length}");
    }

    private static void Pump(TimeSpan duration)
    {
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer { Interval = duration };
        timer.Tick += (_, _) => { timer.Stop(); frame.Continue = false; };
        timer.Start();
        Dispatcher.PushFrame(frame);
    }
}
