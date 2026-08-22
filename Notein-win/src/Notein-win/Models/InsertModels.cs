using System.Text.Json.Nodes;

namespace Notein_win.Models;

public enum InsertObjectType
{
    Table,
    Attachment,
    Image,
    Spreadsheet,
    NoteLink,
}

public enum ImageSourceKind
{
    Image,
    PdfPage,
    Sticker,
}

public enum ImageImportDisplayMode
{
    Ask,
    Invert,
    Original,
}

public static class ImageImportDisplayModeExtensions
{
    public static string GetLabel(this ImageImportDisplayMode mode) => mode switch
    {
        ImageImportDisplayMode.Ask => "导入时询问",
        ImageImportDisplayMode.Invert => "自动反色",
        ImageImportDisplayMode.Original => "保持原图",
        _ => "导入时询问"
    };
}

public enum CanvasBackgroundFormat
{
    Blank,
    RuledNarrow,
    RuledStandard,
    RuledWide,
    GridDense,
    GridStandard,
    GridWide,
    DotsDense,
    DotsStandard,
    DotsWide,
}

public static class CanvasBackgroundFormatExtensions
{
    public static string GetLabel(this CanvasBackgroundFormat format) => format switch
    {
        CanvasBackgroundFormat.Blank => "无横线/空白",
        CanvasBackgroundFormat.RuledNarrow => "窄横线",
        CanvasBackgroundFormat.RuledStandard => "标准横线",
        CanvasBackgroundFormat.RuledWide => "宽横线",
        CanvasBackgroundFormat.GridDense => "密集网格",
        CanvasBackgroundFormat.GridStandard => "标准网格",
        CanvasBackgroundFormat.GridWide => "宽网格",
        CanvasBackgroundFormat.DotsDense => "密集点阵",
        CanvasBackgroundFormat.DotsStandard => "标准点阵",
        CanvasBackgroundFormat.DotsWide => "宽点阵",
        _ => "标准网格"
    };
}

public abstract class InsertObject
{
    public string Id { get; set; }
    public float DocumentX { get; set; }
    public float DocumentY { get; set; }
    public float Width { get; set; }
    public float Height { get; set; }
    public abstract InsertObjectType Type { get; }

    protected InsertObject(string id, float documentX, float documentY, float width, float height)
    {
        Id = id;
        DocumentX = documentX;
        DocumentY = documentY;
        Width = width;
        Height = height;
    }

    public virtual JsonObject ToJson()
    {
        var obj = new JsonObject
        {
            ["id"] = Id,
            ["type"] = Type.ToString().ToUpperInvariant(),
            ["x"] = DocumentX,
            ["y"] = DocumentY,
            ["width"] = Width,
            ["height"] = Height
        };
        return obj;
    }

    public static InsertObject FromJson(JsonObject json)
    {
        var id = json["id"]?.GetValue<string>() ?? Guid.NewGuid().ToString();
        var x = json["x"]?.GetValue<float>() ?? 0f;
        var y = json["y"]?.GetValue<float>() ?? 0f;
        var width = json["width"]?.GetValue<float>() ?? 100f;
        var height = json["height"]?.GetValue<float>() ?? 100f;
        var typeStr = json["type"]?.GetValue<string>() ?? "TABLE";

        var type = Enum.TryParse<InsertObjectType>(typeStr, true, out var parsedType)
            ? parsedType
            : InsertObjectType.Table;

        return type switch
        {
            InsertObjectType.Table => new TableObject(
                id, x, y, width, height,
                json["rows"]?.GetValue<int>() ?? 2,
                json["columns"]?.GetValue<int>() ?? 2,
                json["cells"] is JsonArray cellsArr
                    ? cellsArr.Select(c => c?.GetValue<string>() ?? "").ToList()
                    : new List<string>()
            ),
            InsertObjectType.Attachment => new AttachmentObject(
                id, x, y, width, height,
                json["assetName"]?.GetValue<string>() ?? "",
                json["displayName"]?.GetValue<string>() ?? "附件",
                json["mimeType"]?.GetValue<string>() ?? "application/octet-stream",
                json["sizeBytes"]?.GetValue<long>() ?? 0L
            ),
            InsertObjectType.Image => new ImageObject(
                id, x, y, width, height,
                json["assetName"]?.GetValue<string>() ?? "",
                json["displayName"]?.GetValue<string>() ?? "图片",
                json["mimeType"]?.GetValue<string>() ?? "image/png",
                Enum.TryParse<ImageSourceKind>(json["sourceKind"]?.GetValue<string>(), true, out var sourceKind) ? sourceKind : ImageSourceKind.Image,
                json["viewInverted"]?.GetValue<bool>() ?? false,
                json["sourceAssetName"]?.GetValue<string>(),
                json["sourcePageNumber"]?.GetValue<int>()
            ),
            InsertObjectType.Spreadsheet => new SpreadsheetObject(
                id, x, y, width, height,
                json["assetName"]?.GetValue<string>() ?? "",
                json["displayName"]?.GetValue<string>() ?? "电子表格",
                json["mimeType"]?.GetValue<string>() ?? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                json["previewRows"] is JsonArray rowsArr
                    ? rowsArr.Select(r => (r as JsonArray)?.Select(c => c?.GetValue<string>() ?? "").ToList() ?? new List<string>()).ToList()
                    : new List<List<string>>()
            ),
            InsertObjectType.NoteLink => new NoteLinkObject(
                id, x, y, width, height,
                json["targetNotebookId"]?.GetValue<string>() ?? "",
                json["targetSectionId"]?.GetValue<string>() ?? "",
                json["targetPageId"]?.GetValue<string>() ?? "",
                json["targetTitle"]?.GetValue<string>() ?? "笔记链接"
            ),
            _ => throw new NotSupportedException($"Unsupported object type {type}")
        };
    }
}

public class TableObject : InsertObject
{
    public override InsertObjectType Type => InsertObjectType.Table;
    public int Rows { get; set; }
    public int Columns { get; set; }
    public List<string> Cells { get; set; }

    public TableObject(string id, float documentX, float documentY, float width, float height, int rows, int columns, IEnumerable<string>? cells = null)
        : base(id, documentX, documentY, width, height)
    {
        Rows = rows;
        Columns = columns;
        var list = (cells ?? Enumerable.Empty<string>()).ToList();
        while (list.Count < rows * columns) list.Add("");
        Cells = list;
    }

    public override JsonObject ToJson()
    {
        var obj = base.ToJson();
        obj["rows"] = Rows;
        obj["columns"] = Columns;
        var arr = new JsonArray();
        foreach (var cell in Cells) arr.Add(cell);
        obj["cells"] = arr;
        return obj;
    }
}

public class AttachmentObject : InsertObject
{
    public override InsertObjectType Type => InsertObjectType.Attachment;
    public string AssetName { get; set; }
    public string DisplayName { get; set; }
    public string MimeType { get; set; }
    public long SizeBytes { get; set; }

    public AttachmentObject(string id, float documentX, float documentY, float width, float height, string assetName, string displayName, string mimeType = "application/octet-stream", long sizeBytes = 0L)
        : base(id, documentX, documentY, width, height)
    {
        AssetName = assetName;
        DisplayName = displayName;
        MimeType = mimeType;
        SizeBytes = sizeBytes;
    }

    public override JsonObject ToJson()
    {
        var obj = base.ToJson();
        obj["assetName"] = AssetName;
        obj["displayName"] = DisplayName;
        obj["mimeType"] = MimeType;
        obj["sizeBytes"] = SizeBytes;
        return obj;
    }
}

public class ImageObject : InsertObject
{
    public override InsertObjectType Type => InsertObjectType.Image;
    public string AssetName { get; set; }
    public string DisplayName { get; set; }
    public string MimeType { get; set; }
    public ImageSourceKind SourceKind { get; set; }
    public bool ViewInverted { get; set; }
    public string? SourceAssetName { get; set; }
    public int? SourcePageNumber { get; set; }

    public ImageObject(
        string id,
        float documentX,
        float documentY,
        float width,
        float height,
        string assetName,
        string displayName,
        string mimeType = "image/png",
        ImageSourceKind sourceKind = ImageSourceKind.Image,
        bool viewInverted = false,
        string? sourceAssetName = null,
        int? sourcePageNumber = null)
        : base(id, documentX, documentY, width, height)
    {
        AssetName = assetName;
        DisplayName = displayName;
        MimeType = mimeType;
        SourceKind = sourceKind;
        ViewInverted = viewInverted;
        SourceAssetName = sourceAssetName;
        SourcePageNumber = sourcePageNumber;
    }

    public override JsonObject ToJson()
    {
        var obj = base.ToJson();
        obj["assetName"] = AssetName;
        obj["displayName"] = DisplayName;
        obj["mimeType"] = MimeType;
        obj["sourceKind"] = SourceKind.ToString();
        obj["viewInverted"] = ViewInverted;
        if (SourceAssetName != null) obj["sourceAssetName"] = SourceAssetName;
        if (SourcePageNumber != null) obj["sourcePageNumber"] = SourcePageNumber;
        return obj;
    }
}

public class SpreadsheetObject : InsertObject
{
    public override InsertObjectType Type => InsertObjectType.Spreadsheet;
    public string AssetName { get; set; }
    public string DisplayName { get; set; }
    public string MimeType { get; set; }
    public List<List<string>> PreviewRows { get; set; }

    public SpreadsheetObject(string id, float documentX, float documentY, float width, float height, string assetName, string displayName, string mimeType, IEnumerable<List<string>>? previewRows = null)
        : base(id, documentX, documentY, width, height)
    {
        AssetName = assetName;
        DisplayName = displayName;
        MimeType = mimeType;
        PreviewRows = previewRows?.ToList() ?? new List<List<string>>();
    }

    public override JsonObject ToJson()
    {
        var obj = base.ToJson();
        obj["assetName"] = AssetName;
        obj["displayName"] = DisplayName;
        obj["mimeType"] = MimeType;
        var arr = new JsonArray();
        foreach (var row in PreviewRows)
        {
            var rowArr = new JsonArray();
            foreach (var cell in row) rowArr.Add(cell);
            arr.Add(rowArr);
        }
        obj["previewRows"] = arr;
        return obj;
    }
}

public class NoteLinkObject : InsertObject
{
    public override InsertObjectType Type => InsertObjectType.NoteLink;
    public string TargetNotebookId { get; set; }
    public string TargetSectionId { get; set; }
    public string TargetPageId { get; set; }
    public string TargetTitle { get; set; }

    public NoteLinkObject(string id, float documentX, float documentY, float width, float height, string targetNotebookId, string targetSectionId, string targetPageId, string targetTitle)
        : base(id, documentX, documentY, width, height)
    {
        TargetNotebookId = targetNotebookId;
        TargetSectionId = targetSectionId;
        TargetPageId = targetPageId;
        TargetTitle = targetTitle;
    }

    public override JsonObject ToJson()
    {
        var obj = base.ToJson();
        obj["targetNotebookId"] = TargetNotebookId;
        obj["targetSectionId"] = TargetSectionId;
        obj["targetPageId"] = TargetPageId;
        obj["targetTitle"] = TargetTitle;
        return obj;
    }
}

public class PageInsertDocument
{
    public string NotebookId { get; set; }
    public string PageId { get; set; }
    public List<InsertObject> Objects { get; set; } = new();

    public PageInsertDocument(string notebookId, string pageId, IEnumerable<InsertObject>? objects = null)
    {
        NotebookId = notebookId;
        PageId = pageId;
        if (objects != null) Objects = objects.ToList();
    }

    public JsonObject ToJson()
    {
        var obj = new JsonObject
        {
            ["version"] = 1,
            ["notebookId"] = NotebookId,
            ["pageId"] = PageId
        };
        var arr = new JsonArray();
        foreach (var item in Objects) arr.Add(item.ToJson());
        obj["objects"] = arr;
        return obj;
    }

    public static PageInsertDocument FromJson(JsonObject json)
    {
        var doc = new PageInsertDocument(
            json["notebookId"]?.GetValue<string>() ?? "",
            json["pageId"]?.GetValue<string>() ?? ""
        );
        if (json["objects"] is JsonArray arr)
        {
            foreach (var item in arr)
            {
                if (item is JsonObject itemObj)
                {
                    doc.Objects.Add(InsertObject.FromJson(itemObj));
                }
            }
        }
        return doc;
    }
}
