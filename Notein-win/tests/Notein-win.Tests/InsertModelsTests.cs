using Notein_win.Models;
using Xunit;

namespace Notein_win.Tests;

public class InsertModelsTests
{
    [Fact]
    public void PageInsertDocument_JsonRoundTrip_AllObjectTypes()
    {
        var doc = new PageInsertDocument("notebook-1", "page-1");

        doc.Objects.Add(new TableObject("t1", 10, 20, 200, 100, 2, 2, new[] { "A", "B", "C", "D" }));
        doc.Objects.Add(new AttachmentObject("a1", 10, 130, 220, 50, "file.pdf", "file.pdf", "application/pdf", 102400));
        doc.Objects.Add(new ImageObject("i1", 10, 190, 300, 200, "photo.png", "photo.png", "image/png", ImageSourceKind.Image, true));
        doc.Objects.Add(new SpreadsheetObject("s1", 10, 400, 320, 160, "data.xlsx", "data.xlsx", "application/vnd.openxmlformats", new List<List<string>> { new() { "Col1", "Col2" } }));
        doc.Objects.Add(new NoteLinkObject("l1", 10, 570, 180, 30, "notebook-1", "section-1", "page-2", "Link Target"));

        var json = doc.ToJson();
        Assert.NotNull(json);

        var restored = PageInsertDocument.FromJson(json);
        Assert.Equal(doc.NotebookId, restored.NotebookId);
        Assert.Equal(doc.PageId, restored.PageId);
        Assert.Equal(5, restored.Objects.Count);

        Assert.IsType<TableObject>(restored.Objects[0]);
        Assert.IsType<AttachmentObject>(restored.Objects[1]);
        Assert.IsType<ImageObject>(restored.Objects[2]);
        Assert.IsType<SpreadsheetObject>(restored.Objects[3]);
        Assert.IsType<NoteLinkObject>(restored.Objects[4]);

        var img = (ImageObject)restored.Objects[2];
        Assert.True(img.ViewInverted);
    }
}
