using System.Configuration;
using System.Data;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;

namespace Notein_win;

/// <summary>
/// Interaction logic for App.xaml
/// </summary>
public partial class App : Application
{
    private Mutex? _instance;
    private bool _ownsInstance;
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] private static extern bool ShowWindowAsync(IntPtr window, int command);

    protected override void OnStartup(StartupEventArgs e)
    {
        _instance = new Mutex(true, "Local\\Mojian.Desktop." + Environment.UserName, out _ownsInstance);
        if (!_ownsInstance)
        {
            var existing = Process.GetProcessesByName("Mojian").FirstOrDefault(process => process.Id != Environment.ProcessId && process.MainWindowHandle != IntPtr.Zero);
            if (existing != null)
            {
                ShowWindowAsync(existing.MainWindowHandle, 9);
                SetForegroundWindow(existing.MainWindowHandle);
            }
            Shutdown();
            return;
        }
        base.OnStartup(e);
        try { new MainWindow().Show(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            MessageBox.Show($"无法安全打开笔记，已停止加载。\n{error.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(-1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_ownsInstance) _instance?.ReleaseMutex();
        _instance?.Dispose();
        base.OnExit(e);
    }
}

