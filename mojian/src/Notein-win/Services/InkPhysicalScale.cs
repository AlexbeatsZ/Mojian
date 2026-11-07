using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Notein_win.Services;

public static class InkPhysicalScale
{
    public static double CentimetersPerUnit(double zoom, double dpiScale, double rawDpi) =>
        Math.Max(.00001, zoom) * dpiScale * 2.54 / rawDpi;

    public static double ForVisual(Visual visual, double zoom)
    {
        var dpi = VisualTreeHelper.GetDpi(visual);
        var rawDpi = dpi.PixelsPerInchX;
        if (PresentationSource.FromVisual(visual) is HwndSource source)
        {
            var monitor = MonitorFromWindow(source.Handle, 2);
            if (GetDpiForMonitor(monitor, 2 /* MDT_RAW_DPI */, out var x, out _) == 0 && x > 0)
                rawDpi = x;
        }
        return CentimetersPerUnit(zoom, dpi.DpiScaleX, rawDpi);
    }

    [DllImport("user32.dll")] private static extern nint MonitorFromWindow(nint window, uint flags);
    [DllImport("shcore.dll")] private static extern int GetDpiForMonitor(nint monitor, int type, out uint x, out uint y);
}
