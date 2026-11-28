using System.Collections.ObjectModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.ViewModels;

/// <summary>Sync conflict review state for navigation and the review bar (native-sync.md "Conflict review").</summary>
public sealed partial class MainViewModel
{
    private IReadOnlyList<NativeSyncConflictItem> _syncConflictItems = [];
    /// <summary>Titles seen while a conflicted page was still listed, so it keeps its name after the library drops it.</summary>
    private readonly Dictionary<string, string> _conflictPageTitles = new(StringComparer.Ordinal);
    private SyncConflictRow? _activeSyncConflict;
    private bool _isResolvingSyncConflict;
    private RelayCommand<SyncConflictRow>? _openSyncConflictCommand;
    private RelayCommand<string>? _resolveSyncConflictCommand;
    private RelayCommand? _revealSyncConflictsCommand;

    public ObservableCollection<SyncConflictRow> SyncConflictRows { get; } = new();
    public IReadOnlyList<NativeSyncConflictItem> SyncConflictItems => _syncConflictItems;
    public int SyncConflictCount => _syncConflictItems.Count;
    public bool HasSyncConflicts => _syncConflictItems.Count > 0;
    public string SyncConflictTitle => $"同步冲突 {_syncConflictItems.Count}";
    /// <summary>The conflict of the focused page, shown in the canvas review bar.</summary>
    public SyncConflictRow? ActiveSyncConflict { get => _activeSyncConflict; private set => SetField(ref _activeSyncConflict, value); }
    public bool IsResolvingSyncConflict { get => _isResolvingSyncConflict; set => SetField(ref _isResolvingSyncConflict, value); }
    private string _syncConflictNotice = string.Empty;
    /// <summary>Short transient message about a conflict choice (the sync status line is replaced by the conflict chip).</summary>
    public string SyncConflictNotice { get => _syncConflictNotice; set => SetField(ref _syncConflictNotice, value); }

    /// <summary>Raised when the conflict list changes, so canvases refresh their previews.</summary>
    public event Action? SyncConflictsChanged;
    /// <summary>A conflict without an openable page (library, assets, pages missing here) needs a list dialog.</summary>
    public event Action<SyncConflictRow>? SyncConflictDialogRequested;
    public event Action<SyncConflictRow, NativeSyncChoice>? SyncConflictResolutionRequested;
    public event Action? SyncConflictsRevealRequested;

    public ICommand OpenSyncConflictCommand => _openSyncConflictCommand ??= new RelayCommand<SyncConflictRow>(OpenSyncConflict);
    public ICommand ResolveSyncConflictCommand => _resolveSyncConflictCommand ??= new RelayCommand<string>(choice =>
    {
        if (ActiveSyncConflict is { } row && Enum.TryParse<NativeSyncChoice>(choice, out var parsed)) RequestSyncConflictResolution(row, parsed);
    });
    public ICommand RevealSyncConflictsCommand => _revealSyncConflictsCommand ??= new RelayCommand(() =>
    {
        IsSectionPaneOpen = true;
        SyncConflictsRevealRequested?.Invoke();
    });

    public void RequestSyncConflictResolution(SyncConflictRow row, NativeSyncChoice choice)
    {
        if (!IsResolvingSyncConflict) SyncConflictResolutionRequested?.Invoke(row, choice);
    }

    public void SetSyncConflicts(IReadOnlyList<NativeSyncConflictItem> items)
    {
        var changed = items.Count != _syncConflictItems.Count || !items.SequenceEqual(_syncConflictItems);
        _syncConflictItems = items;
        if (!changed) return;
        RebuildSyncConflictRows();
        RefreshPagePaneRows();
        UpdateActiveSyncConflict();
        OnPropertyChanged(nameof(SyncConflictCount));
        OnPropertyChanged(nameof(HasSyncConflicts));
        OnPropertyChanged(nameof(SyncConflictTitle));
        SyncConflictsChanged?.Invoke();
    }

    /// <summary>The page JSON conflict of a loaded document, if any.</summary>
    public SyncConflictRow? SyncConflictFor(PageDocument? document) => document == null || _syncConflictItems.Count == 0 ? null :
        ConflictRows().FirstOrDefault(row => row.IsPage && row.NotebookId == document.NotebookId && row.PageId == document.PageId);

    /// <summary>Whether taking the server's deletion of this page must wait for the library conflict (else reload restores the page from .previous).</summary>
    public bool SyncConflictNeedsLibraryFirst(SyncConflictRow row, NativeSyncChoice choice) =>
        choice == NativeSyncChoice.Remote && row.Mark == ConflictMark.Delete && row.IsPage &&
        _syncConflictItems.Any(item => item.Path == SyncConflictReview.Library) &&
        _library.Notebooks.FirstOrDefault(notebook => notebook.Id == row.NotebookId)?.FindPage(row.PageId, out _) != null;

    private IEnumerable<SyncConflictRow> ConflictRows()
    {
        var selectedPage = ActiveWorkspace.SelectedPageId;
        return _syncConflictItems
            .Select(item =>
            {
                var pageId = SyncConflictReview.PageIdOf(item);
                var notebookId = pageId == null ? null : _library.Notebooks.FirstOrDefault(notebook => notebook.FindPage(pageId, out _) != null)?.Id;
                return new SyncConflictRow(item, SyncConflictReview.Label(item.Path), notebookId, pageId);
            })
            .Select(row => row.WithSelection(row.IsPage && row.NotebookId == ActiveWorkspace.Id && row.PageId == selectedPage))
            .OrderBy(row => row.Path != SyncConflictReview.Library).ThenBy(row => row.Label, StringComparer.Ordinal);
    }

    private string? ConflictPageTitle(string notebookId, string pageId)
    {
        var key = notebookId + "/" + pageId;
        var title = _library.Notebooks.FirstOrDefault(item => item.Id == notebookId)?.FindPage(pageId, out _)?.Title;
        if (title != null) _conflictPageTitles[key] = title;
        return title ?? _conflictPageTitles.GetValueOrDefault(key);
    }

    private void RebuildSyncConflictRows()
    {
        if (SyncConflictRows.Count == 0 && _syncConflictItems.Count == 0) return;
        SyncConflictRows.Clear();
        foreach (var row in ConflictRows()) SyncConflictRows.Add(row);
    }

    private void MarkPageConflicts(IEnumerable<PagePaneRow> rows)
    {
        if (_syncConflictItems.Count == 0) return;
        var related = ConflictRows().Where(row => row.NotebookId == ActiveWorkspace.Id).ToList();
        foreach (var row in rows.Where(row => !row.IsHeader && row.Page != null))
        {
            var matches = related.Where(conflict => conflict.PageId == row.Page!.Id).ToList();
            row.Conflict = matches.FirstOrDefault(conflict => conflict.IsPage)?.Mark ?? (matches.Count > 0 ? ConflictMark.Modify : null);
        }
    }

    private void UpdateActiveSyncConflict() => ActiveSyncConflict = SyncConflictFor(FocusedDocument);

    protected override void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        base.OnPropertyChanged(propertyName);
        if (propertyName is nameof(PrimaryDocument) or nameof(SecondaryDocument) or nameof(FocusedDocument) or nameof(FocusedSlot))
            UpdateActiveSyncConflict();
    }

    private void OpenSyncConflict(SyncConflictRow? row)
    {
        if (row == null) return;
        var notebook = _library.Notebooks.FirstOrDefault(item => item.Id == row.NotebookId);
        SectionNode? owner = null;
        if (row.IsPage && row.Item.LocalFile != null && notebook?.FindPage(row.PageId, out owner) != null && owner != null)
        {
            SaveFocusedDocument();
            if (!_library.SelectNotebook(notebook.Id)) return;
            notebook.FocusedSectionId = owner.Id;
            if (!notebook.SelectPage(owner.Id, row.PageId!, PagePaneMode)) return;
            LoadSelectedIntoFocusedSlot();
            SaveLibrary();
            RefreshNavigation();
            NotifyAllWorkspaceProperties();
        }
        else SyncConflictDialogRequested?.Invoke(row);
    }
}
