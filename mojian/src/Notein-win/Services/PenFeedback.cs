using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;

namespace Notein_win.Services;

/// <summary>
/// Removes the Windows pen ripple/circle visual effects (tap, double tap, barrel, right tap and
/// press-and-hold feedback) in every Mojian window and popup. Gestures themselves are unchanged.
/// </summary>
public static class PenFeedback
{
    private static bool _installed;

    // FEEDBACK_TYPE values from winuser.h.
    private static readonly int[] PenFeedbackTypes =
    [
        2, // FEEDBACK_PEN_BARRELVISUALIZATION
        3, // FEEDBACK_PEN_TAP
        4, // FEEDBACK_PEN_DOUBLETAP
        5, // FEEDBACK_PEN_PRESSANDHOLD
        6, // FEEDBACK_PEN_RIGHTTAP
    ];

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowFeedbackSetting(IntPtr hwnd, int feedback, uint flags, uint size, ref int configuration);

    public static void Install()
    {
        if (_installed) return;
        _installed = true;
        // On FrameworkElement the Stylus feedback properties inherit, and WPF answers
        // WM_TABLET_QUERYSYSTEMGESTURESTATUS from the hit element's value. Setting it on each
        // logical root (window, context menu) covers popups and dialogs as well.
        EventManager.RegisterClassHandler(typeof(Window), FrameworkElement.LoadedEvent, new RoutedEventHandler((sender, _) =>
        {
            if (sender is not Window window) return;
            DisableTapFeedback(window);
            DisableWindowFeedback(new WindowInteropHelper(window).Handle);
        }));
        EventManager.RegisterClassHandler(typeof(ContextMenu), ContextMenu.OpenedEvent, new RoutedEventHandler((sender, _) =>
        {
            if (sender is DependencyObject menu) DisableTapFeedback(menu);
        }));
    }

    public static void DisableTapFeedback(DependencyObject root) => Stylus.SetIsTapFeedbackEnabled(root, false);

    public static void DisableWindowFeedback(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        foreach (var type in PenFeedbackTypes)
        {
            var enabled = 0;
            try { _ = SetWindowFeedbackSetting(hwnd, type, 0, sizeof(int), ref enabled); }
            catch (EntryPointNotFoundException) { return; }
        }
    }
}
