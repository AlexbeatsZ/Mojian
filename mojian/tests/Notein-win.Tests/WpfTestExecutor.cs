using System.Windows;
using System.Windows.Threading;

namespace Notein_win.Tests;

internal static class WpfTestExecutor
{
    private static readonly Lazy<Dispatcher> SharedDispatcher = new(StartDispatcher);
    private static readonly object ExecutionGate = new();
    private static Thread? _dispatcherThread;
    private static volatile bool _shutdownRequested;

    public static void Run(Action action)
    {
        if (SharedDispatcher.IsValueCreated && SharedDispatcher.Value.CheckAccess())
        {
            ObjectDisposedException.ThrowIf(_shutdownRequested, typeof(WpfTestExecutor));
            action();
            return;
        }

        lock (ExecutionGate)
        {
            ObjectDisposedException.ThrowIf(_shutdownRequested, typeof(WpfTestExecutor));
            var dispatcher = SharedDispatcher.Value;
            dispatcher.Invoke(() =>
            {
                var application = Application.Current!;
                var resources = application.Resources;
                try
                {
                    application.Resources = new ResourceDictionary();
                    action();
                }
                finally
                {
                    foreach (Window window in application.Windows.Cast<Window>().ToArray())
                        window.Close();
                    dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                    application.MainWindow = null;
                    application.Resources = resources;
                }
            });
        }
    }

    public static void Shutdown()
    {
        if (SharedDispatcher.IsValueCreated && SharedDispatcher.Value.CheckAccess())
            throw new InvalidOperationException("The assembly runner must shut down the WPF test dispatcher from another thread.");

        lock (ExecutionGate)
        {
            if (!SharedDispatcher.IsValueCreated) return;

            var dispatcher = SharedDispatcher.Value;
            _shutdownRequested = true;
            if (!dispatcher.HasShutdownStarted)
                dispatcher.Invoke(() => Application.Current?.Shutdown());

            if (!_dispatcherThread!.Join(TimeSpan.FromSeconds(15)))
                throw new TimeoutException("The WPF test dispatcher did not exit after application shutdown.");
        }
    }

    private static Dispatcher StartDispatcher()
    {
        var ready = new TaskCompletionSource<Dispatcher>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try
            {
                var application = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                application.Dispatcher.BeginInvoke(() => ready.SetResult(application.Dispatcher));
                application.Run();
            }
            catch (Exception error)
            {
                ready.TrySetException(error);
            }
        }) { IsBackground = true, Name = "Mojian WPF tests" };
        thread.SetApartmentState(ApartmentState.STA);
        _dispatcherThread = thread;
        thread.Start();
        return ready.Task.GetAwaiter().GetResult();
    }
}
