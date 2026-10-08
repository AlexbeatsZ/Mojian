using System.IO.Compression;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

public static class NotebookPackageService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = true };

    public static void Export(string path, NotebookWorkspace notebook, WorkspaceStore store, string? currentPageId, IReadOnlyDictionary<string, byte[]>? layers = null)
        => ExportRoots(path, notebook, notebook.Sections, "mojian-notebook", store, currentPageId, layers);

    public static void ExportSection(string path, NotebookWorkspace notebook, SectionNode section, WorkspaceStore store, string? currentPageId = null, IReadOnlyDictionary<string, byte[]>? layers = null)
        => ExportRoots(path, notebook, new[] { section }, "mojian-section", store, currentPageId, layers);

    public static void ExportPage(string path, NotebookWorkspace notebook, NotePage page, WorkspaceStore store)
        => ExportRoots(path, notebook, [new SectionNode { Title = page.Title, Pages = [page] }], "mojian-section", store, page.Id, null);

    private static void ExportRoots(string path, NotebookWorkspace notebook, IReadOnlyCollection<SectionNode> roots, string format, WorkspaceStore store, string? currentPageId, IReadOnlyDictionary<string, byte[]>? layers)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        using var archive = ZipFile.Open(path, ZipArchiveMode.Create);
        WriteText(archive, "manifest.json", BuildManifest(notebook, roots, format, currentPageId).ToJsonString(JsonOptions));
        foreach (var section in FlattenSections(roots))
        {
            foreach (var page in section.Pages)
            {
                var document = store.LoadPage(notebook.Id, page.Id);
                WriteText(archive, $"pages/{WorkspaceStore.SafeId(page.Id)}.json", AndroidPageJson(document).ToJsonString(JsonOptions));
                WriteText(archive, $"windows/pages/{WorkspaceStore.SafeId(page.Id)}.json", JsonSerializer.Serialize(document, JsonOptions));
                var assetDirectory = store.GetAssetDirectory(notebook.Id, page.Id);
                foreach (var asset in Directory.EnumerateFiles(assetDirectory).OrderBy(Path.GetFileName))
                {
                    archive.CreateEntryFromFile(asset, $"assets/{WorkspaceStore.SafeId(page.Id)}/{WorkspaceStore.SafeAssetName(Path.GetFileName(asset))}", CompressionLevel.Optimal);
                }
            }
        }
        if (!string.IsNullOrWhiteSpace(currentPageId) && layers != null)
        {
            foreach (var (name, bytes) in layers)
            {
                var entry = archive.CreateEntry($"layers/{WorkspaceStore.SafeId(currentPageId)}/{SafeLabel(name)}.png", CompressionLevel.Optimal);
                using var stream = entry.Open();
                stream.Write(bytes);
            }
        }
    }

    private static JsonObject BuildManifest(NotebookWorkspace notebook, IEnumerable<SectionNode> roots, string format, string? currentPageId) => new()
    {
        ["format"] = format,
        ["version"] = 1,
        ["notebookId"] = notebook.Id,
        ["title"] = notebook.Title,
        ["currentPageId"] = currentPageId,
        ["pdfLayerOrder"] = new JsonArray("页面背景", "插入内容", "墨迹与文字"),
        ["sections"] = SectionsJson(roots),
    };

    private static IEnumerable<SectionNode> FlattenSections(IEnumerable<SectionNode> roots)
    {
        foreach (var section in roots)
        {
            yield return section;
            foreach (var child in FlattenSections(section.Children)) yield return child;
        }
    }

    private static JsonArray SectionsJson(IEnumerable<SectionNode> sections)
    {
        var result = new JsonArray();
        foreach (var section in sections)
        {
            var pages = new JsonArray();
            foreach (var page in section.Pages)
            {
                var pageJson = new JsonObject
                {
                    ["id"] = page.Id,
                    ["title"] = page.Title,
                    ["kind"] = page.IsPdfPage ? "PDF" : "PAGE",
                    ["viewSettings"] = ViewSettingsJson(page),
                };
                if (page.IsPdfPage)
                {
                    pageJson["pdf"] = new JsonObject
                    {
                        ["sourceOwnerPageId"] = page.Id,
                        ["sourceAssetName"] = page.PdfSourceAssetName ?? string.Empty,
                        ["sourceDocumentName"] = page.PdfSourceDocumentName ?? "PDF",
                        ["pageNumber"] = page.PdfPageNumber ?? 1,
                        ["pageCount"] = page.PdfPageCount ?? 1,
                        ["widthPoints"] = page.PdfWidthPoints ?? page.Appearance.Width * 72d / 96d,
                        ["heightPoints"] = page.PdfHeightPoints ?? page.Appearance.Height * 72d / 96d,
                    };
                }
                pages.Add(pageJson);
            }
            result.Add(new JsonObject
            {
                ["id"] = section.Id,
                ["title"] = section.Title,
                ["pages"] = pages,
                ["children"] = SectionsJson(section.Children),
            });
        }
        return result;
    }

    private static JsonObject ViewSettingsJson(NotePage page)
    {
        var appearance = page.Appearance;
        var result = new JsonObject
        {
            ["paperSize"] = appearance.PaperKind switch { PaperKind.A4 => "A4", PaperKind.Pdf => "PDF", _ => "BOUNDLESS" },
            ["pageColor"] = ColorName(appearance.PageColor),
            ["backgroundFormat"] = ToAndroidBackground(appearance.Background),
            ["pageTitleVisible"] = appearance.ShowTitle,
            ["darkBackground"] = appearance.Theme == PageTheme.Dark,
            ["a4PageCount"] = Math.Max(1, appearance.SheetCount),
        };
        if (appearance.PaperKind == PaperKind.Pdf)
        {
            result["customDocumentWidth"] = appearance.Width;
            result["customDocumentHeight"] = appearance.Height;
        }
        if (appearance.PaperKind == PaperKind.Infinite)
        {
            result["boundlessDocumentWidth"] = appearance.InfiniteWidth * InfinitePageLayout.AndroidUnitsPerDesktopUnit;
            result["boundlessDocumentHeight"] = appearance.InfiniteHeight * InfinitePageLayout.AndroidUnitsPerDesktopUnit;
        }
        return result;
    }

    private static JsonObject AndroidPageJson(PageDocument document)
    {
        var objects = new JsonArray();
        foreach (var item in document.Objects) objects.Add(AndroidObjectJson(item));
        return new JsonObject
        {
            ["version"] = 1,
            ["notebookId"] = document.NotebookId,
            ["pageId"] = document.PageId,
            ["objects"] = objects,
            ["strokes"] = JsonSerializer.SerializeToNode(document.Strokes.Where(stroke => stroke.BrushProfile == "shape-v1"), JsonOptions),
        };
    }

    private static JsonObject AndroidObjectJson(PageObject item)
    {
        var result = new JsonObject
        {
            ["id"] = item.Id,
            ["type"] = item.Kind switch
            {
                PageObjectKind.Table => "TABLE",
                PageObjectKind.Attachment => "ATTACHMENT",
                PageObjectKind.Spreadsheet => "SPREADSHEET",
                PageObjectKind.NoteLink => "NOTE_LINK",
                _ => "IMAGE",
            },
            ["x"] = item.X,
            ["y"] = item.Y,
            ["width"] = item.Width,
            ["height"] = item.Height,
        };
        if (item.Kind == PageObjectKind.Table)
        {
            result["rows"] = item.Rows;
            result["columns"] = item.Columns;
            result["cells"] = new JsonArray(item.Cells.Select(value => (JsonNode?)JsonValue.Create(value)).ToArray());
        }
        else if (item.Kind == PageObjectKind.NoteLink)
        {
            result["targetNotebookId"] = item.TargetNotebookId;
            result["targetSectionId"] = item.TargetSectionId;
            result["targetPageId"] = item.TargetPageId;
            result["targetTitle"] = item.DisplayName;
        }
        else
        {
            result["assetName"] = item.AssetName;
            result["displayName"] = item.DisplayName;
            result["mimeType"] = item.MimeType;
            if (item.Kind == PageObjectKind.Attachment) result["sizeBytes"] = item.SizeBytes;
            if (item.Kind == PageObjectKind.Spreadsheet)
            {
                result["previewRows"] = new JsonArray(item.PreviewRows.Select(row => (JsonNode?)new JsonArray(row.Select(value => (JsonNode?)JsonValue.Create(value)).ToArray())).ToArray());
            }
            if (item.Kind is PageObjectKind.Image or PageObjectKind.PdfPrintout or PageObjectKind.Sticker)
            {
                result["sourceKind"] = item.Kind == PageObjectKind.PdfPrintout ? "PDF_PAGE" : item.Kind == PageObjectKind.Sticker ? "STICKER" : "IMAGE";
                result["viewInverted"] = item.ViewInverted;
                if (!string.IsNullOrWhiteSpace(item.SourceAssetName)) result["sourceAssetName"] = item.SourceAssetName;
                if (item.SourcePageNumber.HasValue) result["sourcePageNumber"] = item.SourcePageNumber.Value;
                result["pageBackground"] = item.IsPageBackground;
                result["rotationQuarterTurns"] = ((item.RotationQuarterTurns % 4) + 4) % 4;
                result["flipHorizontal"] = item.FlipHorizontal;
                result["flipVertical"] = item.FlipVertical;
            }
        }
        return result;
    }

    private static string ColorName(string color) => color.ToUpperInvariant() switch
    {
        "#302A21" or "#F3E9D2" => "SAND",
        "#1D2B38" or "#E2EDF8" => "BLUE",
        "#1E3028" or "#E0F1E7" => "MINT",
        "#352329" or "#F4E2E8" => "ROSE",
        _ => "WHITE",
    };

    private static string ToAndroidBackground(CanvasBackgroundFormat value) => value switch
    {
        CanvasBackgroundFormat.Blank => "BLANK",
        CanvasBackgroundFormat.RuledNarrow => "RULED_NARROW",
        CanvasBackgroundFormat.RuledStandard => "RULED_STANDARD",
        CanvasBackgroundFormat.RuledWide => "RULED_WIDE",
        CanvasBackgroundFormat.GridDense => "GRID_DENSE",
        CanvasBackgroundFormat.GridStandard => "GRID_STANDARD",
        CanvasBackgroundFormat.GridWide => "GRID_WIDE",
        CanvasBackgroundFormat.DotsDense => "DOTS_DENSE",
        CanvasBackgroundFormat.DotsStandard => "DOTS_STANDARD",
        CanvasBackgroundFormat.DotsWide => "DOTS_WIDE",
        _ => "GRID_STANDARD",
    };

    private static void WriteText(ZipArchive archive, string name, string value)
    {
        var entry = archive.CreateEntry(name, CompressionLevel.Optimal);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(value);
    }

    private static string SafeLabel(string value) => string.Concat(value.Select(character => "\\/:*?\"<>|".Contains(character) ? '_' : character)).Replace("..", "_");
}
