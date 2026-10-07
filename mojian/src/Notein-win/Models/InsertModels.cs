using System.Text.Json.Serialization;

namespace Notein_win.Models;

public enum PageObjectKind
{
    Table,
    Attachment,
    Image,
    PdfPrintout,
    Spreadsheet,
    Sticker,
    NoteLink,
}

public enum ImageImportDisplayMode
{
    Ask,
    Invert,
    Original,
}

public static class ImageImportDisplayModeExtensions
{
    public static string GetLabel(this ImageImportDisplayMode value) => value switch
    {
        ImageImportDisplayMode.Ask => "导入时询问",
        ImageImportDisplayMode.Invert => "自动反色",
        ImageImportDisplayMode.Original => "保持原图",
        _ => "导入时询问",
    };
}

public sealed class PageObject
{
    public PageObject CopyForSave()
    {
        var copy = (PageObject)MemberwiseClone();
        copy.Cells = Cells.ToList();
        copy.PreviewRows = PreviewRows.Select(row => row.ToList()).ToList();
        return copy;
    }
    public string LayerId { get; set; } = string.Empty;
    public long ZOrder { get; set; }
    public string Id { get; set; } = IdFactory.New("object");
    public PageObjectKind Kind { get; set; }
    public float X { get; set; }
    public float Y { get; set; }
    public float Width { get; set; } = 240;
    public float Height { get; set; } = 120;
    public string DisplayName { get; set; } = string.Empty;
    public string AssetName { get; set; } = string.Empty;
    public string MimeType { get; set; } = "application/octet-stream";
    public long SizeBytes { get; set; }
    public bool ViewInverted { get; set; }
    public int Rows { get; set; }
    public int Columns { get; set; }
    public List<string> Cells { get; set; } = new();
    public List<List<string>> PreviewRows { get; set; } = new();
    public string TargetNotebookId { get; set; } = string.Empty;
    public string TargetSectionId { get; set; } = string.Empty;
    public string TargetPageId { get; set; } = string.Empty;
    public string SourceAssetName { get; set; } = string.Empty;
    public int? SourcePageNumber { get; set; }
    public bool IsPageBackground { get; set; }
    public int RotationQuarterTurns { get; set; }
    public bool FlipHorizontal { get; set; }
    public bool FlipVertical { get; set; }

    [JsonIgnore]
    public bool IsImageLike => Kind is PageObjectKind.Image or PageObjectKind.PdfPrintout or PageObjectKind.Sticker;

    public PageObject CloneWithNewId(float offset = 24) => new()
    {
        LayerId = LayerId,
        ZOrder = ZOrder,
        Id = IdFactory.New("object"),
        Kind = Kind,
        X = X + offset,
        Y = Y + offset,
        Width = Width,
        Height = Height,
        DisplayName = DisplayName,
        AssetName = AssetName,
        MimeType = MimeType,
        SizeBytes = SizeBytes,
        ViewInverted = ViewInverted,
        Rows = Rows,
        Columns = Columns,
        Cells = Cells.ToList(),
        PreviewRows = PreviewRows.Select(row => row.ToList()).ToList(),
        TargetNotebookId = TargetNotebookId,
        TargetSectionId = TargetSectionId,
        TargetPageId = TargetPageId,
        SourceAssetName = SourceAssetName,
        SourcePageNumber = SourcePageNumber,
        IsPageBackground = false,
        RotationQuarterTurns = ((RotationQuarterTurns % 4) + 4) % 4,
        FlipHorizontal = FlipHorizontal,
        FlipVertical = FlipVertical,
    };

    public static PageObject Table(float x, float y, int rows = 3, int columns = 3) => new()
    {
        Kind = PageObjectKind.Table,
        X = x,
        Y = y,
        Width = Math.Max(180, columns * 90),
        Height = Math.Max(90, rows * 36),
        Rows = rows,
        Columns = columns,
        Cells = Enumerable.Repeat(string.Empty, rows * columns).ToList(),
    };

    public static PageObject Asset(PageObjectKind kind, float x, float y, string assetName, string displayName, string mimeType, long sizeBytes) => new()
    {
        Kind = kind,
        X = x,
        Y = y,
        Width = kind is PageObjectKind.Image or PageObjectKind.Sticker or PageObjectKind.PdfPrintout ? 360 : 280,
        Height = kind is PageObjectKind.Image or PageObjectKind.Sticker or PageObjectKind.PdfPrintout ? 240 : 64,
        AssetName = assetName,
        DisplayName = displayName,
        MimeType = mimeType,
        SizeBytes = sizeBytes,
    };
}
