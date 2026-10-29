using System.Windows;
using System.Windows.Threading;

namespace Notein_win.Tests;

public class WpfTestExecutorTests
{
    [Fact]
    public void ConsecutiveWindowsLoadOnTheSameLiveApplicationDispatcher()
    {
        Application? firstApplication = null;
        Dispatcher? firstDispatcher = null;
        for (var iteration = 0; iteration < 3; iteration++)
        {
            WpfTestExecutor.Run(() =>
            {
                var application = Application.Current!;
                firstApplication ??= application;
                firstDispatcher ??= Dispatcher.CurrentDispatcher;
                Assert.Same(firstApplication, application);
                Assert.Same(firstDispatcher, application.Dispatcher);
                Assert.Equal(ApartmentState.STA, Thread.CurrentThread.GetApartmentState());
                Assert.False(application.Dispatcher.HasShutdownStarted);
                Assert.Empty(application.Windows.Cast<Window>());
                var window = new Window { Width = 120, Height = 100, ShowActivated = false };
                window.Show();
                application.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                Assert.True(window.IsLoaded);
                WpfTestExecutor.Run(() => Assert.True(window.Dispatcher.CheckAccess()));
            });
        }
    }

    [Fact]
    public void FailurePropagatesAndCleansWindowsAndResourcesBeforeNextInvocation()
    {
        var failure = new InvalidOperationException("Expected test failure");
        Window? failedWindow = null;
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => WpfTestExecutor.Run(() =>
        {
            Application.Current.Resources["test-only"] = true;
            failedWindow = new Window { Width = 120, Height = 100, ShowActivated = false };
            failedWindow.Show();
            throw failure;
        })));
        WpfTestExecutor.Run(() =>
        {
            Assert.False(failedWindow!.IsVisible);
            Assert.Empty(Application.Current.Windows.Cast<Window>());
            Assert.False(Application.Current.Resources.Contains("test-only"));
        });
    }
}
