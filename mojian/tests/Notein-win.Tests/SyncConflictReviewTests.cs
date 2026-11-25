using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class SyncConflictReviewTests
{
    [Fact]
    public void PathsLabelsAndSummaryFollowTheSharedContract()
    {
        Assert.Equal(("nb", "p1"), SyncConflictReview.PageOf("notebooks/nb/pages/p1.json"));
        Assert.Equal(("nb", "p1"), SyncConflictReview.PageOf("notebooks/nb/assets/p1/scan.png"));
        Assert.Null(SyncConflictReview.PageOf("library.json"));
        Assert.True(SyncConflictReview.IsPageFile("notebooks/nb/pages/p1.json"));
        Assert.False(SyncConflictReview.IsPageFile("notebooks/nb/assets/p1/scan.png"));
        Assert.Equal("笔记目录", SyncConflictReview.Label("library.json", (_, _) => null));
        Assert.Equal("作业 4", SyncConflictReview.Label("notebooks/nb/pages/p1.json", (_, _) => "作业 4"));
        Assert.Equal("p1", SyncConflictReview.Label("notebooks/nb/pages/p1.json", (_, _) => null));
        Assert.Equal("scan.png", SyncConflictReview.Label("notebooks/nb/assets/p1/scan.png", (_, _) => "作业 4"));
        Assert.Equal("本机 +1 · 服务器 +2 · 改动 1", SyncConflictReview.SummaryText(1, 2, 1));
        Assert.Equal("服务器 +3", SyncConflictReview.SummaryText(0, 3, null));
        Assert.Equal(ConflictMark.Add, SyncConflictReview.MarkOf("add"));
        Assert.Equal(ConflictMark.Delete, SyncConflictReview.MarkOf("delete"));
        Assert.Equal(ConflictMark.Modify, SyncConflictReview.MarkOf("modify"));
    }

    [Fact]
    public void StrokeDiffSeparatesServerOnlyLocalOnlyAndChangedStrokes()
    {
        var local = JsonNode.Parse("""{"strokes":[{"id":"a","widthMm":1},{"id":"b","widthMm":1},{"id":"c","widthMm":1}]}""")!.AsObject();
        var remote = JsonNode.Parse("""{"strokes":[{"id":"a","widthMm":1.0},{"id":"b","widthMm":2},{"id":"d","widthMm":1},{"id":"e","widthMm":1}]}""")!.AsObject();
        var diff = SyncConflictReview.StrokeDiff(local, remote, WorkspaceStore.PageSerializerOptions);
        Assert.Equal(new[] { "d", "e" }, diff.Remote.Select(stroke => stroke.Id));
        Assert.Equal(new[] { "c" }, diff.Local.Select(stroke => stroke.Id));
        Assert.Equal(new[] { "b" }, diff.Changed.Select(stroke => stroke.Id));
        Assert.Equal(1, diff.Changed[0].WidthMm);
        var removed = SyncConflictReview.StrokeDiff(local, null, WorkspaceStore.PageSerializerOptions);
        Assert.Equal(3, removed.Local.Count);
    }

    [Fact]
    public void LibraryChangesListPagesAndSectionsOnEachSide()
    {
        var local = JsonNode.Parse("""
            {"notebooks":[{"id":"n","title":"数学","sections":[{"id":"s","title":"作业","children":[],"pages":[
              {"id":"p1","title":"作业 4"},{"id":"p2","title":"旧草稿"}]}]}]}
            """)!.AsObject();
        var remote = JsonNode.Parse("""
            {"notebooks":[{"id":"n","title":"数学","sections":[{"id":"s","title":"作业","children":[],"pages":[
              {"id":"p1","title":"作业四"}]},{"id":"r","title":"复习","children":[],"pages":[]}]}]}
            """)!.AsObject();
        var changes = SyncConflictReview.LibraryChanges(local, remote);
        Assert.Contains(new LibraryChange(LibraryChangeSide.Local, "数学 / 作业 / 旧草稿"), changes);
        Assert.Contains(new LibraryChange(LibraryChangeSide.Changed, "数学 / 作业 / 作业 4"), changes);
        Assert.Contains(new LibraryChange(LibraryChangeSide.Remote, "数学 / 复习"), changes);
        Assert.Equal(3, changes.Count);
    }

    [Fact]
    public void ConflictedPagesAreMarkedReviewedReadOnlyAndRendered()
    {
        WpfTestExecutor.Run(() =>
        {
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(
                    new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-conflict-review-ui", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            MainWindow? window = null;
            Window? dialog = null;
            try
            {
                var model = new MainViewModel(Path.Combine(root, "workspace"), new PreferencesStore(Path.Combine(root, "preferences.json")));
                var homework = AddPageWithStrokes(model, "作业 4", Waves(4, 0));
                var homework5 = AddPageWithStrokes(model, "作业 5", Waves(2, 0));
                var draft = AddPageWithStrokes(model, "旧草稿", Waves(2, 1));
                AddPageWithStrokes(model, "作业 6", []);
                var notebookId = model.ActiveWorkspace.Id;
                string PagePath(NotePage page) => $"notebooks/{notebookId}/pages/{page.Id}.json";

                // The engine's conflict folder: local/<path> and remote/<path> copies.
                var folder = Path.Combine(root, "conflict");
                string Copy(string side, string path, string text)
                {
                    var file = Path.Combine(folder, side, path.Replace('/', Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(file)!);
                    File.WriteAllText(file, text);
                    return file;
                }
                var localPage = JsonNode.Parse(File.ReadAllText(model.Store.GetPageDocumentPath(notebookId, homework.Id)))!.AsObject();
                var remotePage = localPage.DeepClone().AsObject();
                var strokes = remotePage["strokes"]!.AsArray();
                strokes.RemoveAt(1);                                         // only here: red
                strokes[2]!["widthMm"] = 1.4;                                // changed on both: yellow
                foreach (var added in Waves(2, 4))                           // only on the server: green
                    strokes.Add(JsonSerializer(added));
                var localLibrary = JsonNode.Parse(File.ReadAllText(model.Store.LibraryPath))!.AsObject();
                var remoteLibrary = localLibrary.DeepClone().AsObject();
                var sections = remoteLibrary["notebooks"]!.AsArray().First(notebook => (string?)notebook!["id"] == notebookId)!["sections"]!.AsArray();
                var pages = sections.Select(section => section!["pages"]!.AsArray()).First(list => list.Any(page => (string?)page!["id"] == draft.Id));
                pages.Remove(pages.First(page => (string?)page!["id"] == draft.Id));
                sections.Add(JsonNode.Parse("""{"id":"section-review","title":"复习","children":[],"pages":[]}"""));

                NativeSyncConflictItem Item(string path, string? local, string? remote)
                {
                    var l = local == null ? null : Copy("local", path, local);
                    var r = remote == null ? null : Copy("remote", path, remote);
                    var summary = l != null && r != null && path.EndsWith(".json", StringComparison.Ordinal)
                        ? NativeSyncMerge.Summarize(JsonNode.Parse(local!)!.AsObject(), JsonNode.Parse(remote!)!.AsObject()) : null;
                    return new(path, l == null ? "add" : r == null ? "delete" : "modify", l, r, summary?.LocalOnly, summary?.RemoteOnly, summary?.Changed);
                }
                var items = new[]
                {
                    Item(PagePath(homework), localPage.ToJsonString(), remotePage.ToJsonString()),
                    Item(PagePath(draft), File.ReadAllText(model.Store.GetPageDocumentPath(notebookId, draft.Id)), null),
                    Item($"notebooks/{notebookId}/assets/{homework5.Id}/scan.png", null, "png"),
                    Item("library.json", localLibrary.ToJsonString(), remoteLibrary.ToJsonString()),
                };
                model.SetSyncConflicts(items);

                Assert.Equal(new[] { "笔记目录", "scan.png", "作业 4", "旧草稿" }, model.SyncConflictRows.Select(row => row.Label));
                Assert.Equal(new[] { "M", "A", "M", "D" }, model.SyncConflictRows.Select(row => row.Letter));
                var pageMarks = model.PagePaneRows.Where(row => row.Page != null).ToDictionary(row => row.Page!.Title, row => row.ConflictLetter);
                Assert.Equal("M", pageMarks["作业 4"]);
                Assert.Equal("M", pageMarks["作业 5"]);
                Assert.Equal("D", pageMarks["旧草稿"]);
                Assert.Equal(string.Empty, pageMarks["作业 6"]);

                window = new MainWindow(model) { Width = 1280, Height = 820 };
                window.ConfirmSyncChoice = _ => true;
                var requested = new List<(string Path, NativeSyncChoice Choice)>();
                model.SyncConflictResolutionRequested += (row, choice) => requested.Add((row.Path, choice));
                window.Show();
                Idle(window);
                Assert.Null(model.ActiveSyncConflict);
                Assert.Equal(Visibility.Collapsed, ((FrameworkElement)window.FindName("SyncConflictBar")).Visibility);
                Assert.Equal(Visibility.Visible, ((FrameworkElement)window.FindName("SyncConflictChip")).Visibility);
                Assert.False(((FrameworkElement)window.FindName("SyncConflictChip")).IsHitTestVisible);

                model.OpenSyncConflictCommand.Execute(model.SyncConflictRows.Single(row => row.Label == "作业 4"));
                Wait(window.ConflictPreviewsReady);
                Idle(window);
                var canvas = (NoteCanvas)window.FindName("LeftCanvas");
                Assert.Equal(homework.Id, model.FocusedDocument?.PageId);
                Assert.Equal("本机 +1 · 服务器 +2 · 改动 1", model.ActiveSyncConflict?.Summary);
                Assert.True(canvas.IsReviewLocked);
                Assert.Equal((2, 1, 1), (canvas.ConflictPreview!.Diff.Remote.Count, canvas.ConflictPreview.Diff.Local.Count, canvas.ConflictPreview.Diff.Changed.Count));
                Assert.Equal(Visibility.Visible, ((FrameworkElement)window.FindName("SyncConflictBar")).Visibility);
                Assert.Equal(Visibility.Visible, ((FrameworkElement)window.FindName("ConflictKeepBothButton")).Visibility);
                var strokeCount = canvas.Document!.Strokes.Count;
                canvas.ClearPage();
                canvas.Undo();
                Assert.Equal(strokeCount, canvas.Document.Strokes.Count);
                Assert.False(canvas.Document.HasPendingContentChanges);
                Assert.Throws<InvalidOperationException>(() => model.WriteAsset(".png", [1, 2, 3]));
                var appearance = System.Text.Json.JsonSerializer.Serialize(model.ActiveWorkspace.SelectedPage()!.Appearance);
                model.TogglePageTitle();
                model.SetPaperKind(PaperKind.A4);
                model.SetBackgroundFormat(CanvasBackgroundFormat.GridStandard);
                Assert.Equal(appearance, System.Text.Json.JsonSerializer.Serialize(model.ActiveWorkspace.SelectedPage()!.Appearance));
                var (pixels, width) = Render(window, "conflict-review-page.png");
                var area = canvas.TransformToAncestor((Visual)window.Content).TransformBounds(new Rect(0, 60, canvas.ActualWidth, canvas.ActualHeight - 60));
                Assert.True(CountNear(pixels, width, area, NoteCanvas.ConflictGreen) > 50, "server-only strokes are not drawn green");
                Assert.True(CountNear(pixels, width, area, NoteCanvas.ConflictRed) > 50, "local-only strokes are not drawn red");
                Assert.True(CountNear(pixels, width, area, NoteCanvas.ConflictYellow) > 50, "strokes changed on both sides are not drawn yellow");

                ((Button)window.FindName("ConflictKeepBothButton")).Command.Execute("Both");
                Assert.Equal((PagePath(homework), NativeSyncChoice.Both), requested.Single());

                // A deleted page that the local library still lists waits for the library conflict.
                window.ResolveSyncConflictAsync(model.SyncConflictRows.Single(row => row.Label == "旧草稿"), NativeSyncChoice.Remote).GetAwaiter().GetResult();
                Assert.Equal("先处理笔记目录", model.SyncConflictNotice);
                Idle(window);
                Assert.Equal(Visibility.Visible, ((FrameworkElement)window.FindName("SyncConflictNotice")).Visibility);

                var libraryRow = model.SyncConflictRows.Single(row => row.Path == "library.json");
                dialog = window.BuildSyncConflictDialog(libraryRow);
                dialog.Owner = window;
                dialog.Show();
                Idle(dialog);
                var lines = Descendants(dialog).OfType<TextBlock>().Select(text => text.Text).ToList();
                Assert.Contains(lines, line => line.StartsWith("本机：", StringComparison.Ordinal) && line.EndsWith("旧草稿", StringComparison.Ordinal));
                Assert.Contains(lines, line => line.StartsWith("服务器：", StringComparison.Ordinal) && line.EndsWith("复习", StringComparison.Ordinal));
                Assert.Equal(3, Descendants(dialog).OfType<Button>().Count());
                Render(dialog, "conflict-review-library.png");
                dialog.Close();
                dialog = null;

                model.SetSyncConflicts([]);
                Idle(window);
                Assert.False(canvas.IsReviewLocked);
                Assert.Null(canvas.ConflictPreview);
                Assert.Equal(Visibility.Collapsed, ((FrameworkElement)window.FindName("SyncConflictBar")).Visibility);
                Assert.Equal(Visibility.Collapsed, ((FrameworkElement)window.FindName("SyncConflictChip")).Visibility);
                Assert.All(model.PagePaneRows, row => Assert.False(row.HasConflict));
            }
            finally
            {
                dialog?.Close();
                window?.Close();
                Directory.Delete(root, true);
            }
        });
    }

    private static NotePage AddPageWithStrokes(MainViewModel model, string title, IReadOnlyList<StrokeRecord> strokes)
    {
        var page = model.AddPage(title, new PageAppearance())!;
        var document = model.FocusedDocument!;
        document.Strokes.AddRange(strokes);
        document.HasPendingContentChanges = strokes.Count > 0;
        model.SaveAll();
        return page;
    }

    private static JsonNode JsonSerializer(StrokeRecord stroke) =>
        System.Text.Json.JsonSerializer.SerializeToNode(stroke, WorkspaceStore.PageSerializerOptions)!;

    /// <summary>Handwriting-like loops, one per row.</summary>
    private static List<StrokeRecord> Waves(int count, int firstRow) => Enumerable.Range(firstRow, count).Select(row =>
    {
        var points = Enumerable.Range(0, 90).Select(index =>
        {
            var t = index / 89d;
            return new InkPoint
            {
                X = (float)(110 + t * 440 + 14 * Math.Cos(t * Math.PI * 14)),
                Y = (float)(150 + row * 95 + 16 * Math.Sin(t * Math.PI * 14)),
                Pressure = (float)(0.45 + 0.3 * Math.Sin(t * Math.PI)),
            };
        }).ToList();
        return new StrokeRecord { Id = $"stroke-{row}", WidthMm = 0.7f, Points = points };
    }).ToList();

    private static void Idle(DispatcherObject owner) => owner.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);

    private static void Wait(Task task)
    {
        var frame = new DispatcherFrame();
        task.ContinueWith(_ => frame.Continue = false, TaskScheduler.Default);
        Dispatcher.PushFrame(frame);
        task.GetAwaiter().GetResult();
    }

    private static (byte[] Pixels, int Width) Render(Window window, string name)
    {
        window.UpdateLayout();
        var content = (FrameworkElement)window.Content;
        var (width, height) = ((int)Math.Ceiling(content.ActualWidth), (int)Math.Ceiling(content.ActualHeight));
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        var background = new DrawingVisual();
        using (var context = background.RenderOpen()) context.DrawRectangle(window.Background ?? Brushes.Black, null, new Rect(0, 0, width, height));
        bitmap.Render(background);
        bitmap.Render(content);
        var output = Environment.GetEnvironmentVariable("MOJIAN_CONFLICT_QA_DIR") ?? Path.Combine(Path.GetTempPath(), ".agents", "mojian-conflict-review-ui", "qa");
        Directory.CreateDirectory(output);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using (var stream = File.Create(Path.Combine(output, name))) encoder.Save(stream);
        var pixels = new byte[width * height * 4];
        bitmap.CopyPixels(pixels, width * 4, 0);
        return (pixels, width);
    }

    /// <summary>Pixels inside <paramref name="area"/> close to the overlay color composited over dark paper.</summary>
    private static int CountNear(byte[] bgra, int width, Rect area, Color color)
    {
        var count = 0;
        var height = bgra.Length / 4 / width;
        for (var y = Math.Max(0, (int)area.Top); y < Math.Min(height, (int)area.Bottom); y++)
        for (var x = Math.Max(0, (int)area.Left); x < Math.Min(width, (int)area.Right); x++)
        {
            var i = (y * width + x) * 4;
            if (Math.Abs(bgra[i + 2] - color.R) < 40 && Math.Abs(bgra[i + 1] - color.G) < 40 && Math.Abs(bgra[i] - color.B) < 40) count++;
        }
        return count;
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        yield return root;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            foreach (var child in Descendants(VisualTreeHelper.GetChild(root, index))) yield return child;
    }
}
