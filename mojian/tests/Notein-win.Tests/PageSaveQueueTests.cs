using System.IO;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class PageSaveQueueTests
{
    [Fact]
    public void RetryingOlderPageDoesNotRevertNewerLibrary()
    {
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-save-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var store = new WorkspaceStore(root);
            var library = store.LoadLibrary();
            var queue = new PageSaveQueue();
            var older = new PageDocument("n", "older");
            store.SavePage(older);
            using (File.Open(store.GetPageDocumentPath("n", "older"), FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            {
                library.ActiveNotebook.Title = "older";
                queue.Enqueue(store, older.CopyForSave(), library.CopyForSave());
                Assert.Throws<IOException>(queue.Flush);
                library.ActiveNotebook.Title = "newer";
                queue.Enqueue(store, new PageDocument("n", "newer"), library.CopyForSave());
                Assert.Throws<IOException>(queue.Flush);
            }
            queue.Flush();
            Assert.Equal("newer", store.LoadLibrary().ActiveNotebook.Title);
            Assert.True(File.Exists(store.GetPageDocumentPath("n", "older")));
        }
        finally { Directory.Delete(root, true); }
    }
}
