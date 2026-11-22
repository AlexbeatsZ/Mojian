using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win;

/// <summary>Sync conflict review (docs/design/native-sync.md "Conflict review"): navigation group, review bar, canvas preview.</summary>
public partial class MainWindow
{
    private (string Path, long Length, DateTime Written)? _conflictRecordStamp;
    private readonly Dictionary<NoteCanvas, string> _conflictPreviewKeys = new();
    /// <summary>Asks before taking the server's version would drop this device's content.</summary>
    internal Func<string, bool> ConfirmSyncChoice { get; set; } = _ => true;
    internal Task ConflictPreviewsReady { get; private set; } = Task.CompletedTask;
    private DispatcherTimer? _conflictNoticeTimer;

    private void ShowConflictNotice(string message)
    {
        ViewModel.SyncConflictNotice = message;
        _conflictNoticeTimer ??= new DispatcherTimer(TimeSpan.FromSeconds(4), DispatcherPriority.Normal, (_, _) =>
        {
            _conflictNoticeTimer!.Stop();
            ViewModel.SyncConflictNotice = string.Empty;
        }, Dispatcher);
        _conflictNoticeTimer.Stop();
        _conflictNoticeTimer.Start();
    }

    private void InitializeSyncConflictReview()
    {
        ConfirmSyncChoice = message => MessageBox.Show(this, message, "Mojian", MessageBoxButton.OKCancel, MessageBoxImage.None, MessageBoxResult.Cancel) == MessageBoxResult.OK;
        ViewModel.SyncConflictsChanged += RefreshConflictPreviews;
        ViewModel.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is nameof(MainViewModel.PrimaryDocument) or nameof(MainViewModel.SecondaryDocument)) RefreshConflictPreviews();
        };
        ViewModel.SyncConflictDialogRequested += ShowSyncConflictDialog;
        ViewModel.SyncConflictResolutionRequested += (row, choice) => _ = ResolveSyncConflictAsync(row, choice);
        ViewModel.SyncConflictsRevealRequested += () => SectionScroll.ScrollToTop();
    }

    /// <summary>Reads the conflict list once per change of the conflict record (it parses both copies of every file).</summary>
    private async Task RefreshSyncConflictsAsync(NativeSyncClient client, WorkspaceStore store)
    {
        var record = client.Snapshot.ConflictDirectory is { } folder ? Path.Combine(folder, "conflicts.json") : null;
        (string, long, DateTime)? stamp = record != null && File.Exists(record) ? (record, new FileInfo(record).Length, File.GetLastWriteTimeUtc(record)) : null;
        if (stamp == _conflictRecordStamp) return;
        var items = stamp == null ? [] : await Task.Run(client.ConflictReview);
        if (!ReferenceEquals(store, ViewModel.Store)) return;
        _conflictRecordStamp = stamp;
        ViewModel.SetSyncConflicts(items);
    }

    private void ClearSyncConflicts()
    {
        _conflictRecordStamp = null;
        ViewModel.SetSyncConflicts([]);
    }

    private void RefreshConflictPreviews()
    {
        var pending = new List<Task>();
        foreach (var (canvas, document) in new[] { (LeftCanvas, ViewModel.PrimaryDocument), (RightCanvas, ViewModel.SecondaryDocument) })
        {
            var row = ViewModel.SyncConflictFor(document);
            if (row == null || document == null)
            {
                _conflictPreviewKeys.Remove(canvas);
                canvas.ConflictPreview = null;
                continue;
            }
            var item = row.Item;
            var key = string.Join("|", item.Path, FileKey(item.LocalFile), FileKey(item.RemoteFile));
            if (_conflictPreviewKeys.GetValueOrDefault(canvas) == key && canvas.ConflictPreview?.Matches(document) == true) continue;
            _conflictPreviewKeys[canvas] = key;
            var (notebookId, pageId) = (document.NotebookId, document.PageId);
            // Lock the page at once; the colored strokes follow when both copies are parsed.
            canvas.ConflictPreview = new SyncConflictPreview(notebookId, pageId, StrokeDiff.Empty);
            pending.Add(Task.Run(() => SyncConflictReview.StrokeDiff(SyncConflictReview.ReadObject(item.LocalFile),
                    SyncConflictReview.ReadObject(item.RemoteFile), WorkspaceStore.PageSerializerOptions))
                .ContinueWith(task =>
                {
                    if (_conflictPreviewKeys.GetValueOrDefault(canvas) != key) return;
                    if (task.IsCompletedSuccessfully) canvas.ConflictPreview = new SyncConflictPreview(notebookId, pageId, task.Result);
                }, TaskScheduler.FromCurrentSynchronizationContext()));
        }
        if (pending.Count > 0) ConflictPreviewsReady = Task.WhenAll(pending);
    }

    private static string FileKey(string? path) =>
        path != null && File.Exists(path) ? $"{new FileInfo(path).Length}:{File.GetLastWriteTimeUtc(path).Ticks}" : "-";

    internal async Task ResolveSyncConflictAsync(SyncConflictRow row, NativeSyncChoice choice)
    {
        if (ViewModel.SyncConflictNeedsLibraryFirst(row, choice)) { ShowConflictNotice("先处理笔记目录"); return; }
        var discarded = row.Item.LocalOnly ?? 0;
        if (choice == NativeSyncChoice.Remote && (discarded > 0 || row.Mark == ConflictMark.Delete) &&
            !ConfirmSyncChoice(row.Mark == ConflictMark.Delete ? $"删除「{row.Label}」？" : $"移除本机独有的 {discarded} 项？")) return;
        if (_nativeSyncClient is not { } client) { ShowConflictNotice("同步未连接"); return; }
        if (_nativeSyncRunning) { ShowConflictNotice("同步进行中，稍后再试"); return; }
        if (!ViewModel.CanApplyNativeSync) { ShowConflictNotice("正在保存，稍后再试"); return; }
        var store = ViewModel.Store;
        _nativeSyncRunning = true;
        ViewModel.IsResolvingSyncConflict = true;
        try
        {
            var choices = new Dictionary<string, NativeSyncChoice>(StringComparer.Ordinal) { [row.Path] = choice };
            var applied = await Task.Run(() => client.ResolveConflictsAsync(choices, action => Dispatcher.InvokeAsync(() =>
                ReferenceEquals(store, ViewModel.Store) && ViewModel.TryApplyNativeSync(action, () => ReferenceEquals(store, ViewModel.Store) && !_pageMetadataSavePending)).Task,
                _nativeSyncLifetime.Token));
            if (ReferenceEquals(store, ViewModel.Store))
            {
                ViewModel.NativeSyncStatus = NativeSyncDisplayStatus(client.Snapshot);
                if (!applied) ShowConflictNotice("已选择，等待空闲时写入");
            }
            await RefreshSyncConflictsAsync(client, store);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (ReferenceEquals(store, ViewModel.Store)) ShowConflictNotice(error.Message);
        }
        finally
        {
            _nativeSyncRunning = false;
            ViewModel.IsResolvingSyncConflict = false;
        }
        if (_nativeSyncLifetime.IsCancellationRequested) ResetNativeSyncClient();
        else _ = RunNativeSync();
    }

    /// <summary>Library, assets and pages that cannot be opened here: a short list of both sides' changes.</summary>
    private void ShowSyncConflictDialog(SyncConflictRow row)
    {
        var window = BuildSyncConflictDialog(row);
        window.Owner = this;
        window.ShowDialog();
    }

    internal Window BuildSyncConflictDialog(SyncConflictRow row)
    {
        var changes = row.Path == SyncConflictReview.Library &&
            SyncConflictReview.ReadObject(row.Item.LocalFile) is { } local && SyncConflictReview.ReadObject(row.Item.RemoteFile) is { } remote
            ? SyncConflictReview.LibraryChanges(local, remote) : [];
        var secondary = (Brush)FindResource("TextSecondary");
        var primary = (Brush)FindResource("TextPrimary");
        var panel = new StackPanel();
        var title = new TextBlock { FontSize = 16, FontWeight = FontWeights.SemiBold, Foreground = primary, Margin = new Thickness(0, 0, 0, 6) };
        title.Inlines.Add(new System.Windows.Documents.Run(row.Letter + "  ") { Foreground = row.MarkBrush });
        title.Inlines.Add(new System.Windows.Documents.Run(row.Label));
        panel.Children.Add(title);
        if (row.Summary.Length > 0) panel.Children.Add(new TextBlock { Text = row.Summary, Foreground = secondary, FontSize = 12, Margin = new Thickness(0, 0, 0, 8) });
        var list = new StackPanel();
        foreach (var change in changes)
            list.Children.Add(new TextBlock
            {
                Text = (change.Side switch { LibraryChangeSide.Local => "本机：", LibraryChangeSide.Remote => "服务器：", _ => "改动：" }) + change.Label,
                Foreground = ConflictBrushes.For(change.Side switch { LibraryChangeSide.Local => ConflictMark.Delete, LibraryChangeSide.Remote => ConflictMark.Add, _ => ConflictMark.Modify }),
                FontSize = 12.5, Margin = new Thickness(0, 3, 0, 3), TextTrimming = TextTrimming.CharacterEllipsis,
            });
        if (changes.Count > 0) panel.Children.Add(new ScrollViewer { Content = list, MaxHeight = 320, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        var dialog = new Window
        {
            Title = "同步冲突", Width = 460, SizeToContent = SizeToContent.Height, ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ShowInTaskbar = false,
            Background = (Brush)FindResource("WindowBackground"),
        };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
        void Add(string text, NativeSyncChoice choice)
        {
            var button = new Button { Content = text, Style = (Style)FindResource("ConflictReviewButtonStyle"), Tag = choice };
            button.Click += (_, _) => { dialog.Close(); ViewModel.RequestSyncConflictResolution(row, choice); };
            buttons.Children.Add(button);
        }
        Add("保留本机", NativeSyncChoice.Local);
        Add("采用服务器", NativeSyncChoice.Remote);
        if (row.CanKeepBoth) Add("两边都要", NativeSyncChoice.Both);
        panel.Children.Add(buttons);
        dialog.Content = new Border { Padding = new Thickness(20, 16, 20, 14), Background = dialog.Background, Child = panel };
        return dialog;
    }
}
