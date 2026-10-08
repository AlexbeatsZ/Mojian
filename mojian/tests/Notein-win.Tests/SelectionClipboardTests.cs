using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Xml.Linq;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class SelectionClipboardTests
{
    [Fact]
    public void RealWindowCopyCommandsUseNativeSelectionAndConfiguredExportActions() => Sta(() =>
    {
        var previous = SnapshotClipboard();
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-copy-ui-" + Guid.NewGuid().ToString("N"));
        MainWindow? window = null;
        try
        {
            var vm = new MainViewModel(root, new PreferencesStore(Path.Combine(root, "preferences.json")));
            var application = Application.Current!;
            application.Resources.MergedDictionaries.Clear();
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            window = new MainWindow(vm) { Width = 1000, Height = 700 }; window.Show();
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            var canvas = (NoteCanvas)window.FindName("LeftCanvas");
            canvas.Document!.Strokes.Add(new StrokeRecord { Points = [new InkPoint(20, 30), new InkPoint(120, 60)] });
            canvas.SelectAllContent(); canvas.Focus();
            var execute = typeof(MainWindow).GetMethod("ExecuteShortcutAction", BindingFlags.Instance | BindingFlags.NonPublic)!;
            Assert.Equal("home:copy", vm.FindShortcutAction(new ShortcutGesture(System.Windows.Input.Key.C, System.Windows.Input.ModifierKeys.Control)));
            var svgChord = new ShortcutGesture(System.Windows.Input.Key.C, System.Windows.Input.ModifierKeys.Control | System.Windows.Input.ModifierKeys.Shift);
            Assert.Null(vm.FindShortcutAction(svgChord)); vm.SetShortcut("home:copy-svg", svgChord);
            Assert.Equal("home:copy-svg", vm.FindShortcutAction(svgChord));
            Assert.True((bool)execute.Invoke(window, ["home:copy"])!);
            Assert.True(NoteCanvas.HasNativeClipboard);
            Assert.True((bool)execute.Invoke(window, ["home:paste"])!); Assert.Equal(2, canvas.Document.Strokes.Count); Assert.Empty(canvas.Document.Objects);
            Assert.True((bool)execute.Invoke(window, ["home:copy-svg"])!);
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Assert.True(Clipboard.ContainsData("image/svg+xml"));
            Assert.True((bool)execute.Invoke(window, ["home:copy-png"])!);
            window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Assert.True(Clipboard.ContainsImage());
            vm.ImageDisplayMode = ImageImportDisplayMode.Original;
            Assert.True((bool)execute.Invoke(window, ["home:paste"])!);
            Assert.Single(canvas.Document.Objects); Assert.Equal(2, canvas.Document.Strokes.Count);
            canvas.SelectAllContent();
            var menuMethod = typeof(NoteCanvas).GetMethod("OpenSelectionContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)!;
            Assert.True((bool)menuMethod.Invoke(canvas, [new Point(100, 100)])!);
            System.Windows.Threading.Dispatcher.CurrentDispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            var menu = PresentationSource.CurrentSources.Cast<PresentationSource>().Where(source => source.Dispatcher.CheckAccess()).Select(source => FindMenu(source.RootVisual)).FirstOrDefault(value => value != null);
            Assert.NotNull(menu);
            var labels = menu.Items.OfType<System.Windows.Controls.MenuItem>().Select(item => item.Header?.ToString()).ToList();
            Assert.Contains("复制", labels); Assert.Contains("复制为 SVG", labels); Assert.Contains("复制为 PNG", labels);
            var output = Environment.GetEnvironmentVariable("MOJIAN_CLIPBOARD_QA_DIR");
            if (!string.IsNullOrWhiteSpace(output))
            {
                Directory.CreateDirectory(output);
                var bitmap = new System.Windows.Media.Imaging.RenderTargetBitmap((int)Math.Ceiling(menu.ActualWidth), (int)Math.Ceiling(menu.ActualHeight), 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(menu); File.WriteAllBytes(Path.Combine(output, "copy-menu.png"), SelectionImageExport.PngBytes(bitmap));
            }
            menu.IsOpen = false;
        }
        finally { window?.Close(); if (Directory.Exists(root)) Directory.Delete(root, true); if (previous != null) Clipboard.SetDataObject(previous, true); else Clipboard.Clear(); }
    });

    [Fact]
    public void NativeCopyReplacesOldBitmapAndPastesEditableIndependentContentWithUndo() => Sta(() =>
    {
        var previous = SnapshotClipboard();
        try
        {
            var stroke = new StrokeRecord { WidthMm = .7f, PressurePercent = 230, InkFlowPercent = 80,
                VectorPath = "M10,10 L80,10 L80,15 Z", VectorTransform = [1, 0, 0, 1, 0, 0],
                Points = [new InkPoint(10, 10, .23f, 1, 2, 3), new InkPoint(80, 10, .87f, 4, 5, 6), new InkPoint { X = 90, Y = 10, Pressure = 0 }] };
            var source = new NoteCanvas { Document = new PageDocument("source", "page") {
                Strokes = [stroke], TextItems = [new TextItem { Text = "墨笺", X = 20, Y = 30 }],
                Tags = [new CanvasTag { DocumentX = 20, DocumentY = 75 }] } };
            source.SelectAllContent();
            Clipboard.SetImage(SelectionImageExport.PngBitmap(new GeometryDrawing(Brushes.Red, null,
                new RectangleGeometry(new Rect(0, 0, 10, 10))), new Rect(0, 0, 10, 10)));
            Assert.True(source.CopySelection());
            Assert.True(NoteCanvas.HasNativeClipboard);
            Assert.False(Clipboard.ContainsImage()); Assert.False(Clipboard.ContainsText());
            var nativeClipboard = SnapshotClipboard();
            stroke.Points[0] = new InkPoint(999, 999);
            var target = new NoteCanvas { Document = new PageDocument("target", "page2") };
            Assert.True(target.PasteSelection());
            var copy = Assert.Single(target.Document.Strokes);
            Assert.NotEqual(stroke.Id, copy.Id);
            Assert.Equal(34f, copy.Points[0].X); Assert.Equal(.23f, copy.Points[0].Pressure);
            Assert.Equal(0f, copy.Points[2].Pressure);
            Assert.Equal(230, copy.PressurePercent); Assert.Equal(80, copy.InkFlowPercent);
            Assert.Equal(stroke.VectorPath, copy.VectorPath); Assert.Equal(24d, copy.VectorTransform[4]);
            Assert.Single(target.Document.TextItems); Assert.Single(target.Document.Tags); Assert.Empty(target.Document.Objects);
            target.Undo(); Assert.Empty(target.Document.Strokes); Assert.Empty(target.Document.TextItems);
            target.Redo(); Assert.Single(target.Document.Strokes);
            Clipboard.SetText("new external content");
            Assert.False(target.PasteSelection());
            Clipboard.SetDataObject(nativeClipboard!, true);
            Assert.False(target.PasteSelection());
        }
        finally { if (previous != null) Clipboard.SetDataObject(previous, true); else Clipboard.Clear(); }
    });

    [Fact]
    public void ExportedSvgContainsNativePathsAndPngHasTransparencyWithoutPaperOrSelectionChrome() => Sta(() =>
    {
        var previous = SnapshotClipboard();
        try
        {
            var canvas = new NoteCanvas { Document = new PageDocument("n", "p") {
                Strokes = [new StrokeRecord { WidthMm = 2, Points = [new InkPoint(10, 10, .2f), new InkPoint(90, 50, .9f)] }],
                TextItems = [new TextItem { Text = "墨笺", X = 10, Y = 70, Width = 100, Height = 40 }] },
                Appearance = new PageAppearance { Theme = PageTheme.Dark, Background = CanvasBackgroundFormat.GridStandard } };
            canvas.SelectAllContent();
            var selection = canvas.RenderSelectionDrawing()!.Value;
            var svg = XDocument.Parse(SelectionImageExport.SvgText(selection.Drawing, selection.Bounds));
            XNamespace ns = "http://www.w3.org/2000/svg";
            Assert.NotEmpty(svg.Descendants(ns + "path")); Assert.Empty(svg.Descendants(ns + "image"));
            Assert.Contains(svg.Descendants(ns + "path"), path => (string?)path.Attribute("fill") == "#EFEFEF");
            var bitmap = SelectionImageExport.PngBitmap(selection.Drawing, selection.Bounds);
            var output = Environment.GetEnvironmentVariable("MOJIAN_CLIPBOARD_QA_DIR");
            if (!string.IsNullOrWhiteSpace(output))
            {
                Directory.CreateDirectory(output);
                File.WriteAllBytes(Path.Combine(output, "selection.png"), SelectionImageExport.PngBytes(bitmap));
                File.WriteAllText(Path.Combine(output, "selection.svg"), svg.ToString());
            }
            var pixels = new byte[bitmap.PixelWidth * bitmap.PixelHeight * 4]; bitmap.CopyPixels(pixels, bitmap.PixelWidth * 4, 0);
            Assert.Equal(0, pixels[3]); Assert.Contains(Enumerable.Range(0, pixels.Length / 4), i => pixels[i * 4 + 3] > 0);
            Assert.True(canvas.CopySelection()); Assert.True(canvas.CopySelectionImage(true));
            Assert.False(NoteCanvas.HasNativeClipboard); Assert.True(Clipboard.ContainsData("image/svg+xml"));
            Assert.True(canvas.CopySelectionImage(false)); Assert.True(Clipboard.ContainsImage()); Assert.True(Clipboard.ContainsData("PNG"));
        }
        finally { if (previous != null) Clipboard.SetDataObject(previous, true); else Clipboard.Clear(); }
    });

    [Fact]
    public void CopiedAssetsSurviveSourceDeletionAndBecomeTargetOwned() => Sta(() =>
    {
        var previous = SnapshotClipboard();
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-copy-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var sourceFile = Path.Combine(root, "source.txt"); File.WriteAllText(sourceFile, "owned attachment");
            var source = new NoteCanvas { Document = new PageDocument("a", "b") { Objects = [PageObject.Asset(PageObjectKind.Attachment, 10, 20, "source.txt", "source", "text/plain", 16)] }, ResolveAssetPath = _ => sourceFile };
            source.SelectAllContent(); Assert.True(source.CopySelection(cut: true)); Assert.Empty(source.Document.Objects);
            File.Delete(sourceFile);
            var target = new NoteCanvas { Document = new PageDocument("c", "d"), ImportClipboardAsset = (_, bytes) => {
                File.WriteAllBytes(Path.Combine(root, "target.txt"), bytes); return "target.txt"; } };
            Assert.True(target.PasteSelection()); Assert.Equal("target.txt", Assert.Single(target.Document.Objects).AssetName);
            Assert.Equal("owned attachment", File.ReadAllText(Path.Combine(root, "target.txt")));
        }
        finally { Directory.Delete(root, true); if (previous != null) Clipboard.SetDataObject(previous, true); else Clipboard.Clear(); }
    });

    private static void Sta(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() => { try { _ = Application.Current ?? new Application(); action(); } catch (Exception e) { failure = e; } });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        if (failure != null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();
    }

    private static DataObject? SnapshotClipboard()
    {
        var source = Clipboard.GetDataObject();
        if (source == null) return null;
        var snapshot = new DataObject();
        foreach (var format in source.GetFormats(false))
        {
            var value = source.GetData(format, false);
            if (value is MemoryStream stream) value = new MemoryStream(stream.ToArray());
            if (value is System.Windows.Media.Imaging.BitmapSource bitmap)
            {
                var independent = bitmap.CloneCurrentValue(); independent.Freeze(); value = independent;
            }
            // Eager values must not retain a delayed OLE object owned by an earlier test's STA.
            if (value is string or byte[] or MemoryStream or System.Windows.Media.Imaging.BitmapSource or System.Collections.Specialized.StringCollection)
                snapshot.SetData(format, value, false);
        }
        return snapshot;
    }

    private static System.Windows.Controls.ContextMenu? FindMenu(DependencyObject? node)
    {
        if (node is System.Windows.Controls.ContextMenu menu) return menu;
        if (node == null) return null;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(node); index++)
        {
            var result = FindMenu(VisualTreeHelper.GetChild(node, index));
            if (result != null) return result;
        }
        return null;
    }
}
