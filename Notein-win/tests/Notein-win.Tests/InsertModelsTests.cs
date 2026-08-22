using System.Text.Json;
using Notein_win.Models;
using Xunit;

namespace Notein_win.Tests;

public class InsertModelsTests
{
    [Fact]
    public void PageDocument_JsonRoundTrip_PreservesAllObjectKinds()
    {
        var document = new PageDocument("notebook-1", "page-1");
        var table = PageObject.Table(10, 20, 2, 2);
        table.Cells = new List<string> { "A", "B", "C", "D" };
        document.Objects.Add(table);
        document.Objects.Add(PageObject.Asset(PageObjectKind.Attachment, 10, 130, "file.pdf", "file.pdf", "application/pdf", 102400));
        var image = PageObject.Asset(PageObjectKind.Image, 10, 190, "photo.png", "photo.png", "image/png", 400);
        image.ViewInverted = true;
        document.Objects.Add(image);
        var spreadsheet = PageObject.Asset(PageObjectKind.Spreadsheet, 10, 400, "data.xlsx", "data.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 800);
        spreadsheet.PreviewRows = new List<List<string>> { new() { "Col1", "Col2" } };
        document.Objects.Add(spreadsheet);
        document.Objects.Add(new PageObject { Kind = PageObjectKind.NoteLink, TargetNotebookId = "notebook-1", TargetSectionId = "section-1", TargetPageId = "page-2", DisplayName = "Link Target" });

        var restored = JsonSerializer.Deserialize<PageDocument>(JsonSerializer.Serialize(document))!;

        Assert.Equal(5, restored.Objects.Count);
        Assert.Equal(PageObjectKind.Table, restored.Objects[0].Kind);
        Assert.Equal(PageObjectKind.Attachment, restored.Objects[1].Kind);
        Assert.Equal(PageObjectKind.Image, restored.Objects[2].Kind);
        Assert.Equal(PageObjectKind.Spreadsheet, restored.Objects[3].Kind);
        Assert.Equal(PageObjectKind.NoteLink, restored.Objects[4].Kind);
        Assert.True(restored.Objects[2].ViewInverted);
        Assert.Equal("Col2", restored.Objects[3].PreviewRows[0][1]);
    }
}
