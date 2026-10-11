using System.IO;
using System.Text.Json.Nodes;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class SharedWorkspaceTests
{
    private sealed class TemporaryDirectory : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-shared-test", Guid.NewGuid().ToString("N"));
        public TemporaryDirectory() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }
    [Fact]
    public void IncomingPageCannotBeOverwrittenByAnOlderLoadedDocument()
    {
        using var temp = new TemporaryDirectory();
        var desktop = new WorkspaceStore(temp.Path);
        desktop.SavePage(new PageDocument("book", "page"));
        var older = desktop.LoadPage("book", "page");
        var mobile = new WorkspaceStore(temp.Path);
        var incoming = mobile.LoadPage("book", "page");
        incoming.TextItems.Add(new TextItem { Text = "from phone" });
        mobile.SavePage(incoming);
        Assert.True(desktop.HasExternalChanges);
        Assert.Throws<IOException>(() => desktop.SavePage(older));
        desktop.LoadPage("book", "page");
        Assert.Throws<IOException>(() => desktop.SavePage(older));
        Assert.Equal("from phone", mobile.LoadPage("book", "page").TextItems.Single().Text);
    }

    [Fact]
    public void AndroidOutlineAndRichTextExtensionsSurviveDesktopAutosave()
    {
        using var temp = new TemporaryDirectory();
        var store = new WorkspaceStore(temp.Path);
        var path = store.GetPageDocumentPath("book", "page");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path,"""
            {"version":2,"notebookId":"book","pageId":"page","revision":4,"mobileExtension":{"retained":true},
            "strokes":[{"id":"stroke","vectorPath":"M1,2L3,4L4,2Z","vectorBounds":[1,2,4,4],"vectorTransform":[1,0,0,1,0,0],"points":[{"x":1,"y":2,"pressure":0.25,"elapsedMilliseconds":0}],"sourceBrush":{"id":"retained"}}],
            "textItems":[{"id":"text","text":"shared","androidRichText":"<b>shared</b>"}],"objects":[],"tags":[],"layers":[]}
            """);
        var document = store.LoadPage("book", "page");
        Assert.NotNull(document.Strokes.Single().VectorGeometry);
        store.WritePageSnapshot(document.CopyForSave());
        var written = JsonNode.Parse(File.ReadAllText(path))!;
        Assert.True(written["mobileExtension"]!["retained"]!.GetValue<bool>());
        Assert.Equal("<b>shared</b>", written["textItems"]![0]!["androidRichText"]!.GetValue<string>());
        Assert.Equal("retained", written["strokes"]![0]!["sourceBrush"]!["id"]!.GetValue<string>());
        Assert.Equal("M1,2L3,4L4,2Z", written["strokes"]![0]!["vectorPath"]!.GetValue<string>());
    }

    [Fact]
    public void ExternalWorkspaceRefreshRetainsLocalPageAndReadsIncomingContent()
    {
        using var temp = new TemporaryDirectory();
        var model = new MainViewModel(temp.Path,new PreferencesStore(Path.Combine(temp.Path,"prefs.json")));
        var id = model.FocusedDocument!.PageId;
        var remote = new WorkspaceStore(temp.Path);
        var incoming = remote.LoadPage(model.FocusedDocument.NotebookId,id);
        incoming.TextItems.Add(new TextItem { Text="tablet update" });
        remote.SavePage(incoming);
        Assert.True(model.RefreshSharedWorkspace());
        Assert.Equal(id,model.FocusedDocument!.PageId);
        Assert.Equal("tablet update",model.FocusedDocument.TextItems.Last().Text);
        Assert.False(model.RefreshSharedWorkspace());
    }
}
