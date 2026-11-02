using System.Configuration;
using System.Data;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using Microsoft.Win32;
using Notein_win.Services;
using Notein_win.ViewModels;

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

    private static readonly string CrashLogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Mojian", "logs", "crash.log");
    private bool _crashNoticeShown;

    protected override void OnStartup(StartupEventArgs e)
    {
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) => WriteCrashLog("AppDomain", args.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, args) => { WriteCrashLog("Task", args.Exception); args.SetObserved(); };
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
        catch (WorkspaceLibraryMissingException missing)
        {
            if (!OpenChosenWorkspace(missing)) Shutdown(-1);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            ShowLoadFailure(error);
            Shutdown(-1);
        }
    }

    private static void ShowLoadFailure(Exception error) =>
        MessageBox.Show($"无法安全打开笔记，已停止加载。\n{error.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error);

    // The remembered workspace stays untouched; only an explicitly chosen folder may be opened or seeded.
    private static bool OpenChosenWorkspace(WorkspaceLibraryMissingException missing)
    {
        if (MessageBox.Show($"{missing.Message}\n\n是否选择其他笔记目录？", "墨笺", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes)
            return false;
        var dialog = new OpenFolderDialog { Title = "选择墨笺工作区", Multiselect = false };
        if (dialog.ShowDialog() != true) return false;
        try { new MainWindow(new MainViewModel(dialog.FolderName, null)).Show(); return true; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            ShowLoadFailure(error);
            return false;
        }
    }

    // Input handlers run on the dispatcher; an exception there used to terminate the
    // process without any trace. Record it and keep the window (and unsaved ink) alive.
    private void OnDispatcherUnhandledException(object sender, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
    {
        WriteCrashLog("Dispatcher", e.Exception);
        e.Handled = true;
        if (_crashNoticeShown) return;
        _crashNoticeShown = true;
        Dispatcher.BeginInvoke(() => MessageBox.Show(
            $"墨笺遇到一个错误，已阻止闪退。\n{e.Exception.Message}\n\n详细信息已记录到：\n{CrashLogPath}",
            "墨笺", MessageBoxButton.OK, MessageBoxImage.Warning));
    }

    private static void WriteCrashLog(string source, Exception? error)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CrashLogPath)!);
            File.AppendAllText(CrashLogPath, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {source}{Environment.NewLine}{error}{Environment.NewLine}{Environment.NewLine}");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_ownsInstance) _instance?.ReleaseMutex();
        _instance?.Dispose();
        base.OnExit(e);
    }
}

