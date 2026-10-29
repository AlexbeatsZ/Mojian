using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class SelectionMoveCopyTests
{
    private static void Call(NoteCanvas canvas, string name, params object[] args) =>
        typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
    private static T Field<T>(NoteCanvas canvas, string name) =>
        (T)typeof(NoteCanvas).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
    private static void Sta(Action action)
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() => { try { action(); } catch (Exception error) { failure = error; } });
        if (failure != null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void MixedMoveTogglesOriginalPreviewAndCommitsOnlyTheFinalModifierWithOneUndo(bool copy) => Sta(() =>
    {
        var stroke = new StrokeRecord { Points = [new InkPoint(110, 110, .2f), new InkPoint(180, 140, .8f)],
            VectorPath = "M110,110 L180,140 L175,140 Z", VectorTransform = [1, 0, 0, 1, 0, 0] };
        var text = new TextItem { X = 110, Y = 150, Width = 100, Height = 30, Text = "Copy" };
        var image = new PageObject { X = 120, Y = 210, Width = 100, Height = 50, AssetName = "same-page.png" };
        var canvas = new NoteCanvas { Document = new PageDocument("n", "p") {
            Strokes = [stroke], TextItems = [text], Objects = [image], Tags = [new CanvasTag { DocumentX = 200, DocumentY = 180 }] } };
        canvas.SelectAllContent();
        Call(canvas, "BeginDirectPointer", new Point(160, 180), false);
        Call(canvas, "ContinueDirectPointer", new Point(220, 210));
        // No pointer motion is needed to add/remove the original-position preview.
        Call(canvas, "UpdateSelectionMoveCopy", true);
        Assert.True(Field<bool>(canvas, "_moveCopyVisible"));
        Assert.Single(canvas.Document.Strokes); Assert.Single(canvas.Document.Objects);
        Call(canvas, "UpdateSelectionMoveCopy", false);
        Assert.False(Field<bool>(canvas, "_moveCopyVisible"));
        Call(canvas, "UpdateSelectionMoveCopy", true);
        Call(canvas, "ContinueDirectPointer", new Point(230, 220));
        Call(canvas, "UpdateSelectionMoveCopy", copy);
        Call(canvas, "EndDirectPointer", new Point(240, 230));
        Assert.Equal(190, stroke.Points[0].X); Assert.Equal(160, stroke.Points[0].Y);
        Assert.Equal(copy ? 2 : 1, canvas.Document.Strokes.Count);
        Assert.Equal(copy ? 2 : 1, canvas.Document.Objects.Count);
        Assert.Equal(copy ? 2 : 1, canvas.Document.TextItems.Count);
        Assert.Equal(copy ? 2 : 1, canvas.Document.Tags.Count);
        Assert.Single(Field<List<StrokeRecord>>(canvas, "_selectedStrokes"));
        if (copy)
        {
            var original = canvas.Document.Strokes.Single(item => item != stroke);
            Assert.Equal(110, original.Points[0].X); Assert.Equal(.2f, original.Points[0].Pressure);
            Assert.Equal(0, original.VectorTransform[4]); Assert.NotEqual(stroke.Id, original.Id);
            Assert.Equal("same-page.png", canvas.Document.Objects.Single(item => item != image).AssetName);
        }
        canvas.Undo();
        Assert.Single(canvas.Document.Strokes); Assert.Equal(110, canvas.Document.Strokes[0].Points[0].X);
        canvas.Redo(); Assert.Equal(copy ? 2 : 1, canvas.Document.Strokes.Count);
    });

    [Fact]
    public void CancelAndResizeDoNotLeaveCopies() => Sta(() =>
    {
        var canvas = new NoteCanvas { Document = new PageDocument("n", "p") {
            Objects = [new PageObject { X = 100, Y = 100, Width = 100, Height = 60 }] } };
        canvas.SelectAllContent();
        Call(canvas, "BeginDirectPointer", new Point(150, 130), false);
        Call(canvas, "ContinueDirectPointer", new Point(250, 180));
        Call(canvas, "UpdateSelectionMoveCopy", true);
        Call(canvas, "CancelDirectPointer");
        Assert.Equal(100, Assert.Single(canvas.Document.Objects).X);
        canvas.SelectAllContent();
        Call(canvas, "BeginDirectPointer", new Point(200, 160), false);
        Call(canvas, "ContinueDirectPointer", new Point(250, 190));
        Call(canvas, "UpdateSelectionMoveCopy", true);
        Assert.False(Field<bool>(canvas, "_moveCopyVisible"));
        Call(canvas, "EndDirectPointer", new Point(250, 190));
        Assert.Single(canvas.Document.Objects);
    });

    [Fact]
    public void RealWindowCaretPasteAndControlKeyPreviewUseCurrentCanvasCoordinates() => Sta(() =>
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-selection-" + Guid.NewGuid().ToString("N"));
        MainWindow? window = null;
        var previous = typeof(SelectionClipboardTests).GetMethod("SnapshotClipboard", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, []);
        try
        {
            var application = Application.Current ?? new Application();
            application.Resources.MergedDictionaries.Clear();
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var vm = new MainViewModel(root, new PreferencesStore(Path.Combine(root, "preferences.json")));
            window = new MainWindow(vm) { Width = 1100, Height = 780 }; window.Show();
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Assert.True(window.IsLoaded);
            var canvas = (NoteCanvas)window.FindName("LeftCanvas");
            canvas.ClearPage();
            canvas.Document!.Objects.Add(new PageObject { X = 30, Y = 30, Width = 90, Height = 60 });
            canvas.SelectAllContent(); Assert.True(canvas.CopySelection());
            canvas.Focus();
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            canvas.Viewport.SetScale(2, 0, 0); canvas.Viewport.Pan(-40, -70);
            window.UpdateLayout();
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Call(canvas, "HandleDirectTap", new Point(350, 310));
            Assert.NotNull(Field<TextItem?>(canvas, "_temporaryTextItem"));
            var anchor = canvas.PasteInsertionPoint;
            // MainWindow may restore its focused slot's viewport when the canvas takes focus.
            var actualViewportPoint = canvas.Viewport.ViewToDocument(350, 310);
            Assert.Equal(actualViewportPoint.X, anchor.X, 2); Assert.Equal(actualViewportPoint.Y, anchor.Y, 2);
            // Moving focus to the ribbon removes the temporary blank text, retaining its anchor.
            window.Focus();
            typeof(MainWindow).GetMethod("PasteHomeContent", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(window, []);
            var pasted = canvas.Document.Objects.Last();
            Assert.Equal(anchor.X, pasted.X, 3); Assert.Equal(anchor.Y, pasted.Y, 3);
            Assert.Empty(canvas.Document.TextItems);
            var down = canvas.Viewport.DocumentToView(pasted.X + 45, pasted.Y + 30);
            canvas.Focus();
            Call(canvas, "BeginDirectPointer", down, false);
            Call(canvas, "ContinueDirectPointer", down + new Vector(220, 100));
            var key = new KeyEventArgs(Keyboard.PrimaryDevice, PresentationSource.FromVisual(canvas), Environment.TickCount, Key.LeftCtrl);
            Call(canvas, "OnPreviewKeyDown", key);
            Assert.True(Field<bool>(canvas, "_moveCopyVisible"));
            window.UpdateLayout();
            var output = Environment.GetEnvironmentVariable("MOJIAN_SELECTION_QA_DIR");
            if (output != null)
            {
                Directory.CreateDirectory(output);
                var bitmap = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(window);
                File.WriteAllBytes(Path.Combine(output, "control-copy-preview.png"), SelectionImageExport.PngBytes(bitmap));
            }
            Call(canvas, "EndDirectPointer", down + new Vector(240, 120));
            Assert.Equal(3, canvas.Document.Objects.Count);
            Clipboard.SetImage(SelectionImageExport.PngBitmap(new GeometryDrawing(Brushes.Red, null,
                new RectangleGeometry(new Rect(0, 0, 10, 10))), new Rect(0, 0, 10, 10)));
            Call(canvas, "HandleDirectTap", new Point(600, 400));
            anchor = canvas.PasteInsertionPoint;
            typeof(MainWindow).GetMethod("PasteHomeContent", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(window, []);
            Assert.Equal(anchor.X, canvas.Document.Objects.Last().X, 3);
            Assert.Equal(anchor.Y, canvas.Document.Objects.Last().Y, 3);
        }
        finally
        {
            window?.Close();
            if (Directory.Exists(root)) Directory.Delete(root, true);
            typeof(SelectionClipboardTests).GetMethod("RestoreClipboard", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [previous]);
        }
    });
}
