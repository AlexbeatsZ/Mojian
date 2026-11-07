using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;
using Xunit.Abstractions;

namespace Notein_win.Tests;

public sealed class CanvasFocusPerformanceTests(ITestOutputHelper output)
{
    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-focus-" + Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }

    private static MainWindow Window(MainViewModel vm)
    {
        foreach (var resource in new[] { "DarkColors", "IconResources", "ControlStyles" })
            Application.Current!.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{resource}.xaml", UriKind.Relative)));
        var window = new MainWindow(vm);
        window.Show(); window.UpdateLayout();
        window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
        return window;
    }

    [Fact]
    public void RepeatedFocusLeavesPendingInkAndAppearanceUntouched()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(temp.Path, new PreferencesStore(System.IO.Path.Combine(temp.Path, "preferences.json")));
        var document = vm.PrimaryDocument!;
        document.Strokes.Add(new StrokeRecord { Points = [new InkPoint(10, 10)] });
        document.HasPendingContentChanges = true;
        var before = File.ReadAllBytes(vm.Store.GetPageDocumentPath(document.NotebookId, document.PageId));
        var changes = 0; vm.PropertyChanged += (_, _) => changes++;
        vm.FocusCanvas(CanvasSlot.Left);
        Assert.True(document.HasPendingContentChanges);
        Assert.Equal(before, File.ReadAllBytes(vm.Store.GetPageDocumentPath(document.NotebookId, document.PageId)));
        Assert.Equal(0, changes);
    }

    [Fact]
    public void RealCanvasPreviewContactDoesNotWaitForBackgroundPageWrite() => WpfTestExecutor.Run(() =>
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(temp.Path, new PreferencesStore(System.IO.Path.Combine(temp.Path, "preferences.json")));
        var window = Window(vm);
        using var held = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var blocker = Task.Run(() =>
        {
            lock (WorkspaceFileGate.ForRoot(temp.Path)) { held.Set(); release.Wait(TimeSpan.FromSeconds(10)); }
        });
        Assert.True(held.Wait(TimeSpan.FromSeconds(5)));
        try
        {
            vm.PrimaryDocument!.Strokes.Add(new StrokeRecord { Points = [new InkPoint(10, 10)] });
            vm.QueueSaveDocument(vm.PrimaryDocument);
            var canvas = (NoteCanvas)window.FindName("LeftCanvas");
            var deadline = Task.Run(async () => { await Task.Delay(1000); release.Set(); });
            var clock = Stopwatch.StartNew();
            canvas.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left)
                { RoutedEvent = Mouse.PreviewMouseDownEvent });
            output.WriteLine($"same_canvas_preview_contact_ms={clock.Elapsed.TotalMilliseconds:F3}, writer_released={release.IsSet}");
            Assert.False(release.IsSet, "Contact on the already focused canvas waited for the page writer.");
            release.Set(); deadline.GetAwaiter().GetResult();
        }
        finally { release.Set(); blocker.GetAwaiter().GetResult(); window.Close(); }
    });

    [Fact]
    public void SwitchingSplitCanvasStillSavesPendingSourceInk()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(temp.Path, new PreferencesStore(System.IO.Path.Combine(temp.Path, "preferences.json")));
        vm.ToggleSplitViewCommand.Execute(null);
        var source = vm.PrimaryDocument!;
        var stroke = new StrokeRecord { Points = [new InkPoint(10, 10)] };
        source.Strokes.Add(stroke); source.HasPendingContentChanges = true;
        vm.FocusCanvas(CanvasSlot.Right);
        Assert.Equal(CanvasSlot.Right, vm.FocusedSlot);
        Assert.False(source.HasPendingContentChanges);
        Assert.Contains(vm.Store.LoadPage(source.NotebookId, source.PageId).Strokes, ink => ink.Id == stroke.Id);
    }
}
