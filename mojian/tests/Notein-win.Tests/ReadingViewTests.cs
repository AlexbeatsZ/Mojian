using System.Reflection;
using System.Windows;
using System.Windows.Input;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public sealed class ReadingViewTests
{
    private static void RunSta(Action action)
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() => { try { action(); } catch (Exception e) { failure = e; } });
        if (failure != null) throw failure;
    }

    private static StrokeRecord Line(string id, float y) =>
        new(id, DrawingToolKind.Pen, InkPalette.Black, .4f, points: [new InkPoint(20, y, .5f), new InkPoint(120, y + 4, .5f)]);

    [Fact]
    public void PolicyPansFingerPenAndLeftMouseWhileBlockingRightButtonSelectionGestures()
    {
        Assert.Equal(ReadingViewPointerIntent.Pan, DirectInputPolicy.ReadingViewPointerIntent(DirectInputDevice.Finger, null));
        Assert.Equal(ReadingViewPointerIntent.Pan, DirectInputPolicy.ReadingViewPointerIntent(DirectInputDevice.Pen, MouseButton.Right));
        Assert.Equal(ReadingViewPointerIntent.Pan, DirectInputPolicy.ReadingViewPointerIntent(DirectInputDevice.Mouse, MouseButton.Left));
        Assert.Equal(ReadingViewPointerIntent.PassThrough, DirectInputPolicy.ReadingViewPointerIntent(DirectInputDevice.Mouse, MouseButton.Middle));
        Assert.Equal(ReadingViewPointerIntent.Block, DirectInputPolicy.ReadingViewPointerIntent(DirectInputDevice.Mouse, MouseButton.Right));
    }

    [Fact]
    public void EnteringReadingViewClearsSelectionAndDragsOnlyPanTheViewport()
    {
        RunSta(() =>
        {
            var document = new PageDocument("n", "p");
            document.Strokes.Add(Line("ink", 60));
            document.TextItems.Add(new TextItem { X = 30, Y = 120, Text = "t" });
            var canvas = new NoteCanvas
            {
                Width = 400,
                Height = 300,
                Document = document,
                SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen),
                PanoramaSensitivity = 2,
            };
            void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
            bool HasSelection() => (bool)typeof(NoteCanvas).GetMethod("HasContentSelection", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, null)!;
            System.Collections.ICollection Undo() => (System.Collections.ICollection)typeof(NoteCanvas).GetField("_undo", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;

            Assert.True(canvas.SelectAllContent());
            Assert.True(HasSelection());
            canvas.ReadingViewEnabled = true;
            Assert.False(HasSelection());
            Assert.False(canvas.SelectAllContent());

            foreach (var isStylus in new[] { false, true })
            {
                var startX = canvas.Viewport.TranslationX;
                var startY = canvas.Viewport.TranslationY;
                // Starting on the stroke must not select or move it: reading view pans 1:1.
                Call("BeginReadingPan", new Point(60, 60), isStylus);
                Call("ContinuePointer", new Point(80, 30), .5f, 0f, 0f, isStylus);
                Call("ContinuePointer", new Point(100, 10), .5f, 0f, 0f, isStylus);
                Call("EndPointer", isStylus);
                Assert.Equal(startX + 40, canvas.Viewport.TranslationX, 3);
                Assert.Equal(startY - 50, canvas.Viewport.TranslationY, 3);
            }

            Assert.False(HasSelection());
            Assert.Empty(Undo());
            Assert.Equal(20, document.Strokes[0].Points[0].X);
            Assert.Equal(60, document.Strokes[0].Points[0].Y);
            Assert.Equal(120, document.TextItems[0].Y);
        });
    }

    [Fact]
    public void RightMouseButtonNeverArmsTheMenuOrLassoInReadingView()
    {
        RunSta(() =>
        {
            var canvas = new NoteCanvas { Width = 400, Height = 300, Document = new PageDocument("n", "p"), ReadingViewEnabled = true };
            var down = new MouseButtonEventArgs(Mouse.PrimaryDevice, 0, MouseButton.Right) { RoutedEvent = Mouse.MouseDownEvent };
            canvas.RaiseEvent(down);
            Assert.True(down.Handled);
            Assert.False((bool)typeof(NoteCanvas).GetField("_rightLassoArmed", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!);
        });
    }
}
