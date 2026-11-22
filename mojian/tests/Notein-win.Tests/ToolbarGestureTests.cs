using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public sealed class ToolbarGestureTests
{
    [Fact]
    public void UndoDoubleTapRedoesAndSingleTapUndoesOnlyAfterFlushOrTimeout() => WpfTestExecutor.Run(() =>
    {
        var undo = 0; var redo = 0; var enabled = true;
        var gesture = new UndoTapGesture(() => undo++, () => redo++, () => enabled);
        gesture.Tap(); Assert.True(gesture.IsPending); Assert.Equal((0, 0), (undo, redo));
        gesture.Tap(); Assert.False(gesture.IsPending); Assert.Equal((0, 1), (undo, redo));
        gesture.Tap(); gesture.Flush(); Assert.Equal((1, 1), (undo, redo));
        gesture.Flush(); Assert.Equal((1, 1), (undo, redo));

        gesture.Tap();
        var frame = new DispatcherFrame();
        var deadline = new DispatcherTimer { Interval = UndoTapGesture.DoubleTapInterval * 4 };
        deadline.Tick += (_, _) => { deadline.Stop(); frame.Continue = false; };
        deadline.Start(); Dispatcher.PushFrame(frame);
        Assert.False(gesture.IsPending); Assert.Equal((2, 1), (undo, redo));

        enabled = false;
        gesture.Tap(); gesture.Tap(); Assert.False(gesture.IsPending); Assert.Equal((4, 1), (undo, redo));
    });

    [Fact]
    public void UndoDoubleTapUsesTheConfiguredInterval() => WpfTestExecutor.Run(() =>
    {
        var undo = 0; var redo = 0; var interval = TimeSpan.FromMilliseconds(600);
        var gesture = new UndoTapGesture(() => undo++, () => redo++, () => true, null, () => interval);
        gesture.Tap(); Wait(TimeSpan.FromMilliseconds(400));
        Assert.True(gesture.IsPending); gesture.Tap(); Assert.Equal((0, 1), (undo, redo));
        interval = TimeSpan.FromMilliseconds(150);
        gesture.Tap(); Wait(TimeSpan.FromMilliseconds(400));
        Assert.False(gesture.IsPending); Assert.Equal((1, 1), (undo, redo));
    });

    [Fact]
    public void UndoDoubleTapSettingDefaultsOnAndPersists() => WpfTestExecutor.Run(() =>
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-undo-tap", "fixture-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try
        {
            var prefs = new PreferencesStore(Path.Combine(root, "preferences.json"));
            var model = new MainViewModel(root, prefs);
            Assert.True(model.UndoDoubleTapRedoEnabled); Assert.Equal("开启", model.UndoDoubleTapRedoButtonText);
            Assert.Equal(300, model.UndoDoubleTapIntervalMillis);
            model.ToggleUndoDoubleTapRedoCommand.Execute(null); model.UndoDoubleTapIntervalMillis = 5000; model.SaveAll();
            Assert.Equal(MainViewModel.MaximumUndoDoubleTapIntervalMillis, model.UndoDoubleTapIntervalMillis);
            model.UndoDoubleTapIntervalMillis = 420; model.SaveAll();
            var reloaded = new MainViewModel(root, prefs);
            Assert.False(reloaded.UndoDoubleTapRedoEnabled); Assert.Equal(420, reloaded.UndoDoubleTapIntervalMillis);
        }
        finally { try { Directory.Delete(root, true); } catch (IOException) { } }
    });

    [Fact]
    public void UndoShortcutDoublePressRedoesWhileUndoButtonsStayImmediate() => WpfTestExecutor.Run(() =>
    {
        foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
            Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-undo-shortcut", "fixture-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        MainWindow? window = null;
        try
        {
            var model = new MainViewModel(root, new PreferencesStore(Path.Combine(root, "preferences.json")));
            window = new MainWindow(model); window.Show(); Idle(window);
            // Keyboard.Modifiers cannot be faked, so bind undo to a plain key; Ctrl+Z takes the same path.
            model.SetShortcut("draw:undo", new ShortcutGesture(Key.F9, ModifierKeys.None));
            model.UndoDoubleTapIntervalMillis = 200;
            var canvas = (NoteCanvas)window.FindName("LeftCanvas");
            canvas.SelectedTool = new ToolPreset("shape-default", DrawingToolKind.Shape) { ShapeKind = ShapeKind.Rectangle };
            for (var i = 0; i < 3; i++) DrawShape(canvas, 40 + i * 120);
            var strokes = canvas.Document!.Strokes;
            Assert.Equal(3, strokes.Count);

            Press(window, Key.F9);
            Assert.Equal(3, strokes.Count); // single press waits for a possible second press
            Wait(TimeSpan.FromMilliseconds(500));
            Assert.Equal(2, strokes.Count);

            Press(window, Key.F9); Press(window, Key.LeftCtrl); Press(window, Key.F9);
            Assert.Equal(3, strokes.Count); // undo + redo within the window

            Press(window, Key.F9); Press(window, Key.F9, isRepeat: true);
            Assert.Single(strokes); // holding the key keeps undoing

            Press(window, Key.F9); Press(window, Key.Escape);
            Assert.Empty(strokes); // other input applies the pending undo first

            canvas.Redo(); canvas.Redo();
            model.UndoCommand.Execute(null);
            Assert.Single(strokes); // the ribbon undo button stays immediate
            window.Close(); window = null;
        }
        finally { window?.Close(); try { Directory.Delete(root, true); } catch (IOException) { } }
    });

    private static void DrawShape(NoteCanvas canvas, double x)
    {
        void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.Invoke(canvas, args);
        Call("BeginDirectPointer", new Point(x, 50), false);
        Call("ContinuePointer", new Point(x + 80, 110), .5f, 0f, 0f, false);
        Call("EndPointer", false);
    }

    private static void Press(Window window, Key key, bool isRepeat = false)
    {
        var args = new KeyEventArgs(Keyboard.PrimaryDevice, PresentationSource.FromVisual(window)!, 0, key) { RoutedEvent = Keyboard.PreviewKeyDownEvent };
        if (isRepeat) typeof(KeyEventArgs).GetMethod("SetRepeat", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.Invoke(args, [true]);
        window.RaiseEvent(args);
    }

    private static void Wait(TimeSpan duration)
    {
        var frame = new DispatcherFrame();
        var deadline = new DispatcherTimer { Interval = duration };
        deadline.Tick += (_, _) => { deadline.Stop(); frame.Continue = false; };
        deadline.Start(); Dispatcher.PushFrame(frame);
    }

    [Fact]
    public void PenTapFeedbackIsDisabledForEveryElement() => WpfTestExecutor.Run(() =>
    {
        PenFeedback.Install(); PenFeedback.Install();
        var item = new ComboBoxItem(); var popup = new Popup { Child = new Border { Child = item } };
        var window = new Window { Content = new Grid { Children = { popup } }, ShowInTaskbar = false, WindowStyle = WindowStyle.None, Width = 10, Height = 10 };
        var menu = new ContextMenu { Items = { new MenuItem { Header = "x" } } };
        try
        {
            window.Show(); window.UpdateLayout();
            Assert.False(Stylus.GetIsTapFeedbackEnabled(window));
            Assert.False(Stylus.GetIsTapFeedbackEnabled(item));
            Assert.True(Stylus.GetIsPressAndHoldEnabled(item));
            menu.PlacementTarget = window; menu.IsOpen = true;
            Assert.False(Stylus.GetIsTapFeedbackEnabled((MenuItem)menu.Items[0]!));
        }
        finally { menu.IsOpen = false; window.Close(); }
    });

    [Fact]
    public void ToolFlyoutStaysAnchoredToTheRebuiltToolButtonWhenPenStyleChanges() => WpfTestExecutor.Run(() =>
    {
        foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
            Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-flyout-anchor", "fixture-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        MainWindow? window = null;
        try
        {
            var prefs = new PreferencesStore(Path.Combine(root, "preferences.json")); var model = new MainViewModel(root, prefs); model.AddPage("fixture", new PageAppearance()); model.SaveAll();
            window = new MainWindow(model); window.Show(); Idle(window);
            var pen = model.PenTools.First(); model.SelectToolFromToolbar(pen.Id, false); Idle(window);
            var button = RibbonButton(window, pen.Id);
            button.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent, button));
            var popup = (Popup)window.FindName("ToolFlyoutPopup");
            Assert.True(model.IsToolFlyoutOpen);
            // Selecting rebuilt the toolbar collections, so the clicked button is no longer in the tree.
            Assert.Null(PresentationSource.FromVisual(button));
            Idle(window);
            AssertAnchoredTo(popup, pen.Id);

            var selector = (ComboBox)window.FindName("PenStyleSelector");
            selector.SelectedValue = "onenote"; Idle(window);
            Assert.Equal("onenote", model.SelectedTool.PenStyleId);
            AssertAnchoredTo(popup, pen.Id);
            selector.SelectedValue = "notein"; Idle(window);
            AssertAnchoredTo(popup, pen.Id);
            window.Close(); window = null;
        }
        finally { window?.Close(); try { Directory.Delete(root, true); } catch (IOException) { } }
    });

    private static void AssertAnchoredTo(Popup popup, string toolId)
    {
        var target = Assert.IsAssignableFrom<FrameworkElement>(popup.PlacementTarget);
        Assert.NotNull(PresentationSource.FromVisual(target));
        Assert.True(target.IsVisible);
        Assert.Equal(toolId, Assert.IsType<ToolPreset>(target.DataContext).Id);
    }

    private static ToggleButton RibbonButton(DependencyObject root, string toolId)
    {
        foreach (var button in Descendants<ToggleButton>(root))
            if (button.IsVisible && button.DataContext is ToolPreset tool && tool.Id == toolId) return button;
        throw new InvalidOperationException("ribbon tool button not found");
    }

    private static IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is T match) yield return match;
            foreach (var nested in Descendants<T>(child)) yield return nested;
        }
    }

    private static void Idle(Window window) { window.UpdateLayout(); window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle); }
}
