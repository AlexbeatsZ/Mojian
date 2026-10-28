using System.IO;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class WorkspaceDeletionTransactionTests
{
    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-deletion-tests", Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }

    [Fact]
    public void FailedIndexCommitRestoresPageAssetsAndPreviousRevision()
    {
        using var temp = new TemporaryDirectory();
        var paths = new[] { "notebooks/book/pages/page.json", "notebooks/book/pages/page.json.previous", "notebooks/book/assets/page/image.png" }
            .Select(relative => Path.Combine(temp.Path, relative)).ToArray();
        foreach (var path in paths) { Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllText(path, path); }

        Assert.Throws<IOException>(() => WorkspaceDeletionTransaction.Execute(temp.Path, paths, () => throw new IOException("index locked")));

        foreach (var path in paths) Assert.Equal(path, File.ReadAllText(path));
    }

    [Fact]
    public void StoreSaveInvalidatesPreparedSnapshotFromAnotherInstance()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var library = store.LoadLibrary();
        var secondStore = new WorkspaceStore(temp.Path + System.IO.Path.DirectorySeparatorChar);
        secondStore.LoadLibrary();
        var epoch = NativeSyncMutationEpoch.ForRoot(temp.Path);
        var before = epoch.Capture() ?? throw new InvalidOperationException("Unexpected active mutation");
        library.ActiveNotebook.Title = "after preparation";
        secondStore.SaveLibrary(library);

        var published = false;
        Assert.Same(epoch, NativeSyncMutationEpoch.ForRoot(secondStore.RootDirectory));
        Assert.NotEqual(before, epoch.Capture());
        Assert.False(epoch.PublishIfUnchanged(before, () => published = true));
        Assert.False(published);
        Assert.Equal("after preparation", store.LoadLibrary().ActiveNotebook.Title);
    }

    [Fact]
    public void CommittedDeletionRetainsRecoveryAndDoesNotRestoreUnreferencedContent()
    {
        using var temp = new TemporaryDirectory();
        var path = Path.Combine(temp.Path, "page.json");
        File.WriteAllText(path, "old");
        WorkspaceDeletionTransaction.Execute(temp.Path, new[] { path }, () => { });

        WorkspaceDeletionTransaction.Recover(temp.Path, _ => false);
        Assert.False(File.Exists(path));
        WorkspaceDeletionTransaction.Recover(temp.Path, _ => true);
        Assert.Equal("old", File.ReadAllText(path));
        File.WriteAllText(path, "new reference");
        WorkspaceDeletionTransaction.Recover(temp.Path, _ => true);
        Assert.Equal("new reference", File.ReadAllText(path));
    }

    [Fact]
    public void RestartWithOriginalIndexRecoversInterruptedStaging()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var library = store.LoadLibrary();
        var notebook = library.ActiveNotebook;
        var page = notebook.SelectedPage()!;
        var path = store.GetPageDocumentPath(notebook.Id, page.Id);
        var original = File.ReadAllText(path);
        WorkspaceDeletionTransaction.Execute(temp.Path, new[] { path }, () => { });

        new WorkspaceStore(temp.Path).LoadLibrary();

        Assert.Equal(original, File.ReadAllText(path));
    }

    [Fact]
    public void ExternalLibraryChangeRejectsDeletionBeforeStaging()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var library = store.LoadLibrary();
        var notebook = library.ActiveNotebook;
        var path = store.GetPageDocumentPath(notebook.Id, notebook.SelectedPage()!.Id);
        File.AppendAllText(store.LibraryPath, " ");
        var candidate = library.CopyForSave();
        candidate.DeleteNotebook(notebook.Id);

        Assert.Throws<IOException>(() => store.CommitDeletion(candidate, notebook.Id));

        Assert.True(File.Exists(path));
        Assert.Contains(library.Notebooks, item => item.Id == notebook.Id);
    }

    [Fact]
    public void ViewModelKeepsOriginalLibraryAndDocumentWhenDeletionIsRejected()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var library = vm.Library;
        var document = vm.PrimaryDocument;
        var notebook = vm.ActiveWorkspace;
        var page = notebook.SelectedPage()!;
        File.AppendAllText(vm.Store.LibraryPath, " ");

        Assert.Throws<IOException>(() => vm.DeletePage(notebook.FocusedSectionId!, page.Id));

        Assert.Same(library, vm.Library);
        Assert.Same(document, vm.PrimaryDocument);
        Assert.NotNull(notebook.FindPage(page.Id, out _));
        Assert.True(File.Exists(vm.Store.GetPageDocumentPath(notebook.Id, page.Id)));
    }

    [Fact]
    public void IncomingIsRejectedAfterLocalSaveFailureEvenWhenEditorIsIdle()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        File.AppendAllText(vm.Store.LibraryPath, " ");
        Assert.Throws<IOException>(() => vm.SaveAll());
        var applied = false;

        Assert.False(vm.TryApplyNativeSync(() => applied = true, () => true));

        Assert.False(applied);
        Assert.True(vm.HasSaveError);
    }

    [Fact]
    public void IncomingReloadRefusalLocksEditingUntilOfflineReloadCompletes()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var queue = Assert.IsType<PageSaveQueue>(typeof(MainViewModel).GetField("_pageSaves", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.GetValue(vm));
        try
        {
            Assert.Throws<IOException>(() => vm.TryApplyNativeSync(() => vm.QueueSaveDocument(vm.PrimaryDocument), () => true));
            Assert.True(vm.NativeSyncRecoveryRequired);
            Assert.False(vm.CanApplyNativeSync);
            Assert.Throws<IOException>(() => vm.SaveAll());
        }
        finally { queue.Flush(); }

        Assert.True(vm.TryRecoverNativeSync(() => { }));
        Assert.False(vm.NativeSyncRecoveryRequired);
        Assert.True(vm.CanApplyNativeSync);
    }

    [Fact]
    public void IncomingFailureLocksSavingUntilOfflineRecoveryAndReloadComplete()
    {
        using var temp = new TemporaryDirectory();
        var vm = new MainViewModel(Path.Combine(temp.Path, "workspace"), new PreferencesStore(Path.Combine(temp.Path, "preferences.json")));
        var original = File.ReadAllText(vm.Store.LibraryPath);
        Assert.Throws<IOException>(() => vm.TryApplyNativeSync(() =>
        {
            File.WriteAllText(vm.Store.LibraryPath, "partial incoming");
            throw new IOException("apply interrupted");
        }, () => true));

        Assert.True(vm.NativeSyncRecoveryRequired);
        Assert.False(vm.CanApplyNativeSync);
        Assert.Throws<IOException>(() => vm.SaveAll());
        Assert.Equal("partial incoming", File.ReadAllText(vm.Store.LibraryPath));
        Assert.Throws<IOException>(() => vm.TryRecoverNativeSync(() => throw new IOException("recovery failed")));
        Assert.True(vm.NativeSyncRecoveryRequired);

        Assert.True(vm.TryRecoverNativeSync(() => File.WriteAllText(vm.Store.LibraryPath, original)));

        Assert.False(vm.NativeSyncRecoveryRequired);
        Assert.True(vm.CanApplyNativeSync);
        Assert.Equal(original, File.ReadAllText(vm.Store.LibraryPath));
    }
}
